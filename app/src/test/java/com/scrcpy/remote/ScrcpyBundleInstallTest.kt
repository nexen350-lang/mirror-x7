package com.scrcpy.remote

import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.session.ApkInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Installing an app bundle: its files are copied into the shell's temporary folder and written into
 * one install session of the device's package manager, which installs them together or not at all.
 * A session that cannot be finished is abandoned, and the copies go whatever happened.
 */
class ScrcpyBundleInstallTest {
    private class ReplyChannel : SyncChannel {
        val written = ByteArrayOutputStream()
        override val input = frame("OKAY", 0).inputStream()
        override val output = written
        override fun close() = Unit
    }

    private class Shell(private val answer: (String) -> String) {
        val commands = mutableListOf<Pair<String, Long>>()
        fun run(command: String, timeoutMs: Long): String {
            commands += command to timeoutMs
            return answer(command)
        }
    }

    private val token = "0123456789abcdef0123456789abcdef"
    private val parts = listOf(
        ScrcpySession.UploadPart(3) { byteArrayOf(1, 2, 3).inputStream() },
        ScrcpySession.UploadPart(2) { byteArrayOf(4, 5).inputStream() },
    )

    private fun answers(write: (String) -> String = { "Success: streamed 3 bytes\n" }, commit: () -> String = { "Success\n" }) =
        Shell { command ->
            when {
                command.startsWith("pm install-create") -> "Success: created install session [752914882]\n"
                command.startsWith("pm install-write") -> write(command)
                command.startsWith("pm install-commit") -> commit()
                command.startsWith("pm install-abandon") -> "Success\n"
                else -> ""
            }
        }

    @Test fun eachFileIsCopiedThenAllAreInstalledInOneSessionThenTheCopiesRemoved() {
        val shell = answers()
        val channels = mutableListOf<ReplyChannel>()
        val events = mutableListOf<String>()
        val progress = mutableListOf<Long>()
        val answer = ScrcpySession.installPackages(
            openSync = { events += "open"; ReplyChannel().also { channels += it } },
            runShell = { command, timeout -> events += command.substringBefore(' '); shell.run(command, timeout) },
            parts = parts,
            token = token,
            onProgress = { progress += it },
            onInstalling = { events += "installing" },
        )
        assertEquals("Success\n", answer)
        assertEquals(listOf("find", "open", "open", "installing", "pm", "pm", "pm", "pm", "rm"), events)
        val first = ApkInstall.partPath(token, 0)
        val second = ApkInstall.partPath(token, 1)
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.createSessionCommand(5),
            ApkInstall.writeCommand(752914882, 0, 3, first), ApkInstall.writeCommand(752914882, 1, 2, second),
            ApkInstall.commitCommand(752914882), ApkInstall.removePartsCommand(token)), shell.commands.map { it.first })
        assertEquals("Progress counts the whole bundle", listOf(3L, 5L), progress)
        for ((channel, path) in channels.zip(listOf(first, second))) {
            val sent = channel.written.toByteArray()
            val target = "$path,${0b110_100_100}"
            assertEquals(target, String(sent, 8, target.length, Charsets.UTF_8))
        }
        val timeouts = shell.commands.toMap()
        assertTrue("Installing can take minutes on a slow device", timeouts.getValue(ApkInstall.commitCommand(752914882)) >= 5 * 60_000L)
        assertTrue("Tidying must not hold the slot for long", timeouts.getValue(ApkInstall.sweepCommand()) <= 15_000L &&
            timeouts.getValue(ApkInstall.removePartsCommand(token)) <= 15_000L)
    }

    @Test fun aFileTheSessionRefusesAbandonsItAndSaysWhy() {
        val refusal = "Error: Unable to open file: ${ApkInstall.partPath(token, 1)}\n"
        val shell = answers(write = { if ("1_part.apk" in it) refusal else "Success: streamed 3 bytes\n" })
        val answer = ScrcpySession.installPackages({ ReplyChannel() }, shell::run, parts, token)
        assertEquals(refusal, answer)
        val commands = shell.commands.map { it.first }
        assertEquals(listOf(ApkInstall.abandonCommand(752914882), ApkInstall.removePartsCommand(token)), commands.takeLast(2))
        assertFalse("Nothing half-written is committed", commands.any { it.startsWith("pm install-commit") })
    }

    @Test fun aSessionThatCannotBeOpenedSaysWhyAndStillRemovesTheCopies() {
        val refusal = "Error: java.lang.SecurityException: User restriction prevents installing\n"
        val shell = Shell { command -> if (command.startsWith("pm install-create")) refusal else "" }
        assertEquals(refusal, ScrcpySession.installPackages({ ReplyChannel() }, shell::run, parts, token))
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.createSessionCommand(5), ApkInstall.removePartsCommand(token)),
            shell.commands.map { it.first })
    }

    @Test fun aConnectionLostWhileWritingAbandonsTheSessionAndRemovesTheCopies() {
        val lost = IOException("Stream closed")
        val shell = answers(write = { if ("1_part.apk" in it) throw lost else "Success: streamed 3 bytes\n" })
        val failure = assertThrows(IOException::class.java) {
            ScrcpySession.installPackages({ ReplyChannel() }, shell::run, parts, token)
        }
        assertSame(lost, failure)
        assertEquals(listOf(ApkInstall.abandonCommand(752914882), ApkInstall.removePartsCommand(token)),
            shell.commands.map { it.first }.takeLast(2))
    }

    @Test fun aCommitWhoseAnswerIsLostIsNotAbandoned() {
        // It may well have finished: the device refuses to abandon a committed session anyway.
        val lost = IOException("Stream closed")
        val shell = answers(commit = { throw lost })
        assertSame(lost, assertThrows(IOException::class.java) {
            ScrcpySession.installPackages({ ReplyChannel() }, shell::run, parts, token)
        })
        val commands = shell.commands.map { it.first }
        assertFalse(commands.any { it.startsWith("pm install-abandon") })
        assertEquals(ApkInstall.removePartsCommand(token), commands.last())
    }

    @Test fun cancellingWhileCopyingStartsNoInstall() {
        var copied = 0
        val shell = answers()
        assertThrows(InterruptedIOException::class.java) {
            ScrcpySession.installPackages({ ReplyChannel() }, shell::run, parts, token,
                onProgress = { copied++ }, isCancelled = { copied > 0 })
        }
        assertEquals(listOf(ApkInstall.sweepCommand(), ApkInstall.removePartsCommand(token)), shell.commands.map { it.first })
    }

    @Test fun theCommandsAreThePackageManagersOwn() {
        assertEquals("/data/local/tmp/scrcpy-remote-install-abc123-1.apk", ApkInstall.partPath("abc123", 1))
        assertEquals("pm install-create -r -t -S 46311", ApkInstall.createSessionCommand(46311))
        assertEquals("pm install-write -S 24938 7 1_part.apk /data/local/tmp/scrcpy-remote-install-abc123-1.apk",
            ApkInstall.writeCommand(7, 1, 24938, ApkInstall.partPath("abc123", 1)))
        assertEquals("pm install-commit 7", ApkInstall.commitCommand(7))
        assertEquals("pm install-abandon 7", ApkInstall.abandonCommand(7))
        assertEquals("rm -f /data/local/tmp/scrcpy-remote-install-abc123-*.apk", ApkInstall.removePartsCommand("abc123"))
        assertEquals("A path that needs quoting is quoted", "pm install-write -S 1 7 0_part.apk ${ScrcpyOptions.shellQuote("/tmp/a b.apk")}",
            ApkInstall.writeCommand(7, 0, 1, "/tmp/a b.apk"))
        // As Android 8 and 16 answer.
        assertEquals(752914882, ApkInstall.sessionId("Success: created install session [752914882]\n"))
        assertEquals(42, ApkInstall.sessionId("Success: created install session [42]\r\n"))
        assertNull(ApkInstall.sessionId("Error: java.lang.SecurityException: Caller has no access to session 7\n"))
        assertNull(ApkInstall.sessionId("Failure [INSTALL_FAILED_INTERNAL_ERROR] [7]\n"))
        assertNull(ApkInstall.sessionId(""))
        assertTrue(ApkInstall.wrote("Success: streamed 24938 bytes\n"))
        assertFalse(ApkInstall.wrote("Error: failed to write; /data/local/tmp/x.apk (No such file or directory)\n"))
        assertFalse(ApkInstall.wrote("Error: Unable to open file: /data/local/tmp/x.apk\n"))
        assertFalse(ApkInstall.wrote(""))
        // A stranded bundle copy is swept like a single app's.
        val token = ApkInstall.newToken()
        assertTrue(token, token.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(token, ApkInstall.newToken())
        assertTrue(ApkInstall.sweepCommand(), ApkInstall.sweepCommand().contains("-name 'scrcpy-remote-install-*.apk'"))
        assertTrue(ApkInstall.partPath(token, 9).substringAfterLast('/').let { it.startsWith("scrcpy-remote-install-") && it.endsWith(".apk") })
    }

    private companion object {
        fun frame(id: String, value: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()
    }
}
