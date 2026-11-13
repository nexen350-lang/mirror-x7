package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.adb.SyncChannel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ServerIntegrityTest {
    private fun frame(id: String, n: Int) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .put(id.toByteArray(Charsets.US_ASCII)).putInt(n).array()
    private fun channel(bytes: ByteArray) = object : SyncChannel {
        override val input = bytes.inputStream()
        override val output = ByteArrayOutputStream()
        override fun close() = Unit
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun verifiesContentNotJustSize() {
        val content = byteArrayOf(1, 2, 3, 4)
        val stream = frame("DATA", 2) + content.copyOfRange(0, 2) +
            frame("DATA", 2) + content.copyOfRange(2, 4) + frame("DONE", 0)
        val result = AdbSync.sha256({ channel(stream) }, "/data/local/tmp/server.jar", 4)
        assertEquals(sha(content), result)
        assertNotEquals(sha(byteArrayOf(1, 2, 3, 5)), result)
    }
    @Test(expected = IOException::class) fun shortServerCannotBeReused() {
        AdbSync.sha256({ channel(frame("DATA", 1) + byteArrayOf(1) + frame("DONE", 0)) }, "/tmp/x", 2)
    }
    @Test(expected = IOException::class) fun oversizedReplyIsRejected() {
        AdbSync.sha256({ channel(frame("DATA", 65537)) }, "/tmp/x", 65537)
    }
}
