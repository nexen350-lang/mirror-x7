package com.scrcpy.remote.session

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.scrcpy.remote.ui.screenshot.ArtefactSharing

@RunWith(AndroidJUnit4::class)
class RecordingExportLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val jobs = mutableMapOf<MirrorViewModel, Job>()

    @Test fun finishingTheScreenCannotDeleteARecordingBeforeTheExporterOpensIt() {
        withBlockedExport { model, store, source, payload, output, release ->
            instrumentation.runOnMainSync { store.clear() }
            assertTrue("The exporter still owns the source after ViewModel teardown", source.isFile)
            release.countDown()
            awaitJob(model)
            assertArrayEquals(payload, output.readBytes())
            assertTrue("The abandoned cache copy must be removed after exporting", !source.exists())
        }
    }

    @Test fun discardingWhileAnExportIsOpeningCannotRemoveItsSource() {
        withBlockedExport { model, _, source, payload, output, release ->
            instrumentation.runOnMainSync { model.consumeFinishedRecording(keep = false) }
            assertTrue("Save is in progress; discard cannot delete its source", source.isFile)
            assertEquals(source, model.finishedRecording.value)
            release.countDown()
            awaitFinished(model)
            assertArrayEquals(payload, output.readBytes())
        }
    }

    @Test fun aFailedExportStillOffersTheOriginalRecordingForRetry() {
        val source = File.createTempFile("recording-failed-", ".mp4", app.cacheDir)
        source.writeBytes(byteArrayOf(1, 2, 3))
        val store = ViewModelStore()
        val model = createModel(store, source)
        val uri = TestShareProvider.uriFor("recording-failed-export.mp4")
        TestShareProvider.beforeOpenOutput = { if (it == uri) throw IOException("Deliberate output failure") }
        try {
            instrumentation.runOnMainSync { model.saveFinishedRecording(uri) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model.notice.value == null && System.nanoTime() < deadline) Thread.sleep(10)
            assertNotNull("Export failure must be reported", model.notice.value)
            assertEquals(source, model.finishedRecording.value)
            assertTrue(source.isFile)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            awaitJob(model)
            source.delete()
            TestShareProvider.clear(app)
        }
    }

    @Test fun cancellingTheSavePickerKeepsTheRecordingForSaveShareOrDiscard() {
        val source = File.createTempFile("recording-cancelled-picker-", ".mp4", app.cacheDir)
        val payload = byteArrayOf(1, 2, 3)
        source.writeBytes(payload)
        val store = ViewModelStore()
        val model = createModel(store, source)
        try {
            instrumentation.runOnMainSync { model.saveFinishedRecording(null) }
            assertEquals(source, model.finishedRecording.value)
            assertTrue("Cancelling a picker is not discarding the recording", source.isFile)
            assertArrayEquals(payload, source.readBytes())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            awaitJob(model)
            source.delete()
        }
    }

    @Test fun aSharedRecordingRemainsReadableAfterDiscardingThePendingOffer() {
        withSharedRecording { model, _, source, payload, uri ->
            instrumentation.runOnMainSync { model.consumeFinishedRecording(keep = false) }
            assertEquals(null, model.finishedRecording.value)
            assertTrue("Discarding the offer must not invalidate an existing share", source.isFile)
            assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun aDelayedReceiverCanOpenTheRecordingAfterTheOwnerIsCleared() {
        withSharedRecording { model, store, source, payload, uri ->
            instrumentation.runOnMainSync { store.clear() }
            awaitJob(model)
            assertTrue("The receiver, not the cleared ViewModel, now owns the shared lifetime", source.isFile)
            assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun savingASharedRecordingDoesNotInvalidateTheOtherAppsUri() {
        withSharedRecording { model, _, source, payload, uri ->
            val name = "saved-shared-${System.nanoTime()}.mp4"
            val output = File(TestShareProvider.directory(app), name)
            try {
                instrumentation.runOnMainSync { model.saveFinishedRecording(TestShareProvider.uriFor(name)) }
                awaitFinished(model)
                assertArrayEquals(payload, output.readBytes())
                assertTrue(source.isFile)
                assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally {
                output.delete()
                TestShareProvider.clear(app)
            }
        }
    }

    @Test fun aRefusedShareKeepsTheOfferButDoesNotPreventNormalDiscard() {
        withSharedRecording(launched = false) { model, _, source, _, _ ->
            assertEquals(source, model.finishedRecording.value)
            instrumentation.runOnMainSync { model.consumeFinishedRecording(keep = false) }
            assertFalse(source.exists())
        }
    }

    @Test fun sharingDoesNotConsumeTheUsersSaveOrDiscardChoice() {
        withSharedRecording { model, _, source, payload, uri ->
            assertEquals(source, model.finishedRecording.value)
            assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun aLaterRefusedShareDoesNotInvalidateAnEarlierGrant() {
        withSharedRecording { model, _, _, payload, uri ->
            instrumentation.runOnMainSync {
                assertFalse(model.shareFinishedRecording { false })
                model.consumeFinishedRecording(keep = false)
            }
            assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    @Test fun sharingAnOldPendingRecordingRefreshesItsExpiryForTheReceiver() {
        withSharedRecording(ageMillis = TimeUnit.HOURS.toMillis(7)) { model, _, source, payload, uri ->
            instrumentation.runOnMainSync {
                model.consumeFinishedRecording(keep = false)
                MirrorViewModel::class.java.getDeclaredMethod("sweepStaleRecordings", File::class.java)
                    .apply { isAccessible = true }
                    .invoke(model, source.parentFile)
            }
            assertTrue("A newly shared recording must not already be expired", source.isFile)
            assertArrayEquals(payload, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }
    }

    private fun withSharedRecording(
        launched: Boolean = true,
        ageMillis: Long = 0,
        assertions: (MirrorViewModel, ViewModelStore, File, ByteArray, Uri) -> Unit,
    ) {
        val directory = File(app.cacheDir, MirrorViewModel.RECORDING_DIRECTORY).apply { mkdirs() }
        val source = File.createTempFile("shared-recording-", ".mp4", directory)
        val payload = ByteArray(513) { (it % 251).toByte() }
        source.writeBytes(payload)
        if (ageMillis > 0) assertTrue(source.setLastModified(System.currentTimeMillis() - ageMillis))
        val uri = checkNotNull(ArtefactSharing.uriFor(app, source))
        val store = ViewModelStore()
        val model = createModel(store, source)
        try {
            instrumentation.runOnMainSync {
                assertEquals(launched, model.shareFinishedRecording {
                    assertEquals(source, it)
                    launched
                })
            }
            assertions(model, store, source, payload, uri)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            awaitJob(model)
            source.delete()
        }
    }

    private fun withBlockedExport(
        assertions: (MirrorViewModel, ViewModelStore, File, ByteArray, File, CountDownLatch) -> Unit,
    ) {
        val source = File.createTempFile("recording-export-", ".mp4", app.cacheDir)
        val payload = ByteArray(8192) { (it % 251).toByte() }
        source.writeBytes(payload)
        val name = "recording-export-${System.nanoTime()}.mp4"
        val output = File(TestShareProvider.directory(app), name)
        val uri = TestShareProvider.uriFor(name)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = ViewModelStore()
        val model = createModel(store, source)
        TestShareProvider.beforeOpenOutput = { requested ->
            if (requested == uri) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Output-open gate timed out" }
            }
        }
        try {
            instrumentation.runOnMainSync { model.saveFinishedRecording(uri) }
            assertTrue("Export must reach the output provider", entered.await(5, TimeUnit.SECONDS))
            assertions(model, store, source, payload, output, release)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { store.clear() }
            awaitJob(model)
            source.delete()
            output.delete()
            TestShareProvider.clear(app)
        }
    }

    private fun createModel(store: ViewModelStore, source: File): MirrorViewModel {
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            jobs[model] = checkNotNull(model.viewModelScope.coroutineContext[Job])
            store.put("recording-export-test", model)
            val field = MirrorViewModel::class.java.getDeclaredField("_finishedRecording").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val finished = field.get(model) as MutableStateFlow<File?>
            finished.value = source
        }
        return model
    }

    private fun awaitFinished(model: MirrorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (model.finishedRecording.value != null && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(null, model.finishedRecording.value)
    }

    private fun awaitJob(model: MirrorViewModel) = runBlocking {
        withTimeout(5_000) { jobs.getValue(model).join() }
    }
}
