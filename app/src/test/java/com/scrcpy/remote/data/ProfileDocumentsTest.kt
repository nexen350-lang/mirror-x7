package com.scrcpy.remote.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.util.UUID

class ProfileDocumentsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun boundedReadAcceptsExactLimitButRejectsExtraBytes() {
        val bytes = ByteArray(ProfileBackupCodec.MAX_BYTES) { 42 }
        assertArrayEquals(bytes, readProfileBytes(bytes.inputStream()))
        assertThrows(IOException::class.java) { readProfileBytes(ByteArray(bytes.size + 1).inputStream()) }
    }

    @Test fun inputStallIsAnErrorNotAnInfiniteLoop() {
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        assertThrows(IOException::class.java) { readProfileBytes(input) }
    }

    @Test fun readChecksCancellationBetweenChunks() {
        var checks = 0
        assertThrows(InterruptedException::class.java) {
            readProfileBytes(ByteArrayInputStream(ByteArray(32 * 1024))) {
                if (++checks == 2) throw InterruptedException()
            }
        }
        assertEquals(2, checks)
    }

    @Test fun expiredFilesAreRejectedAndPrunedWithoutRemovingUnrelatedFiles() {
        var now = System.currentTimeMillis()
        val cache = ProfileTransferCache(folder.root) { now }
        val token = cache.create(byteArrayOf(1))
        val file = File(folder.root, "profile-transfers/$token.json")
        val unrelated = File(file.parentFile, "unrelated.txt").apply { writeText("retain") }
        now = file.lastModified() + 86_400_001L
        assertThrows(IOException::class.java) { cache.read(token) }
        cache.create(byteArrayOf(2))
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun emptyAndOversizedCachedFilesAreNotRestored() {
        val cache = ProfileTransferCache(folder.root)
        val token = cache.create(byteArrayOf(1))
        val file = File(folder.root, "profile-transfers/$token.json")
        file.writeBytes(byteArrayOf())
        assertThrows(IOException::class.java) { cache.read(token) }
        file.writeBytes(ByteArray(ProfileBackupCodec.MAX_BYTES + 1))
        assertThrows(IOException::class.java) { cache.read(token) }
    }

    @Test fun opaqueTokensRestoreOnlyOurPrivateBoundedFiles() {
        val cache = ProfileTransferCache(folder.root)
        val content = ProfileBackupCodec.encode(ConfigurationSnapshot(emptyList(), MirrorSettings()))
        val token = cache.create(content)
        assertEquals(token, UUID.fromString(token).toString())
        assertArrayEquals(content, cache.read(token))
        for (invalid in listOf("../file", "..\\file", folder.root.absolutePath, "$token.json")) {
            assertThrows(IOException::class.java) { cache.read(invalid) }
            assertThrows(IOException::class.java) { cache.discard(invalid) }
        }
        cache.discard(token)
        assertThrows(IOException::class.java) { cache.read(token) }
    }

    @Test fun discardRemovesThePlaintextFileAndSweepRemovesExpiredOnes() {
        var now = System.currentTimeMillis()
        val cache = ProfileTransferCache(folder.root) { now }
        val kept = cache.create(byteArrayOf(1))
        cache.discard(kept)
        assertFalse(File(folder.root, "profile-transfers/$kept.json").exists())
        val old = cache.create(byteArrayOf(2))
        now += 2 * 86_400_000L // files are stamped with the real clock, so leave a wide margin
        cache.sweep()
        assertFalse(File(folder.root, "profile-transfers/$old.json").exists())
    }
}
