package com.scrcpy.remote.session

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A finished recording waiting for Save or Discard outlives the process, as the document picker
 * it is waiting on can: Android may kill the app behind the picker.
 */
@RunWith(AndroidJUnit4::class)
class FinishedRecordingRestoreTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val directory = File(app.cacheDir, MirrorViewModel.RECORDING_DIRECTORY)

    @Test fun anOfferedRecordingSurvivesTheProcessAndIsSavedToTheDocumentChosenMeanwhile() {
        val recording = recording()
        val output = File.createTempFile("restored-recording-", ".mp4", app.cacheDir)
        val stores = mutableListOf<ViewModelStore>()
        try {
            val saved = SavedStateHandle()
            main { model(saved, stores).offerFinishedRecording(recording) }
            // The process dies: only the saved state comes back.
            val restored = copy(saved)
            lateinit var replacement: MirrorViewModel
            main {
                replacement = model(restored, stores)
                assertEquals("The offer is restored", recording, replacement.finishedRecording.value)
                replacement.saveFinishedRecording(Uri.fromFile(output))
            }
            await { !replacement.recordingSaveInProgress.value && replacement.finishedRecording.value == null }
            assertArrayEquals(PAYLOAD, output.readBytes())
            // Once kept, a later restoration has nothing to offer.
            main { assertNull(model(copy(restored), stores).finishedRecording.value) }
        } finally {
            main { stores.forEach { it.clear() } }
            recording.delete()
            output.delete()
        }
    }

    @Test fun aDiscardedRecordingIsNotOfferedAgainAfterTheProcess() {
        val recording = recording()
        val stores = mutableListOf<ViewModelStore>()
        try {
            val saved = SavedStateHandle()
            main {
                val model = model(saved, stores)
                model.offerFinishedRecording(recording)
                model.consumeFinishedRecording(keep = false)
            }
            main { assertNull(model(copy(saved), stores).finishedRecording.value) }
        } finally {
            main { stores.forEach { it.clear() } }
            recording.delete()
        }
    }

    @Test fun aRecordingAlreadySharedIsLeftForItsReceiverAfterTheProcess() {
        val recording = recording()
        val stores = mutableListOf<ViewModelStore>()
        try {
            val saved = SavedStateHandle()
            main {
                val model = model(saved, stores)
                model.offerFinishedRecording(recording)
                assertTrue(model.shareFinishedRecording { true })
            }
            main {
                val restored = model(copy(saved), stores)
                assertEquals(recording, restored.finishedRecording.value)
                restored.consumeFinishedRecording(keep = false)
            }
            // Another app may still be reading it through its grant; the cache expiry removes it.
            assertTrue("The receiver's file must survive Discard", recording.exists())
        } finally {
            main { stores.forEach { it.clear() } }
            recording.delete()
        }
    }

    @Test fun onlyAnExistingRecordingInTheRecordingCacheIsRestored() {
        val stores = mutableListOf<ViewModelStore>()
        val outside = File(app.cacheDir, "session-1.mp4").apply { writeBytes(PAYLOAD) }
        try {
            for (name in listOf("../session-1.mp4", "session-404.mp4", "notes.txt", "")) {
                val saved = SavedStateHandle(mapOf(MirrorViewModel.STATE_FINISHED_RECORDING to name))
                main {
                    assertNull("Restored $name", model(saved, stores).finishedRecording.value)
                    assertNull("Cleared $name", saved.get<String>(MirrorViewModel.STATE_FINISHED_RECORDING))
                }
            }
            assertTrue("A file outside the recordings is never touched", outside.exists())
        } finally {
            main { stores.forEach { it.clear() } }
            outside.delete()
        }
    }

    private fun recording(): File {
        directory.mkdirs()
        return File(directory, "session-${System.nanoTime()}.mp4").apply { writeBytes(PAYLOAD) }
    }

    private fun model(saved: SavedStateHandle, stores: MutableList<ViewModelStore>): MirrorViewModel =
        MirrorViewModel(app, saved).also { model -> ViewModelStore().also { stores += it }.put("session", model) }

    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for the recording to be saved")
    }

    private companion object {
        val PAYLOAD = byteArrayOf(2, 4, 6, 8)
    }
}
