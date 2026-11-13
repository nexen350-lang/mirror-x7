package com.scrcpy.remote

import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.scrcpy.ServerDeployment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ServerDeploymentTest {
    private val path = "/data/local/tmp/test-server.jar"
    private val serverBytes = byteArrayOf(1, 2, 3, 4)

    @Test fun concurrentCatalogDeploymentsWaitForTheWholeTransaction() {
        assertConcurrentDeploymentIsSerialized(holdVerification = false)
    }

    @Test fun concurrentCatalogDeploymentsAlsoWaitForPostPushVerification() {
        assertConcurrentDeploymentIsSerialized(holdVerification = true)
    }

    @Test fun aWaitingCatalogDeploymentCanBeInterruptedWithoutOpeningSync() {
        assertConcurrentDeploymentIsSerialized(holdVerification = true, interruptWaiter = true)
    }

    private fun assertConcurrentDeploymentIsSerialized(
        holdVerification: Boolean,
        interruptWaiter: Boolean = false,
    ) {
        val deployment = ServerDeployment(path, sha256(serverBytes))
        val remote = RemoteFile()
        val firstHeld = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondReachedSync = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val firstResult = AtomicReference<Boolean>()
        val secondResult = AtomicReference<Boolean>()
        val firstFailure = AtomicReference<Throwable>()
        val secondFailure = AtomicReference<Throwable>()

        fun holdFirst() {
            firstHeld.countDown()
            releaseFirst.await()
        }

        val first = Thread({
            try {
                firstResult.set(deployment.ensureDeployed(
                    serverBytes = serverBytes,
                    openSync = {
                        remote.openChannel { command ->
                            if (holdVerification && command == "RECV") holdFirst()
                        }
                    },
                    onPush = { if (!holdVerification) holdFirst() },
                ))
            } catch (error: Throwable) {
                firstFailure.set(error)
            } finally {
                firstFinished.countDown()
            }
        }, "test-first-catalog-deployment").apply { isDaemon = true }
        val second = Thread({
            try {
                secondStarted.countDown()
                secondResult.set(deployment.ensureDeployed(
                    serverBytes = serverBytes,
                    openSync = {
                        secondReachedSync.countDown()
                        remote.openChannel()
                    },
                ))
            } catch (error: Throwable) {
                secondFailure.set(error)
            } finally {
                secondFinished.countDown()
            }
        }, "test-second-catalog-deployment").apply { isDaemon = true }

        try {
            first.start()
            assertTrue("First deployment did not reach its gate", firstHeld.await(10, TimeUnit.SECONDS))
            second.start()
            assertTrue("Second catalog request did not start", secondStarted.await(10, TimeUnit.SECONDS))
            if (interruptWaiter) {
                second.interrupt()
                assertTrue("Interrupted waiter stayed blocked", secondFinished.await(10, TimeUnit.SECONDS))
                assertTrue(
                    "Waiting transaction did not propagate interruption: ${secondFailure.get()}",
                    secondFailure.get() is InterruptedException,
                )
                assertNull(secondResult.get())
                assertEquals("Interrupted waiter opened a sync channel", 1L, secondReachedSync.count)
            } else {
                // Wait until the second thread is observably parked behind the first transaction,
                // then check it has not reached the sync channel. No fixed sleep to outrun.
                val parkUntil = System.nanoTime() + 10_000_000_000L
                while (second.isAlive && second.state == Thread.State.RUNNABLE && System.nanoTime() < parkUntil) {
                    Thread.sleep(5)
                }
                assertEquals(
                    "Second catalog touched the shared server before the first transaction finished",
                    1L, secondReachedSync.count,
                )
            }
            releaseFirst.countDown()
            assertTrue("First deployment did not finish", firstFinished.await(10, TimeUnit.SECONDS))
            assertTrue("Second deployment did not finish", secondFinished.await(10, TimeUnit.SECONDS))
            assertNull(firstFailure.get())
            assertEquals(true, firstResult.get())
            if (interruptWaiter) {
                assertFalse("An interrupted waiter must not poison the lock",
                    deployment.ensureDeployed(serverBytes, { remote.openChannel() }))
            } else {
                assertNull(secondFailure.get())
                assertEquals("The second catalog should reuse the verified copy", false, secondResult.get())
            }
            assertEquals("Concurrent first-use catalogs must not both push", 1, remote.pushes.get())
            assertArrayEquals(serverBytes, remote.contents())
            assertEquals(remote.opens.get(), remote.closes.get())
        } finally {
            releaseFirst.countDown()
            first.interrupt()
            second.interrupt()
            first.join(10_000)
            second.join(10_000)
            assertFalse("First deployment survived test cleanup", first.isAlive)
            assertFalse("Second deployment survived test cleanup", second.isAlive)
        }
    }

    @Test fun sameSizeDifferentServerIsReplacedAndTheMatchingCopyIsReused() {
        val remote = RemoteFile(byteArrayOf(1, 2, 3, 5))
        val deployment = ServerDeployment(path, sha256(serverBytes))
        val notified = AtomicInteger()
        assertTrue(deployment.ensureDeployed(serverBytes, { remote.openChannel() }) {
            notified.incrementAndGet()
        })
        assertFalse(deployment.ensureDeployed(serverBytes, { remote.openChannel() }) {
            notified.incrementAndGet()
        })
        assertEquals(1, remote.pushes.get())
        assertEquals(1, notified.get())
        assertArrayEquals(serverBytes, remote.contents())
        assertEquals(listOf("STAT", "RECV", "SEND", "RECV", "STAT", "RECV"), remote.commands())
        assertEquals(remote.opens.get(), remote.closes.get())
    }

    @Test fun anInvalidBundledDigestFailsBeforeAnySyncChannelIsOpened() {
        val remote = RemoteFile()
        val deployment = ServerDeployment(path, sha256(serverBytes))
        val failure = assertThrows(IllegalStateException::class.java) {
            deployment.ensureDeployed(byteArrayOf(1, 2, 3, 5), { remote.openChannel() })
        }
        assertEquals("Bundled scrcpy server failed integrity verification", failure.message)
        assertEquals(0, remote.opens.get())
    }

    /** Interprets the frames produced by real AdbSync calls; gates never hold the fake file lock. */
    private inner class RemoteFile(initial: ByteArray? = null) {
        private val lock = Any()
        private var bytes = initial?.copyOf()
        private val received = mutableListOf<String>()
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val pushes = AtomicInteger()

        fun contents(): ByteArray? = synchronized(lock) { bytes?.copyOf() }
        fun commands(): List<String> = synchronized(lock) { received.toList() }

        fun openChannel(beforeReply: (String) -> Unit = {}): SyncChannel {
            opens.incrementAndGet()
            return object : SyncChannel {
                private val written = ByteArrayOutputStream()
                override val output = written
                override val input = object : InputStream() {
                    private var reply: InputStream? = null

                    private fun response(): InputStream {
                        reply?.let { return it }
                        val request = written.toByteArray()
                        val command = id(request, 0)
                        beforeReply(command)
                        return answer(command, request).inputStream().also { reply = it }
                    }

                    override fun read(): Int = response().read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        response().read(buffer, offset, length)
                }

                override fun close() { closes.incrementAndGet() }
            }
        }

        private fun answer(command: String, request: ByteArray): ByteArray = synchronized(lock) {
            received += command
            val nameLength = leInt(request, 4)
            val name = String(request, 8, nameLength, Charsets.UTF_8)
            assertEquals(path, if (command == "SEND") name.substringBeforeLast(',') else name)
            when (command) {
                "STAT" -> frame("STAT", if (bytes == null) 0 else 0b1000_000_110_100_100) +
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(bytes?.size ?: 0).putInt(0).array()
                "RECV" -> {
                    val content = bytes
                    if (content == null) {
                        frame("FAIL", 0)
                    } else {
                        frame("DATA", content.size) + content + frame("DONE", 0)
                    }
                }
                "SEND" -> {
                    val payload = ByteArrayOutputStream()
                    var offset = 8 + nameLength
                    var done = false
                    while (offset + 8 <= request.size) {
                        val part = id(request, offset)
                        val length = leInt(request, offset + 4)
                        offset += 8
                        when (part) {
                            "DATA" -> {
                                payload.write(request, offset, length)
                                offset += length
                            }
                            "DONE" -> {
                                done = true
                                break
                            }
                            else -> throw AssertionError("Unexpected sync frame $part")
                        }
                    }
                    assertTrue("Push omitted DONE", done)
                    assertEquals("Unexpected trailing push bytes", request.size, offset)
                    bytes = payload.toByteArray()
                    pushes.incrementAndGet()
                    frame("OKAY", 0)
                }
                else -> throw AssertionError("Unexpected sync request $command")
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun frame(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    private fun id(bytes: ByteArray, offset: Int) =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun leInt(bytes: ByteArray, offset: Int) =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
