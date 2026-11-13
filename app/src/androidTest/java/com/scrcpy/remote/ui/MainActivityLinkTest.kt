package com.scrcpy.remote.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.TestShareProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Links, launcher shortcuts and shares arriving at the device list. */
@RunWith(AndroidJUnit4::class)
class MainActivityLinkTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = DeviceRepository.get(context)
    private val saved = mutableListOf<String>()
    private val mirrors = MirrorLaunches()

    /** Records mirror launches instead of starting sessions to addresses nobody is listening on. */
    private class MirrorLaunches : Instrumentation.ActivityMonitor() {
        val intents = LinkedBlockingQueue<Intent>()
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className != MirrorActivity::class.java.name) return null
            intents.add(Intent(intent))
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    @Before fun intercept() = instrumentation.addMonitor(mirrors)

    @After fun cleanUp() {
        instrumentation.removeMonitor(mirrors)
        saved.forEach { assertTrue(repository.deleteDevice(it)) }
        TestShareProvider.clear(context)
    }

    private fun save(device: SavedDevice): SavedDevice {
        assertTrue(repository.saveDevice(device))
        saved += device.id
        return device
    }

    private fun link(uri: String) = Intent(Intent.ACTION_VIEW, uri.toUri(), context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts the device list on [intent] and finishes it afterwards.
     *
     * Not ActivityScenario: the screen consumes a link by clearing the intent's data, after which
     * the scenario no longer recognises its own activity. Matching happens before onCreate here.
     */
    private fun withMainActivity(intent: Intent, block: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        }
    }

    private fun inDialog(text: String, substring: Boolean = true) =
        hasText(text, substring = substring) and hasAnyAncestor(isDialog())

    private fun dialogShows(text: String): Boolean {
        compose.waitForIdle()
        return compose.onAllNodes(inDialog(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun aLinkToAnAddressSeveralProfilesShareAsksWhichOneToUse() {
        // Duplicating a profile is how one phone gets work and camera settings side by side.
        val work = save(SavedDevice(name = "Phone (work)", host = "192.0.2.77", port = 5555))
        val camera = save(SavedDevice(name = "Phone (camera)", host = "192.0.2.77", port = 5555))
        withMainActivity(link("scrcpy://192.0.2.77:5555")) {
            compose.waitUntil(5_000) { dialogShows(context.getString(R.string.link_confirm_title)) }
            assertTrue("Every matching profile is offered", dialogShows(work.name))
            assertTrue("Every matching profile is offered", dialogShows(camera.name))
            val connect = compose.onNode(inDialog(context.getString(R.string.connect), substring = false))
            connect.assertIsNotEnabled()
            compose.onNode(inDialog(camera.name, substring = false)).performClick()
            connect.assertIsEnabled().performClick()
            val launched = mirrors.intents.poll(5, TimeUnit.SECONDS)
            assertNotNull("Connecting must start the mirror", launched)
            assertEquals(camera.id, launched!!.getStringExtra(MirrorActivity.EXTRA_DEVICE_ID))
        }
    }

    @Test fun aShortcutToASavedDeviceReplacesAnOlderLinkPrompt() {
        val desk = save(SavedDevice(name = "Desk phone", host = "192.0.2.78", port = 5555))
        withMainActivity(link("scrcpy://192.0.2.79:5555")) { activity ->
            compose.waitUntil(5_000) { dialogShows(context.getString(R.string.link_confirm_title)) }
            instrumentation.runOnMainSync {
                activity.startActivity(Intent(activity, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, desk.id)
                    .putExtra(MainActivity.EXTRA_SHORTCUT_NONCE, ShortcutTrust.nonce(activity))
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            val launched = mirrors.intents.poll(5, TimeUnit.SECONDS)
            assertEquals(desk.id, launched?.getStringExtra(MirrorActivity.EXTRA_DEVICE_ID))
            // The prompt for the other address must not reappear when that session ends.
            assertFalse(dialogShows(context.getString(R.string.link_confirm_title)))
        }
    }

    @Test fun leavingTheDeviceListDiscardsAShareThatWasNeverSent() {
        val uri = TestShareProvider.offer(context, "unsent.bin", byteArrayOf(1, 2, 3))
        val share = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
        var path: String? = null
        try {
            ActivityScenario.launch<MainActivity>(share).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (path == null && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { path = pendingShare(it) }
                    if (path == null) SystemClock.sleep(50)
                }
                assertNotNull("The shared copy must be waiting for a destination", path)
                assertTrue(File(path!!).isFile)
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (File(path!!).exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertFalse("Nothing can send this copy any more, so it must not stay in the cache", File(path!!).exists())
        } finally {
            // A failure must not leave the copy for later tests that expect an empty share cache.
            path?.let { File(it).delete() }
            TestShareProvider.clear(context)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingShare(activity: MainActivity): String? =
        (MainActivity::class.java.getDeclaredField("pendingShare\$delegate").apply { isAccessible = true }
            .get(activity) as MutableState<String?>).value
}
