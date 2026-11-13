package com.scrcpy.remote

import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.scrcpy.ServerDeployment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class ServerFastVerificationTest {
    private val serverBytes = "scrcpy-test-server".toByteArray()
    private val path = "/data/local/tmp/scrcpy-server-test.jar"

    @Test fun skipsRecvAndPushWhenRemoteSha256MatchesExactSizeAndDigest() {
        val commands = mutableListOf<String>()
        val pushed = AtomicInteger(0)
        val deployment = ServerDeployment(path, sha256(serverBytes))

        val didPush = deployment.ensureDeployed(
            serverBytes = serverBytes,
            openSync = { fakeSync(serverBytes, commands) },
            remoteSha256 = { sha256(serverBytes) },
            onPush = { pushed.incrementAndGet() },
        )

        assertFalse(didPush)
        assertEquals(0, pushed.get())
        assertEquals(listOf("STAT"), commands)
    }

    @Test fun fallsBackToRecvWhenFastSha256IsUnavailable() {
        val commands = mutableListOf<String>()
        val deployment = ServerDeployment(path, sha256(serverBytes))

        val didPush = deployment.ensureDeployed(
            serverBytes = serverBytes,
            openSync = { fakeSync(serverBytes, commands) },
            remoteSha256 = { null },
        )

        assertFalse(didPush)
        assertEquals(listOf("STAT", "RECV"), commands)
    }

    @Test fun parsesSha256SumOutputStrictly() {
        val validDigest = "a".repeat(64)
        assertEquals(validDigest, ScrcpySession.parseSha256SumOutput("$validDigest  $path\n", path))
        assertEquals(validDigest, ScrcpySession.parseSha256SumOutput("${validDigest.uppercase()}  $path", path))
        assertNull(ScrcpySession.parseSha256SumOutput("$validDigest  /data/local/tmp/other.jar", path))
        assertNull(ScrcpySession.parseSha256SumOutput("sha256sum: not found", path))
        assertNull(ScrcpySession.parseSha256SumOutput("", path))
    }

    private fun fakeSync(remoteBytes: ByteArray, commands: MutableList<String>): SyncChannel {
        val out = ByteArrayOutputStream()
        return object : SyncChannel {
            override val output = out
            override val input = object : InputStream() {
                private var reply: InputStream? = null
                private fun ensureReply(): InputStream {
                    reply?.let { return it }
                    val req = out.toByteArray()
                    val cmd = String(req, 0, 4, Charsets.US_ASCII)
                    commands += cmd
                    val bytes = when (cmd) {
                        "STAT" -> frame("STAT", 0b1000_000_110_100_100) +
                            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(remoteBytes.size).putInt(0).array()
                        "RECV" -> frame("DATA", remoteBytes.size) + remoteBytes + frame("DONE", 0)
                        else -> throw AssertionError("Unexpected sync command: $cmd")
                    }
                    return bytes.inputStream().also { reply = it }
                }

                override fun read(): Int = ensureReply().read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = ensureReply().read(b, off, len)
            }

            override fun close() = Unit
        }
    }

    private fun frame(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
