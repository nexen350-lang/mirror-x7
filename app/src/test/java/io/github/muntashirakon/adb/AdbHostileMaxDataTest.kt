package io.github.muntashirakon.adb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A hostile adbd's CNXN max-data must neither be trusted as an allocation size nor be unusable. */
class AdbHostileMaxDataTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val connections = mutableListOf<AdbConnection>()

    @After fun closeEverything() {
        connections.forEach { runCatching { it.close() } }
        server.close()
    }

    @Test(timeout = 30_000) fun hugeMaxDataIsClampedToTheHostsOwnMaximum() {
        val connection = connectTo(Int.MAX_VALUE)
        assertTrue(connection.isConnectionEstablished)
        assertEquals(AdbProtocol.getMaxData(API), connection.maxData)
    }

    @Test(timeout = 30_000) fun smallerMaxDataIsKept() {
        assertEquals(4096, connectTo(4096).maxData)
    }

    @Test(timeout = 30_000) fun zeroMaxDataFailsTheConnection() = assertRefused(0)

    @Test(timeout = 30_000) fun negativeMaxDataFailsTheConnection() = assertRefused(-1)

    private fun assertRefused(maxData: Int) {
        val established = try {
            connectTo(maxData).isConnectionEstablished
        } catch (_: java.io.IOException) {
            false
        }
        assertFalse("A peer announcing max data $maxData must not establish a connection", established)
    }

    private fun connectTo(maxData: Int): AdbConnection {
        thread(name = "fake-adbd", isDaemon = true) {
            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val header = ByteArray(24)
                input.readFully(header)
                input.readFully(ByteArray(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)))
                val payload = "device::\u0000".toByteArray()
                val reply = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(AdbProtocol.A_CNXN).putInt(VERSION).putInt(maxData).putInt(payload.size)
                    .putInt(payload.sumOf { it.toInt() and 0xFF }).putInt(AdbProtocol.A_CNXN.inv())
                socket.getOutputStream().apply { write(reply.array()); write(payload); flush() }
                try { while (true) input.readByte() } catch (_: java.io.IOException) { }
            }
        }
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = object : Certificate("X.509") {
            override fun getEncoded(): ByteArray = ByteArray(0)
            override fun verify(key: PublicKey) = Unit
            override fun verify(key: PublicKey, sigProvider: String) = Unit
            override fun toString(): String = "test certificate"
            override fun getPublicKey(): PublicKey = keys.public
        }
        val connection = AdbConnection.create("127.0.0.1", server.localPort, keys.private, certificate, API)
        connections += connection
        connection.connect(10, TimeUnit.SECONDS, false)
        return connection
    }

    private companion object {
        const val API = 36
        const val VERSION = 0x01000001
    }
}
