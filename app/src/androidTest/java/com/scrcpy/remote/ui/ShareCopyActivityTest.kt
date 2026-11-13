package com.scrcpy.remote.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.ShareCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/** The device list as the destination picker for a share whose copy has not landed yet. */
@RunWith(AndroidJUnit4::class)
class ShareCopyActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aDevicePickedWhileTheShareCopiesConnectsWithTheFileOnceItLands() {
        val repository = DeviceRepository.get(context)
        val device = SavedDevice(name = "Share target fixture", host = "192.0.2.30")
        val gate = CompletableDeferred<Unit>()
        val copy = File(File(context.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
            .apply { writeBytes(byteArrayOf(4, 2)) }
        val launched = Collections.synchronizedList(mutableListOf<Intent>())
        // Records the session it would start rather than starting it.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != MirrorActivity::class.java.name) return null
                launched += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        assertTrue(repository.saveDevice(device))
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MainViewModel::class.java]
                    model.shareCopier = {
                        gate.await()
                        ShareCache.Copied(copy.absolutePath, NAME)
                    }
                    // Delivered as the running list would receive a second share. The launch
                    // intent is put back afterwards: the scenario recognises its activity by it.
                    val launch = activity.intent
                    MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).run {
                        isAccessible = true
                        invoke(activity, Intent(Intent.ACTION_SEND)
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.scrcpy.remote.test.slow/$NAME")))
                    }
                    activity.intent = launch
                }
                // While the file copies, the list already says that picking a device sends it.
                compose.onNodeWithText(text(R.string.share_pick_device)).assertIsDisplayed()
                val connect = context.getString(R.string.device_list_connect, device.name)
                compose.onNodeWithTag("saved-device-list").performScrollToNode(hasContentDescription(connect))
                compose.onNodeWithContentDescription(connect).performClick()
                compose.waitForIdle()
                assertTrue("Nothing connects before the file is ready: $launched", launched.isEmpty())

                gate.complete(Unit)
                compose.waitUntil(10_000) { launched.isNotEmpty() }
                val intent = launched.single()
                assertEquals(device.host, intent.getStringExtra(MirrorActivity.EXTRA_HOST))
                assertEquals(device.id, intent.getStringExtra(MirrorActivity.EXTRA_DEVICE_ID))
                assertEquals(copy.absolutePath, intent.getStringExtra(MirrorActivity.EXTRA_SHARE_PATH))
                assertEquals(NAME, intent.getStringExtra(MirrorActivity.EXTRA_SHARE_NAME))
                compose.onNodeWithText(text(R.string.share_pick_device)).assertDoesNotExist()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            gate.complete(Unit)
            repository.deleteDevice(device.id)
            copy.delete()
        }
    }

    @Test fun aDevicePickedJustAsTheCopyLandsStillTakesTheFile() {
        val repository = DeviceRepository.get(context)
        val device = SavedDevice(name = "Share race fixture", host = "192.0.2.31")
        val copy = File(File(context.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
            .apply { writeBytes(byteArrayOf(7)) }
        val launched = Collections.synchronizedList(mutableListOf<Intent>())
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != MirrorActivity::class.java.name) return null
                launched += intent
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        assertTrue(repository.saveDevice(device))
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = ViewModelProvider(activity)[MainViewModel::class.java]
                    // The copy lands and the device is picked in the same turn of the main
                    // thread, before the list has had a chance to take the result.
                    flow<ShareIngest?>(model, "_shareIngest").value = ShareIngest.Ready(copy.absolutePath, NAME)
                    MainActivity::class.java.getDeclaredMethod("connectFromList", SavedDevice::class.java).run {
                        isAccessible = true
                        invoke(activity, device)
                    }
                }
                compose.waitUntil(10_000) { launched.isNotEmpty() }
                assertEquals("The file goes with the pick", copy.absolutePath,
                    launched.single().getStringExtra(MirrorActivity.EXTRA_SHARE_PATH))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            repository.deleteDevice(device.id)
            copy.delete()
        }
    }

    private fun text(id: Int) = context.getString(id)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private companion object {
        const val NAME = "photo.jpg"
    }
}
