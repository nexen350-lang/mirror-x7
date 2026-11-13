package com.scrcpy.remote.session

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ConnectionFailureStateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun aClosedTcpPortReportsConnectionRefused() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val observed = checkFailure(port, R.string.error_connection_refused)
        // The technical line is kept under Details so it stays useful for reports without cluttering the error text.
        val detail = requireNotNull(observed.failed.details ?: observed.failed.message.substringAfterLast("\n"))
        assertNotEquals("IOException", detail)
        assertTrue(detail, detail.contains("refused", ignoreCase = true))
        assertFailedAt(observed, port, CheckupStage.REACH, R.string.checkup_action_reach_refused)
    }

    @Test fun aPeerThatClosesDuringTheAdbHandshakeIsReportedAsALostConnection() {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                listener.accept().use { socket ->
                    // Reading the whole CNXN makes the close an orderly end of stream rather
                    // than a reset, which is what a forwarder with nothing behind it produces.
                    val input = DataInputStream(socket.getInputStream())
                    val header = ByteArray(24).also { input.readFully(it) }
                    input.readFully(ByteArray(ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)))
                    socket.shutdownOutput()
                    SystemClock.sleep(500)
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            // The port answered, so "could not reach" would send the user checking a right address.
            val observed = checkFailure(listener.localPort, R.string.error_connection_lost)
            assertNull(failure.get())
            // TCP connected, so the address was right; what answered it did not speak ADB.
            assertFailedAt(observed, listener.localPort, CheckupStage.AUTHORIZE, R.string.checkup_action_adb_port)
        } finally {
            listener.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun aFailureReportsBothAnInterruptedTransferAndAShareThatNeverStarted() {
        val store = ViewModelStore()
        val waiting = File.createTempFile("queued-share-", ".bin", app.cacheDir)
        try {
            instrumentation.runOnMainSync {
                val model = MirrorViewModel(app)
                store.put("failure-notice-test", model)
                @Suppress("UNCHECKED_CAST")
                val transfer = MirrorViewModel::class.java.getDeclaredField("_fileTransfer")
                    .apply { isAccessible = true }.get(model) as MutableStateFlow<FileTransferState>
                transfer.value = FileTransferState(running = true, name = "A.bin")
                model.queueSharedFile(waiting.absolutePath, "B.bin")
                MirrorViewModel::class.java.getDeclaredMethod("fail", Throwable::class.java)
                    .apply { isAccessible = true }.invoke(model, IOException("Connection lost"))
                val notice = requireNotNull(model.notice.value)
                assertTrue(notice, notice.contains(app.getString(R.string.send_file_interrupted, "A.bin")))
                assertTrue(notice, notice.contains(app.getString(R.string.share_not_sent)))
                assertNull("No attempt was made, so there are no steps to show", model.checkup.value)
            }
            assertFalse("The abandoned share's private copy must be deleted", waiting.exists())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            waiting.delete()
        }
    }

    @Test fun anAcceptedSocketWithoutAnAdbReplyReportsNotResponding() {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val peer = AtomicReference<Socket>()
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                peer.set(listener.accept())
                accepted.countDown()
                release.await(20, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            val observed = checkFailure(listener.localPort, R.string.error_not_responding)
            assertTrue("The fixture must actually accept TCP before withholding ADB", accepted.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            // A port that accepts and then says nothing is what a pending Allow debugging prompt
            // looks like from here.
            assertFailedAt(observed, listener.localPort, CheckupStage.AUTHORIZE, R.string.checkup_action_authorize)
            // The wait was for the handshake, so it is reported there, not as time spent connecting.
            val authorize = requireNotNull(observed.checkup).steps[CheckupStage.AUTHORIZE.ordinal]
            assertTrue("$authorize", (authorize.elapsedMillis ?: 0L) >= 5_000L)
        } finally {
            release.countDown()
            peer.get()?.close()
            listener.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test fun aDeviceThatRefusesFileTransfersFailsAtServerDeployment() {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val accepted = AtomicReference<Socket>()
        val opened = LinkedBlockingQueue<String>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                val socket = listener.accept().also { accepted.set(it) }
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                assertEquals(A_CNXN, readPacket(input).command)
                // A device that needs no authorization answers the client's CNXN with its own.
                writePacket(output, A_CNXN, 0x01000000, 4096, "device::\u0000".toByteArray())
                while (true) {
                    val packet = readPacket(input)
                    if (packet.command == A_OPEN) {
                        opened.put(String(packet.payload, Charsets.UTF_8).trimEnd('\u0000'))
                        writePacket(output, A_CLSE, 0, packet.arg0)
                    }
                }
            } catch (_: EOFException) {
                // The client hung up after the failure, as it should.
            } catch (_: SocketException) {
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply { isDaemon = true; start() }
        try {
            // Connected and authorized: the device was reached, and then the connection was lost.
            val observed = checkFailure(listener.localPort, R.string.error_connection_lost)
            assertEquals("sync:", opened.poll(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertFailedAt(observed, listener.localPort, CheckupStage.DEPLOY, R.string.checkup_action_reconnect)
        } finally {
            accepted.get()?.close()
            listener.close()
            worker.join(2_000)
            assertFalse(worker.isAlive)
        }
    }

    private class Observed(val failed: MirrorUiState.Failed, val checkup: ConnectionCheckup?)

    /** [stage] failed with [action] as its advice, every step before it passed and none after ran. */
    private fun assertFailedAt(observed: Observed, port: Int, stage: CheckupStage, action: Int) {
        val checkup = requireNotNull(observed.checkup) { "A failed attempt must keep its checkup" }
        assertEquals("127.0.0.1:$port", checkup.endpoint)
        assertEquals(stage, checkup.failedStage)
        for (step in checkup.steps) {
            val expected = when {
                step.stage < stage -> StageStatus.PASSED
                step.stage == stage -> StageStatus.FAILED
                else -> StageStatus.NOT_REACHED
            }
            assertEquals("${step.stage} in $checkup", expected, step.status)
            // A step passed at a moment the attempt could not observe has no duration of its own.
            val untimed = expected == StageStatus.PASSED &&
                CheckupStage.entries.getOrNull(step.stage.ordinal + 1)?.startObserved == false
            assertEquals("${step.stage} duration in $checkup", expected != StageStatus.NOT_REACHED && !untimed,
                step.elapsedMillis != null)
        }
        assertEquals(app.getString(action), app.getString(requireNotNull(checkup.nextAction)))
    }

    private fun checkFailure(port: Int, expectedMessage: Int): Observed {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("failure-test", model)
            model.connect("127.0.0.1", port, MirrorSettings(autoReconnect = false, audioEnabled = false))
        }
        try {
            val until = SystemClock.elapsedRealtime() + 20_000
            var observed: MirrorUiState.Failed? = null
            while (SystemClock.elapsedRealtime() < until) {
                observed = model.state.value as? MirrorUiState.Failed
                if (observed != null) break
                SystemClock.sleep(20)
            }
            val failed = requireNotNull(observed) { "Connection did not produce a bounded failure: ${model.state.value}" }
            assertTrue(failed.message, failed.message.startsWith(app.getString(expectedMessage, "127.0.0.1:$port")))
            assertTrue(failed.guided)
            // Published in the same main-thread step as the failure, before it, so this is the
            // checkup of the attempt that just failed.
            return Observed(failed, model.checkup.value)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private class Packet(val command: Int, val arg0: Int, val payload: ByteArray)

    private fun readPacket(input: DataInputStream): Packet {
        val header = ByteBuffer.wrap(ByteArray(24).also { input.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.getInt(0)
        val length = header.getInt(12)
        require(length in 0..(1024 * 1024)) { "Implausible ADB payload length $length" }
        return Packet(command, header.getInt(4), ByteArray(length).also { input.readFully(it) })
    }

    private fun writePacket(output: OutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
        output.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 255 }).putInt(command.inv()).array())
        output.write(payload)
        output.flush()
    }

    private companion object {
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_CLSE = 0x45534c43
    }
}
