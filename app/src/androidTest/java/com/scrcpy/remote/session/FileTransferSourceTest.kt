package com.scrcpy.remote.session

import android.net.Uri
import android.content.res.AssetFileDescriptor
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.adb.SyncChannel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class FileTransferSourceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @After fun resetProvider() {
        TestShareProvider.clear(context)
    }

    @Test fun anEmptyReliableProviderFailureCannotCommitAnEmptyRemoteFile() {
        assertReliableProviderFailure(ByteArray(0), AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    @Test fun aReliableProviderFailureAfterAllExpectedBytesStillPreventsCommit() {
        assertReliableProviderFailure(byteArrayOf(1, 2, 3), AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    @Test fun aDeclaredAssetBoundaryDoesNotHideAReliableProviderFailure() {
        assertReliableProviderFailure(byteArrayOf(1, 2, 3), 3)
    }

    @Test fun cleanReliableProvidersStillCommitEmptyAndNonemptyFiles() {
        for (payload in listOf(ByteArray(0), byteArrayOf(1, 2, 3))) {
            withReliableProvider(payload, AssetFileDescriptor.UNKNOWN_LENGTH, error = null) { uri ->
                val output = ByteArrayOutputStream()
                FileTransfers.withSource(resolver, uri, CancellationSignal(), {}, {}) { input ->
                    pushToFakeDevice(input, payload.size.toLong(), output)
                }
                assertTrue(output.toString("ISO-8859-1").endsWithDonePacket())
            }
        }
    }

    private fun assertReliableProviderFailure(payload: ByteArray, declaredLength: Long) {
        withReliableProvider(payload, declaredLength, "download failed") { uri ->
            val output = ByteArrayOutputStream()
            val failure = assertThrows(IOException::class.java) {
                FileTransfers.withSource(resolver, uri, CancellationSignal(), {}, {}) { input ->
                    pushToFakeDevice(input, payload.size.toLong(), output)
                }
            }
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("download failed"))
            assertFalse("A producer error must prevent the ADB DONE commit",
                output.toString("ISO-8859-1").endsWithDonePacket())
        }
    }

    private fun withReliableProvider(
        payload: ByteArray,
        declaredLength: Long,
        error: String?,
        assertions: (Uri) -> Unit,
    ) {
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val uri = TestShareProvider.uriFor("reliable-source.bin")
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            writer.write(payload)
            writer.flush()
            if (error == null) pipe[1].close() else pipe[1].closeWithError(error)
            TestShareProvider.openAssetInput = { _, _ ->
                AssetFileDescriptor(pipe[0], 0, declaredLength)
            }
            assertions(uri)
        } finally {
            writer.close()
            pipe[0].close()
            TestShareProvider.openAssetInput = null
        }
    }

    private fun pushToFakeDevice(
        source: java.io.InputStream,
        size: Long,
        written: ByteArrayOutputStream,
    ) {
        AdbSync.push(
            openChannel = {
                object : SyncChannel {
                    override val input = ByteArrayInputStream(byteArrayOf(79, 75, 65, 89, 0, 0, 0, 0))
                    override val output = written
                    override fun close() = Unit
                }
            },
            source = source,
            size = size,
            remotePath = "/sdcard/Download/reliable-source.bin",
        )
    }

    private fun String.endsWithDonePacket(): Boolean =
        length >= 8 && substring(length - 8, length - 4) == "DONE"

    @Test fun cancellationBeforeOpeningNeverContactsTheProvider() {
        val opened = AtomicInteger()
        val uri = TestShareProvider.offer(context, "already-cancelled.bin", byteArrayOf(1))
        TestShareProvider.openInput = { _, _ ->
            opened.incrementAndGet()
            error("A cancelled request must not open its source")
        }
        val signal = CancellationSignal().apply { cancel() }
        assertThrows(OperationCanceledException::class.java) {
            FileTransfers.withSource(resolver, uri, signal, {}, {}) { fail("Must not consume"); Unit }
        }
        assertEquals(0, opened.get())
    }

    @Test fun cancellationDuringProviderOpenReachesTheProvider() {
        val uri = TestShareProvider.uriFor("blocked-source.bin")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val signal = CancellationSignal()
        TestShareProvider.openInput = { _, providerSignal ->
            providerSignal?.setOnCancelListener {
                cancelled.countDown()
                release.countDown()
            }
            entered.countDown()
            check(release.await(8, TimeUnit.SECONDS)) { "Provider gate timed out" }
            providerSignal?.throwIfCanceled()
            throw IOException("Released without provider cancellation")
        }
        val worker = thread(name = "test-source-open", isDaemon = true) {
            try {
                FileTransfers.withSource(resolver, uri, signal, {}, {}) { fail("Must not consume"); Unit }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            signal.cancel()
            assertTrue("The open call must carry the cancellation signal", cancelled.await(1, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get().toString(), failure.get() is OperationCanceledException)
        } finally {
            release.countDown()
            worker.join(3_000)
            assertFalse("No blocked test worker may remain", worker.isAlive)
        }
    }

    @Test fun aRegisteredSourceCanBeClosedWhileItsReadIsBlocked() {
        val pipe = ParcelFileDescriptor.createPipe()
        val uri = TestShareProvider.uriFor("waiting-pipe.bin")
        TestShareProvider.openInput = { _, _ -> pipe[0] }
        val source = ResourceHandoff<Closeable>()
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = thread(name = "test-source-read", isDaemon = true) {
            try {
                FileTransfers.withSource(resolver, uri, CancellationSignal(), source::offer, source::release) {
                    entered.countDown()
                    it.read()
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                returned.countDown()
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertFalse("The pipe must still be waiting for data", returned.await(100, TimeUnit.MILLISECONDS))
            source.close()
            assertTrue("Cancelling ownership must release a blocked source read", returned.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get().toString(), failure.get() is IOException)
        } finally {
            pipe[1].close()
            worker.join(3_000)
            source.close()
            pipe[0].close()
            assertFalse(worker.isAlive)
        }
    }

    @Test fun cancellationAfterRegistrationNeverConsumesTheSource() {
        val uri = TestShareProvider.offer(context, "cancel-after-open.bin", byteArrayOf(1))
        val signal = CancellationSignal()
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        assertThrows(OperationCanceledException::class.java) {
            FileTransfers.withSource(resolver, uri, signal, {
                opened.incrementAndGet()
                signal.cancel()
            }, { closed.incrementAndGet() }) {
                fail("The source became cancelled before consumption")
            }
        }
        assertEquals(1, opened.get())
        assertEquals(1, closed.get())
    }

    @Test fun aDescriptorReturnedAfterCancellationIsClosedWithoutBeingConsumed() {
        val uri = TestShareProvider.offer(context, "late-source.bin", byteArrayOf(1))
        val signal = CancellationSignal()
        val descriptor = ParcelFileDescriptor.open(
            File(TestShareProvider.directory(context), "late-source.bin"),
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        val fileDescriptor = descriptor.fileDescriptor
        TestShareProvider.openInput = { _, _ ->
            signal.cancel()
            descriptor
        }
        try {
            assertThrows(OperationCanceledException::class.java) {
                FileTransfers.withSource(resolver, uri, signal, {}, {}) { fail("Must not consume"); Unit }
            }
            assertFalse("A provider result returned after cancellation must not leak", fileDescriptor.valid())
        } finally {
            descriptor.close()
        }
    }

    @Test fun registrationAndConsumptionFailuresStillReleaseSourceOwnership() {
        val uri = TestShareProvider.offer(context, "failed-source.bin", byteArrayOf(1))
        for (failAtRegistration in listOf(true, false)) {
            val source = AtomicReference<Closeable?>()
            val closed = AtomicInteger()
            val expected = IOException("Deliberate source failure")
            val actual = assertThrows(IOException::class.java) {
                FileTransfers.withSource(resolver, uri, CancellationSignal(), {
                    source.set(it)
                    if (failAtRegistration) throw expected
                }, { closed.incrementAndGet() }) {
                    throw expected
                }
            }
            assertSame(expected, actual)
            assertEquals(1, closed.get())
            source.get()!!.close()
            assertEquals("Closing twice must not release ownership twice", 1, closed.get())
        }
    }

    @Test fun cancelledMetadataIsNotReplacedWithAnotherProviderRequest() {
        val uri = TestShareProvider.offer(context, "cancelled-metadata.bin", byteArrayOf(1))
        val opened = AtomicInteger()
        TestShareProvider.beforeQuery = { throw OperationCanceledException("Provider cancelled its request") }
        TestShareProvider.openInput = { _, _ ->
            opened.incrementAndGet()
            ParcelFileDescriptor.open(
                File(TestShareProvider.directory(context), "cancelled-metadata.bin"),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        assertThrows(OperationCanceledException::class.java) {
            FileTransfers.describe(resolver, uri, CancellationSignal())
        }
        assertEquals("Cancellation must not fall back to opening the source", 0, opened.get())
    }

    @Test fun anUnsupportedMetadataQueryStillFallsBackToTheDescriptor() {
        val payload = ByteArray(97) { it.toByte() }
        val uri = TestShareProvider.offer(context, "fallback.bin", payload)
        TestShareProvider.beforeQuery = { throw UnsupportedOperationException("No metadata query") }
        val details = FileTransfers.describe(resolver, uri, CancellationSignal())
        assertEquals("fallback.bin", details.name)
        assertEquals(payload.size.toLong(), details.size)
    }

    @Test fun ordinaryContentAndPrivateFileSourcesRetainTheirBytesAndCloseOwnership() {
        val payload = ByteArray(257) { it.toByte() }
        val uri = TestShareProvider.offer(context, "ordinary.bin", payload)
        val file = File(TestShareProvider.directory(context), "ordinary.bin")
        for (sourceUri in listOf(uri, Uri.fromFile(file))) {
            val opened = AtomicInteger()
            val closed = AtomicInteger()
            val bytes = FileTransfers.withSource(resolver, sourceUri, CancellationSignal(),
                { opened.incrementAndGet() }, { closed.incrementAndGet() }) { it.readBytes() }
            assertArrayEquals(payload, bytes)
            assertEquals(1, opened.get())
            assertEquals(1, closed.get())
        }
    }

    @Test fun aProviderAssetReadsOnlyItsDeclaredSubsection() {
        val payload = byteArrayOf(9, 8, 1, 2, 3, 7, 6)
        val uri = TestShareProvider.offer(context, "subsection.bin", payload)
        TestShareProvider.openAssetInput = { _, _ ->
            AssetFileDescriptor(
                ParcelFileDescriptor.open(
                    File(TestShareProvider.directory(context), "subsection.bin"),
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ),
                2,
                3,
            )
        }
        FileTransfers.withSource(resolver, uri, CancellationSignal(), {}, {}) {
            assertEquals(1, it.read())
            assertArrayEquals(byteArrayOf(2, 3), it.readBytes())
            assertEquals(-1, it.read())
            assertEquals(0, it.read(ByteArray(0)))
            assertEquals(0, it.available())
        }
        FileTransfers.withSource(resolver, uri, CancellationSignal(), {}, {}) {
            assertEquals(3L, it.skip(Long.MAX_VALUE))
            assertEquals(-1, it.read())
        }
    }
}
