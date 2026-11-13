package com.scrcpy.remote.session

import android.app.Application
import android.os.CancellationSignal
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TransferCancellationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun cancellingAStreamDoesNotWaitForItsBlockingCloseOnMain() =
        checkCancellation("fileTransferChannel", { gate -> Closeable { gate() } }) { model, _ ->
            model.cancelFileTransfer()
        }

    @Test fun disconnectCanReachPhysicalCleanupWithoutWaitingForALogicalStreamClose() =
        checkCancellation("fileTransferChannel", { gate -> Closeable { gate() } }) { model, _ ->
            model.disconnect()
        }

    @Test fun cancellingAProviderDoesNotWaitForItsBinderCancellationOnMain() =
        checkCancellation("fileTransferSignal", { gate ->
            CancellationSignal().apply { setOnCancelListener { gate() } }
        }) { model, _ -> model.cancelFileTransfer() }

    @Test fun cancellingASourceDoesNotWaitForItsBlockingCloseOnMain() =
        checkCancellation("fileTransferSource", { gate -> Closeable { gate() } }) { model, _ ->
            model.cancelFileTransfer()
        }

    @Test fun clearingTheOwnerKeepsSourceCleanupTrackedUntilItCloses() =
        checkCancellation("fileTransferSource", { gate -> Closeable { gate() } }, clearsOwner = true) { _, store ->
            store.clear()
        }

    @Test fun clearingTheOwnerKeepsCleanupTrackedUntilTheResourceCloses() =
        checkCancellation("fileTransferChannel", { gate -> Closeable { gate() } }, clearsOwner = true) { _, store ->
            store.clear()
        }

    @Test fun replacingAPendingShareRemovesOnlyTheSupersededCacheCopy() {
        val first = File.createTempFile("share-superseded-", ".bin", app.cacheDir)
        val second = File.createTempFile("share-pending-", ".bin", app.cacheDir)
        try {
            withModel { model ->
                model.queueSharedFile(first.absolutePath, first.name)
                model.queueSharedFile(second.absolutePath, second.name)
                assertFalse("The superseded upload must not be stranded in cache", first.exists())
                assertTrue("The newly queued upload still belongs to the session", second.isFile)
                assertTrue(model.transferHoldsProcess.value)
            }
            assertFalse("Owner teardown removes the remaining upload", second.exists())
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test fun reofferingTheSamePendingShareDoesNotDeleteItsContents() {
        val source = File.createTempFile("share-reoffered-", ".bin", app.cacheDir)
        try {
            withModel { model ->
                model.queueSharedFile(source.absolutePath, source.name)
                model.queueSharedFile(source.absolutePath, "Updated label")
                assertTrue(source.isFile)
                assertTrue(model.transferHoldsProcess.value)
            }
            assertFalse(source.exists())
        } finally {
            source.delete()
        }
    }

    private fun withModel(assertions: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var scopeJob: Job
        try {
            instrumentation.runOnMainSync {
                val model = MirrorViewModel(app)
                scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
                store.put("pending-share-test", model)
                assertions(model)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runBlocking { withTimeout(5_000) { scopeJob.join() } }
        }
    }

    private fun checkCancellation(
        fieldName: String,
        resource: (() -> Unit) -> Any,
        clearsOwner: Boolean = false,
        cancel: (MirrorViewModel, ViewModelStore) -> Unit,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        lateinit var scopeJob: Job
        lateinit var cleanupJob: Job
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
            val cleanupField = MirrorViewModel::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
            cleanupJob = checkNotNull((cleanupField.get(model) as CoroutineScope).coroutineContext[Job])
            store.put("transfer-cancellation-test", model)
            val field = MirrorViewModel::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
            field.set(model, resource {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Cancellation gate timed out" }
            })
        }
        val caller = thread(name = "transfer-cancel-test", isDaemon = true) {
            try {
                instrumentation.runOnMainSync { cancel(model, store) }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                returned.countDown()
            }
        }
        try {
            assertTrue("Cancellation must reach the resource", entered.await(5, TimeUnit.SECONDS))
            assertTrue("Main must return while resource cleanup is still blocked", returned.await(1, TimeUnit.SECONDS))
            assertNull(failure.get())
            if (clearsOwner) {
                val finishedEarly = runBlocking {
                    withTimeoutOrNull(250) { cleanupJob.join(); true } ?: false
                }
                assertFalse("Cleanup must remain owned while the resource is still closing", finishedEarly)
            }
        } finally {
            release.countDown()
            caller.join(5_000)
            assertFalse("The test must not leave its caller blocked", caller.isAlive)
            instrumentation.runOnMainSync { store.clear() }
            runBlocking { withTimeout(5_000) { scopeJob.join(); cleanupJob.join() } }
        }
    }
}
