package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.RemoteFileName
import com.scrcpy.remote.session.FileTransfers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FileTransferTest {

    /** Captures what was written and replies with whatever the test scripts. */
    private class FakeChannel(reply: ByteArray) : SyncChannel {
        val written = ByteArrayOutputStream()
        override val output: OutputStream = written
        override val input: InputStream = ByteArrayInputStream(reply)
        override fun close() = Unit
    }

    private fun okay(): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put("OKAY".toByteArray(Charsets.US_ASCII)).putInt(0).array()

    private fun fail(message: String): ByteArray {
        val raw = message.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + raw.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("FAIL".toByteArray(Charsets.US_ASCII)).putInt(raw.size).put(raw).array()
    }

    /** Extracts the payload of every DATA chunk, which is the file adbd would write. */
    private fun dataPayload(frame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = frame.indexOf("DATA")
        while (offset >= 0 && offset + 8 <= frame.size) {
            val id = String(frame, offset, 4, Charsets.US_ASCII)
            if (id != "DATA") break
            val length = ByteBuffer.wrap(frame, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            out.write(frame, offset + 8, length)
            offset += 8 + length
        }
        return out.toByteArray()
    }

    private fun ByteArray.indexOf(marker: String): Int {
        val needle = marker.toByteArray(Charsets.US_ASCII)
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }

    @Test fun streamingAPushSendsExactlyTheFilesBytesInOrder() {
        // Larger than one 64 KiB chunk, so the chunk loop itself is exercised.
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val channel = FakeChannel(okay())
        AdbSync.push({ channel }, ByteArrayInputStream(content), content.size.toLong(), "/sdcard/Download/x.bin")
        assertArrayEqualsMessage(content, dataPayload(channel.written.toByteArray()))
    }

    @Test fun progressIsReportedAndEndsAtTheFullSize() {
        val content = ByteArray(150_000)
        val seen = mutableListOf<Long>()
        AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(content), content.size.toLong(),
            "/sdcard/Download/x.bin", onProgress = { seen += it })
        assertTrue("Progress must advance", seen.size >= 2)
        assertEquals(content.size.toLong(), seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test fun cancellingStopsWithoutCompletingTheFile() {
        // Abandoning before DONE is what makes adbd discard the partial file rather than keep a
        // truncated one, so the frame must not contain DONE.
        val channel = FakeChannel(okay())
        val content = ByteArray(200_000)
        assertThrows(InterruptedIOException::class.java) {
            AdbSync.push({ channel }, ByteArrayInputStream(content), content.size.toLong(),
                "/sdcard/Download/x.bin", isCancelled = { true })
        }
        assertEquals(-1, channel.written.toByteArray().indexOf("DONE"))
    }

    @Test fun cancellingAtTheFinalProgressCallbackDoesNotCommitTheFile() {
        val channel = FakeChannel(okay())
        val content = ByteArray(65_537) { (it % 251).toByte() }
        var cancelled = false
        assertThrows(InterruptedIOException::class.java) {
            AdbSync.push(
                openChannel = { channel },
                source = content.inputStream(),
                size = content.size.toLong(),
                remotePath = "/sdcard/Download/x.bin",
                onProgress = { sent -> if (sent == content.size.toLong()) cancelled = true },
                isCancelled = { cancelled },
            )
        }
        assertTrue("The final progress callback was not reached", cancelled)
        assertArrayEqualsMessage(content, dataPayload(channel.written.toByteArray()))
        assertEquals(-1, channel.written.toByteArray().indexOf("DONE"))
    }

    @Test fun anAlreadyCancelledEmptyFileDoesNotStartTheRemoteWrite() {
        val channel = FakeChannel(okay())
        assertThrows(InterruptedIOException::class.java) {
            AdbSync.push(
                openChannel = { channel },
                source = ByteArray(0).inputStream(),
                size = 0,
                remotePath = "/sdcard/Download/empty.bin",
                isCancelled = { true },
            )
        }
        assertEquals("A cancelled transfer must not send even a SEND header", 0, channel.written.size())
    }

    @Test fun cancellingDuringTheEndOfFileCheckDoesNotCommitTheFile() {
        val channel = FakeChannel(okay())
        val content = byteArrayOf(1, 2, 3)
        val delegate = content.inputStream()
        var cancelled = false
        val source = object : InputStream() {
            override fun read(): Int = throw AssertionError("Expected a bulk read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                delegate.read(bytes, offset, length).also { if (it < 0) cancelled = true }
        }
        assertThrows(InterruptedIOException::class.java) {
            AdbSync.push(
                openChannel = { channel },
                source = source,
                size = content.size.toLong(),
                remotePath = "/sdcard/Download/x.bin",
                isCancelled = { cancelled },
            )
        }
        assertTrue("The end-of-file check was not reached", cancelled)
        assertArrayEqualsMessage(content, dataPayload(channel.written.toByteArray()))
        assertEquals(-1, channel.written.toByteArray().indexOf("DONE"))
    }

    @Test fun aShortFileIsReportedRatherThanSilentlyTruncated() {
        val failure = assertThrows(IOException::class.java) {
            AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(ByteArray(10)), 5_000L,
                "/sdcard/Download/x.bin")
        }
        assertTrue(failure.message, failure.message!!.contains("ended after"))
    }

    @Test fun aFileLongerThanItsReportedSizeIsReportedRatherThanSilentlyCutOff() {
        // Some providers answer zero, or a stale value, for a file's size. Trusting it and
        // stopping there left a truncated copy on the device and still called it a success.
        for (reported in listOf(0L, 4L)) {
            val failure = assertThrows(IOException::class.java) {
                AdbSync.push({ FakeChannel(okay()) }, ByteArrayInputStream(ByteArray(10)), reported,
                    "/sdcard/Download/x.bin")
            }
            assertTrue(failure.message, failure.message!!.contains("larger than"))
        }
    }

    @Test fun adbdsRefusalIsSurfacedWithItsOwnMessage() {
        val failure = assertThrows(IOException::class.java) {
            AdbSync.push({ FakeChannel(fail("Read-only file system")) }, ByteArrayInputStream(ByteArray(4)),
                4L, "/sdcard/Download/x.bin")
        }
        assertTrue(failure.message, failure.message!!.contains("Read-only file system"))
    }

    @Test fun anEmptyFileStillCompletesTheTransfer() {
        val channel = FakeChannel(okay())
        AdbSync.push({ channel }, ByteArrayInputStream(ByteArray(0)), 0L, "/sdcard/Download/x.bin")
        assertTrue(channel.written.toByteArray().indexOf("DONE") > 0)
    }

    @Test fun remoteNamesCannotEscapeTheDownloadFolder() {
        // The name reaches both the sync SEND header and a SCAN_FILE path, so it must stay a
        // single path segment. An embedded ".." is harmless once separators are gone; what must
        // never survive is a separator, a leading dot, or a bare parent reference.
        for (name in listOf("../../etc/passwd", "a/b/c.txt", "..", ".", "./.ssh/id_rsa", "\\..\\x")) {
            val path = RemoteFileName.remotePath(name)
            assertTrue(path, path.startsWith("${RemoteFileName.TARGET_DIRECTORY}/"))
            val bare = path.removePrefix("${RemoteFileName.TARGET_DIRECTORY}/")
            assertFalse(path, bare.contains('/'))
            assertFalse(path, bare.contains('\\'))
            assertFalse(path, bare.startsWith("."))
            assertFalse(path, bare == ".." || bare == ".")
            assertTrue(path, bare.isNotEmpty())
        }
    }

    @Test fun aTransferNeverLandsOnAFileThatIsAlreadyThere() {
        // adbd empties the destination as a transfer begins and removes it again if that transfer
        // fails, so sending to a name already in use meant cancelling an upload — or losing the
        // network partway — destroyed whatever was on the device under that name, leaving neither
        // file. A name nothing is using is chosen instead, so the bytes never go near the old one.
        val present = mutableSetOf(
            "${RemoteFileName.TARGET_DIRECTORY}/probe.txt",
            "${RemoteFileName.TARGET_DIRECTORY}/probe (1).txt",
        )
        val chosen = RemoteFileName.freePath("probe.txt") { it in present }
        assertEquals("${RemoteFileName.TARGET_DIRECTORY}/probe (2).txt", chosen)

        // The extension is what decides which app opens the file, so the number goes before it.
        assertTrue(chosen, chosen.endsWith(".txt"))

        // Nothing there: the name the user recognises is used unchanged.
        assertEquals("${RemoteFileName.TARGET_DIRECTORY}/probe.txt",
            RemoteFileName.freePath("probe.txt") { false })
    }

    @Test fun aDeviceThatWillNotSayWhetherAFileExistsIsNotWrittenOver() {
        val probed = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            RemoteFileName.freePath("probe.txt") {
                probed += it
                null
            }
        }
        assertEquals("The search must remain bounded", 100, probed.size)
        assertEquals(100, probed.distinct().size)
    }

    @Test fun exhaustingEveryNumberedCandidateFailsInsteadOfReplacingTheLastExistingFile() {
        val present = (0..99).map { number ->
            val name = if (number == 0) "probe.txt" else "probe ($number).txt"
            "${RemoteFileName.TARGET_DIRECTORY}/$name"
        }
        val probed = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            RemoteFileName.freePath("probe.txt") {
                probed += it
                it in present
            }
        }
        assertEquals(present, probed)
    }

    @Test fun anUnknownCandidateIsSkippedUntilANameIsConfirmedFree() {
        var checked = 0
        val chosen = RemoteFileName.freePath("probe.txt") {
            if (checked++ == 0) null else false
        }
        assertEquals("${RemoteFileName.TARGET_DIRECTORY}/probe (1).txt", chosen)
        assertEquals(2, checked)
    }

    @Test fun theLastNumberedCandidateIsUsableWhenItIsConfirmedFree() {
        var checked = 0
        val chosen = RemoteFileName.freePath("probe.txt") { checked++ < 99 }
        assertEquals("${RemoteFileName.TARGET_DIRECTORY}/probe (99).txt", chosen)
        assertEquals(100, checked)
    }

    @Test fun aChosenNameStaysInsideTheDownloadFolderHoweverItIsNumbered() {
        for (name in listOf("../../etc/passwd", ". .nomedia", "a/b/c.txt", "x")) {
            var checked = 0
            val chosen = RemoteFileName.freePath(name) { checked++ < 3 }
            assertTrue(chosen, chosen.startsWith("${RemoteFileName.TARGET_DIRECTORY}/"))
            val bare = chosen.removePrefix("${RemoteFileName.TARGET_DIRECTORY}/")
            assertFalse(chosen, bare.contains('/'))
            assertFalse(chosen, bare.contains('\\'))
            assertFalse(chosen, bare.startsWith("."))
            assertTrue(chosen, bare.isNotEmpty())
        }
    }

    @Test fun aDotSeparatedBySpacesCannotSmuggleInAHiddenFile() {
        // Dots were stripped before the final trim, so a name whose leading dot sat behind a
        // space had it uncovered again afterwards. ".nomedia" in particular would have told the
        // media scanner to ignore the whole Download folder.
        for (name in listOf(". .nomedia", ".  .hidden", " . . .x", ".\t.nomedia")) {
            val bare = RemoteFileName.sanitize(name)
            assertFalse(name + " -> " + bare, bare.startsWith("."))
            assertTrue(name + " -> " + bare, bare.isNotEmpty())
        }
    }

    @Test fun ordinaryNamesSurviveUnchangedAndOddCharactersAreReplaced() {
        assertEquals("Report 2024.pdf", RemoteFileName.sanitize("Report 2024.pdf"))
        assertEquals("a_b.txt", RemoteFileName.sanitize("a:b.txt"))
        assertEquals("shared-file", RemoteFileName.sanitize(""))
        assertEquals("shared-file", RemoteFileName.sanitize("..."))
    }

    @Test fun aVeryLongNameKeepsItsExtensionSoItStillOpens() {
        val long = "x".repeat(400) + ".pdf"
        val sanitized = RemoteFileName.sanitize(long)
        assertTrue(sanitized, sanitized.endsWith(".pdf"))
        assertTrue(sanitized, sanitized.length <= 100)
    }

    @Test fun aVeryLongNameIsBoundedByFilesystemBytesRatherThanCharacters() {
        // ext4 and FAT cap a name at 255 bytes, and these characters cost three each. A limit
        // counted in characters would let an ordinary name fail to create on the device.
        val long = "文".repeat(90) + ".pdf"
        val sanitized = RemoteFileName.sanitize(long)
        assertTrue(sanitized, sanitized.endsWith(".pdf"))
        assertTrue(sanitized, sanitized.toByteArray(Charsets.UTF_8).size <= 100)
        // Truncation never cuts a character in half.
        assertEquals(sanitized, String(sanitized.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test fun sizesAreFormattedForPeopleRatherThanInRawBytes() {
        assertEquals("512 B", FileTransfers.formatSize(512))
        assertEquals("1.0 KB", FileTransfers.formatSize(1024))
        assertEquals("1.5 MB", FileTransfers.formatSize(1024L * 1024 * 3 / 2))
        assertEquals("512 MB", FileTransfers.formatSize(FileTransfers.MAX_BYTES))
        assertEquals("100 MB", FileTransfers.formatSize(104_815_000L)) // 99.96 MB must not print 100.0
        assertEquals("99.8 MB", FileTransfers.formatSize(104_700_000L))
    }

    private fun assertArrayEqualsMessage(expected: ByteArray, actual: ByteArray) {
        assertEquals("Transferred byte count", expected.size, actual.size)
        val differing = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("First differing byte index", null, differing)
    }
}
