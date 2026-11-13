package com.scrcpy.remote.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.ShareCache
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A shared file is copied in before it can be sent. While that copy runs the share is still
 * pending, and a device picked meanwhile is kept for the moment the file lands.
 */
@RunWith(AndroidJUnit4::class)
class ShareCopyQueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val device = SavedDevice(name = "Queue fixture", host = "192.0.2.20")

    @Test fun aDevicePickedWhileTheFileIsStillCopyingIsKeptUntilItLands() {
        withSlowCopy { model, gate, copy ->
            main {
                assertTrue("The share is pending while it copies", model.shareCopying.value)
                assertTrue(model.queueShareDestination(device))
                assertEquals(device, model.shareDestination.value)
            }
            gate.complete(Unit)
            await { model.shareIngest.value is ShareIngest.Ready }
            main {
                assertFalse(model.shareCopying.value)
                assertEquals(ShareIngest.Ready(copy.absolutePath, NAME), model.shareIngest.value)
                assertEquals(device, model.takeShareDestination())
                assertNull("Taken once", model.takeShareDestination())
            }
        }
    }

    @Test fun cancellingWhileCopyingDropsTheCopyAndThePickedDevice() {
        withSlowCopy { model, gate, copy ->
            main {
                model.queueShareDestination(device)
                model.cancelShare()
                assertFalse(model.shareCopying.value)
                assertNull(model.shareDestination.value)
            }
            gate.complete(Unit)
            await { !copy.exists() }
            main { assertNull("A cancelled copy is never offered", model.shareIngest.value) }
        }
    }

    @Test fun nothingIsQueuedWhenNoCopyIsRunning() {
        val store = ViewModelStore()
        try {
            main {
                val model = MainViewModel(app).also { store.put("share-queue", it) }
                assertFalse(model.shareCopying.value)
                assertFalse(model.queueShareDestination(device))
                assertNull(model.shareDestination.value)
            }
        } finally {
            main { store.clear() }
        }
    }

    private fun withSlowCopy(block: (MainViewModel, CompletableDeferred<Unit>, File) -> Unit) {
        val gate = CompletableDeferred<Unit>()
        val copy = File(File(app.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
            .apply { writeBytes(byteArrayOf(9, 9)) }
        val store = ViewModelStore()
        try {
            lateinit var model: MainViewModel
            main {
                model = MainViewModel(app).also { store.put("share-queue", it) }
                model.shareCopier = {
                    gate.await()
                    ShareCache.Copied(copy.absolutePath, NAME)
                }
                model.ingestShare(Uri.parse("content://com.scrcpy.remote.test.slow/$NAME"))
            }
            block(model, gate, copy)
        } finally {
            gate.complete(Unit)
            main { store.clear() }
            copy.delete()
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for the shared copy")
    }

    private companion object {
        const val NAME = "photo.jpg"
    }
}
