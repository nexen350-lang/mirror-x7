package com.scrcpy.remote

import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.session.ApkInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Installing an app: the package is copied into the shell's temporary folder, the device's package
 * manager installs it from there, and the copy is removed whatever happened, so nothing an
 * install leaves behind piles up on the device.
 */
class ScrcpyInstallTest {
    private class ReplyChannel(reply: ByteArray) : SyncChannel {
        val written = ByteArrayOutputStream()
        val closes = AtomicInteger()
        override val input = reply.inputStream()
        override val output = written
        override fun close() { closes.incrementAndGet() }
    }

    private val path = "/data/local/tmp/scrcpy-remote-install-0123456789abcdef0123456789abcdef.apk"

    private class Shell(private val answer: (String) -> String) {
        val commands = mutableListOf<Pair<String, Long>>()
        fun run(command: String, timeoutMs: Long): String {
            commands += command to timeoutMs
            return answer(command)
        }
    }

    @Test fun theAppIsCopiedThenInstalledThenItsCopyRemoved() {
        val push = ReplyChannel(frame("OKAY", 0))
        val events = mutableListOf<String>()
        val shell = Shell { command -> events += command.substringBefore(' '); if (command.startsWith("pm ")) "Success\n" else "" }
        val progress = mutableListOf<Long>()
        val answer = ScrcpySession.installPackage(
            openSync = { events += "open"; push },
            runShell = shell::run,
            source = byteArrayOf(1, 2, 3).inputStream(),
            size = 3,
            remotePath = path,
            onProgress = { progress += it },
            onInstalling = { events += "installing" },
        )
        assertEquals("Success\n", answer)
        assertEquals(listOf("find", "open", "installing", "pm", "rm"), events)
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.installCommand(path), ApkInstall.removeCommand(path)),
            shell.commands.map { it.first })
        assertTrue("Installing can take minutes on a slow device: ${shell.commands}",
            shell.commands[1].second >= 5 * 60_000L)
        assertTrue("Tidying must not hold the slot for long: ${shell.commands}",
            shell.commands[0].second <= 15_000L && shell.commands[2].second <= 15_000L)
        assertEquals(listOf(3L), progress)
        val sent = push.written.toByteArray()
        assertEquals("SEND", String(sent, 0, 4, Charsets.US_ASCII))
        val target = "$path,${0b110_100_100}"
        assertEquals(target, String(sent, 8, target.length, Charsets.UTF_8))
        assertEquals(1, push.closes.get())
    }

    @Test fun theCopyIsRemovedEvenWhenTheInstallAnswerIsLost() {
        val lost = IOException("Stream closed")
        val shell = Shell { command -> if (command.startsWith("pm ")) throw lost else "" }
        val failure = assertThrows(IOException::class.java) {
            ScrcpySession.installPackage(
                openSync = { ReplyChannel(frame("OKAY", 0)) },
                runShell = shell::run,
                source = byteArrayOf(1).inputStream(),
                size = 1,
                remotePath = path,
            )
        }
        assertSame(lost, failure)
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.installCommand(path), ApkInstall.removeCommand(path)),
            shell.commands.map { it.first })
    }

    @Test fun aRefusedCopyIsNeverInstalledButStillCleanedUp() {
        val message = "No space left on device".toByteArray()
        val push = ReplyChannel(frame("FAIL", message.size) + message)
        var installing = 0
        val shell = Shell { "" }
        assertThrows(IOException::class.java) {
            ScrcpySession.installPackage(
                openSync = { push },
                runShell = shell::run,
                source = byteArrayOf(1, 2).inputStream(),
                size = 2,
                remotePath = path,
                onInstalling = { installing++ },
            )
        }
        assertEquals(0, installing)
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.removeCommand(path)), shell.commands.map { it.first })
    }

    @Test fun anInstallCancelledBeforeItStartsTouchesNothing() {
        var opened = 0
        val shell = Shell { "" }
        assertThrows(InterruptedIOException::class.java) {
            ScrcpySession.installPackage(
                openSync = { opened++; ReplyChannel(frame("OKAY", 0)) },
                runShell = shell::run,
                source = byteArrayOf(1).inputStream(),
                size = 1,
                remotePath = path,
                isCancelled = { true },
            )
        }
        assertEquals(0, opened)
        assertEquals(emptyList<Pair<String, Long>>(), shell.commands)
    }

    @Test fun cancellingAsTheCopyEndsStopsBeforeInstalling() {
        var cancelled = false
        var installing = 0
        val push = ReplyChannel(frame("OKAY", 0))
        val shell = Shell { "" }
        assertThrows(InterruptedIOException::class.java) {
            ScrcpySession.installPackage(
                openSync = { push },
                runShell = shell::run,
                source = byteArrayOf(1, 2, 3).inputStream(),
                size = 3,
                remotePath = path,
                onProgress = { if (it == 3L) cancelled = true },
                isCancelled = { cancelled },
                onInstalling = { installing++ },
            )
        }
        assertEquals(0, installing)
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.removeCommand(path)), shell.commands.map { it.first })
        val sent = push.written.toByteArray()
        assertTrue("A cancelled copy must never be committed with DONE",
            String(sent, Charsets.ISO_8859_1).indexOf("DONE") < 0)
    }

    @Test fun aCleanupFailureDoesNotUndoAnInstall() {
        val shell = Shell { command -> if (command.startsWith("rm ")) throw IOException("gone") else "Success\n" }
        val reported = mutableListOf<Exception>()
        val answer = ScrcpySession.installPackage(
            openSync = { ReplyChannel(frame("OKAY", 0)) },
            runShell = shell::run,
            source = byteArrayOf(1).inputStream(),
            size = 1,
            remotePath = path,
            onCleanupFailed = { reported += it },
        )
        assertEquals("Success\n", answer)
        assertEquals(listOf("gone"), reported.map { it.message })
    }

    @Test fun aCleanupFailureDoesNotHideWhyTheInstallFailed() {
        val lost = IOException("Stream closed")
        val shell = Shell { command ->
            when {
                command.startsWith("rm ") -> throw IOException("gone")
                command.startsWith("pm ") -> throw lost
                else -> ""
            }
        }
        val reported = mutableListOf<Exception>()
        val failure = assertThrows(IOException::class.java) {
            ScrcpySession.installPackage(
                openSync = { ReplyChannel(frame("OKAY", 0)) },
                runShell = shell::run,
                source = byteArrayOf(1).inputStream(),
                size = 1,
                remotePath = path,
                onCleanupFailed = { reported += it },
            )
        }
        assertSame(lost, failure)
        assertEquals(listOf("gone"), reported.map { it.message })
    }

    @Test fun aFailedSweepDoesNotStopTheInstall() {
        // An old device without find, or one that refuses it, still installs.
        val shell = Shell { command -> if (command.startsWith("find ")) throw IOException("no find") else "Success\n" }
        val reported = mutableListOf<Exception>()
        val answer = ScrcpySession.installPackage(
            openSync = { ReplyChannel(frame("OKAY", 0)) },
            runShell = shell::run,
            source = byteArrayOf(1).inputStream(),
            size = 1,
            remotePath = path,
            onCleanupFailed = { reported += it },
        )
        assertEquals("Success\n", answer)
        assertEquals(listOf("no find"), reported.map { it.message })
        assertEquals(3, shell.commands.size)
    }

    private fun frame(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()
}
