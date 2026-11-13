package io.github.muntashirakon.adb

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * How the ADB client treats a device that sends a stream's last output without waiting to be
 * asked: Android 16's adbd does, as a shell command ends right after a slow step, and the client
 * used to end the whole connection over it, taking the mirroring session with it.
 *
 * A small adbd on a local socket speaks the wire protocol: the handshake, an OPEN's OKAY, and
 * WRTE and CLSE packets in exactly the order a test asks for.
 */
class AdbFlowControlTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val connections = mutableListOf<AdbConnection>()

    @After fun closeEverything() {
        connections.forEach { runCatching { it.close() } }
        server.close()
    }

    @Test(timeout = 30_000) fun aStreamsLastOutputSentWithoutCreditIsReadAndTheConnectionLives() {
        val sent = CountDownLatch(1)
        val device = thread(name = "fake-adbd") {
            server.accept().use { socket ->
                val wire = Wire(socket)
                wire.handshake()
                val shell = wire.expect(AdbProtocol.A_OPEN)
                wire.send(AdbProtocol.A_OKAY, REMOTE, shell.arg0)
                // The first packet is credited by the OPEN; the last one comes before any OKAY,
                // and the stream ends.
                wire.send(AdbProtocol.A_WRTE, REMOTE, shell.arg0, "locales=null\n".toByteArray())
                wire.send(AdbProtocol.A_WRTE, REMOTE, shell.arg0, "locale=\nproduct=en-US\n".toByteArray())
                wire.send(AdbProtocol.A_CLSE, REMOTE, shell.arg0)
                sent.countDown()
                // The connection is still there for the next command.
                val next = wire.expectSkippingAcknowledgements(AdbProtocol.A_OPEN)
                wire.send(AdbProtocol.A_OKAY, REMOTE + 1, next.arg0)
                wire.send(AdbProtocol.A_WRTE, REMOTE + 1, next.arg0, "next\n".toByteArray())
                wire.send(AdbProtocol.A_CLSE, REMOTE + 1, next.arg0)
                wire.drain()
            }
        }
        val connection = connect()
        val stream = connection.open("shell:echo")
        assertTrue("The device sent both packets and closed", sent.await(10, TimeUnit.SECONDS))
        // The client reads nothing until both have arrived, as a reader still waking up would.
        Thread.sleep(300)
        assertEquals("locales=null\nlocale=\nproduct=en-US\n", readAll(stream))
        assertEquals("next\n", readAll(connection.open("shell:echo")))
        connection.close()
        device.join(10_000)
    }

    @Test(timeout = 30_000) fun aDeviceThatKeepsSendingUnaskedStillCannotFillThisPhonesMemory() {
        val floodDone = java.util.concurrent.CountDownLatch(1)
        val device = thread(name = "fake-adbd") {
            server.accept().use { socket ->
                val wire = Wire(socket)
                wire.handshake()
                val shell = wire.expect(AdbProtocol.A_OPEN)
                wire.send(AdbProtocol.A_OKAY, REMOTE, shell.arg0)
                val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
                // Far more than any last output: a device ignoring flow control altogether.
                runCatching { repeat(256) { wire.send(AdbProtocol.A_WRTE, REMOTE, shell.arg0, chunk) } }
                // A client that kept it all would read it to the end and find nothing wrong.
                runCatching { wire.send(AdbProtocol.A_CLSE, REMOTE, shell.arg0) }
                floodDone.countDown()
                wire.drain()
            }
        }
        val connection = connect()
        val stream = connection.open("shell:flood")
        // Nothing is read until the device has had every chance to overrun the client: wait for
        // it to finish sending (not asserted: a blocked send is also a valid outcome), then a
        // short grace period for the client's reader to process what arrived.
        floodDone.await(15, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(500)
        assertThrows(IOException::class.java) { readAll(stream) }
        connection.close()
        device.join(10_000)
    }

    @Test(timeout = 30_000) fun aLongStreamReadAsItArrivesIsNeverCutOff() {
        // Four times the bound in all, each packet sent only once the last was acknowledged: what
        // the reader has taken no longer counts.
        val chunk = ByteArray(64 * 1024) { 'y'.code.toByte() }
        val packets = 128
        val device = thread(name = "fake-adbd") {
            server.accept().use { socket ->
                val wire = Wire(socket)
                wire.handshake()
                val shell = wire.expect(AdbProtocol.A_OPEN)
                wire.send(AdbProtocol.A_OKAY, REMOTE, shell.arg0)
                repeat(packets) { index ->
                    if (index > 0) wire.expect(AdbProtocol.A_OKAY)
                    wire.send(AdbProtocol.A_WRTE, REMOTE, shell.arg0, chunk)
                }
                wire.send(AdbProtocol.A_CLSE, REMOTE, shell.arg0)
                wire.drain()
            }
        }
        val connection = connect()
        assertEquals(packets * chunk.size, readAll(connection.open("shell:cat")).length)
        connection.close()
        device.join(10_000)
    }
    private fun connect(): AdbConnection {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // Only a device that asks for TLS reads the certificate, and this one never does.
        val certificate = object : Certificate("X.509") {
            override fun getEncoded(): ByteArray = ByteArray(0)
            override fun verify(key: PublicKey) = Unit
            override fun verify(key: PublicKey, sigProvider: String) = Unit
            override fun toString(): String = "test certificate"
            override fun getPublicKey(): PublicKey = keys.public
        }
        val connection = AdbConnection.create("127.0.0.1", server.localPort, keys.private, certificate, API)
        connections += connection
        assertTrue(connection.connect(10, TimeUnit.SECONDS, false))
        return connection
    }

    private fun readAll(stream: AdbStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val input = stream.openInputStream()
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (end: IOException) {
                // A stream the device closed ends this way once everything sent has been read.
                if (end.message == "Stream closed.") break else throw end
            }
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** One side of the ADB wire protocol, as adbd speaks it. */
    private class Wire(socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()

        class Packet(val command: Int, val arg0: Int)

        fun handshake() {
            expect(AdbProtocol.A_CNXN)
            send(AdbProtocol.A_CNXN, VERSION, MAX_DATA, "device::\u0000".toByteArray())
        }

        fun expect(command: Int): Packet = read().also {
            check(it.command == command) { "Expected 0x%x, got 0x%x".format(command, it.command) }
        }

        /** The client's OKAYs and CLSEs for packets already sent may come first. */
        fun expectSkippingAcknowledgements(command: Int): Packet {
            while (true) {
                val packet = read()
                if (packet.command == AdbProtocol.A_OKAY || packet.command == AdbProtocol.A_CLSE) continue
                check(packet.command == command) { "Expected 0x%x, got 0x%x".format(command, packet.command) }
                return packet
            }
        }

        fun drain() {
            try { while (true) read() } catch (_: IOException) { }
        }

        fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 0xFF }).putInt(command.inv())
            synchronized(output) {
                output.write(header.array())
                output.write(payload)
                output.flush()
            }
        }

        private fun read(): Packet {
            val header = ByteArray(24)
            input.readFully(header)
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = fields.int
            val arg0 = fields.int
            fields.int
            val length = fields.int
            input.readFully(ByteArray(length))
            return Packet(command, arg0)
        }
    }

    private companion object {
        const val API = 36
        const val REMOTE = 1359
        const val VERSION = 0x01000001
        const val MAX_DATA = 256 * 1024
    }
}
