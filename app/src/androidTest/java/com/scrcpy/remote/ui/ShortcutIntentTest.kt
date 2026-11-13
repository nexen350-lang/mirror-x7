package com.scrcpy.remote.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A launcher shortcut is an `ACTION_VIEW` carrying no data, which is close enough to a malformed
 * `scrcpy://` link that telling the two apart is the whole job of the deep link guard.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutIntentTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun intent() = Intent(context, MainActivity::class.java)

    private fun invalidLinkShown(): Boolean {
        compose.waitForIdle()
        return compose.onAllNodes(hasText(context.getString(R.string.ui_invalid_link_title)))
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun aLauncherShortcutIsNotMistakenForABrokenLink() {
        // The shortcut names a saved device by id and carries no URI at all. Accepting "a VIEW,
        // or anything with data" made every shortcut tap report a broken link over the session it
        // had just started.
        val shortcut = intent()
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, "no-such-device")
        ActivityScenario.launch<MainActivity>(shortcut).use {
            assertFalse("A launcher shortcut must not report an invalid link", invalidLinkShown())
        }
    }

    @Test fun anOrdinaryLaunchIsNotMistakenForABrokenLink() {
        ActivityScenario.launch<MainActivity>(intent().setAction(Intent.ACTION_MAIN)).use {
            assertFalse("A plain launch must not report an invalid link", invalidLinkShown())
        }
    }

    @Test fun aGenuinelyMalformedLinkIsStillReported() {
        // The guard was tightened, so this proves it still accepts what it is meant to accept
        // rather than having simply been turned off. Checked here rather than by launching the
        // activity: a singleTop activity relaunched into an existing task is delivered through
        // onNewIntent, which ActivityScenario cannot observe.
        assertTrue(isConnectionLink(Intent.ACTION_VIEW, "scrcpy://example.test:99999".toUri()))
        assertTrue(isConnectionLink(Intent.ACTION_VIEW, "scrcpy://10.0.0.2:5555".toUri()))
    }

    @Test fun onlyAViewIntentCarryingAUriIsALink() {
        // A shortcut is a VIEW with no data; a share is a SEND; a plain launch is a MAIN.
        assertFalse(isConnectionLink(Intent.ACTION_VIEW, null))
        assertFalse(isConnectionLink(Intent.ACTION_MAIN, null))
        assertFalse(isConnectionLink(Intent.ACTION_SEND, null))
        assertFalse(isConnectionLink(null, "scrcpy://10.0.0.2:5555".toUri()))
        assertFalse(isConnectionLink(Intent.ACTION_SEND, "content://x/y".toUri()))
    }

    @Test fun aShortcutStillWorksWhenItsSavedDeviceIsGone() {
        // A pinned icon outlives the device list: delete the device, or reinstall and restore,
        // and the id no longer resolves. It used to silently do nothing. It now offers the
        // address it was created with — and asks first, because an address that is not already
        // a saved device cannot be treated as pre-approved on an exported activity.
        val stale = intent()
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, "no-such-device")
            .putExtra(MainActivity.EXTRA_SHORTCUT_HOST, "192.0.2.20")
            .putExtra(MainActivity.EXTRA_SHORTCUT_PORT, 555)
            .putExtra(MainActivity.EXTRA_SHORTCUT_NAME, "Desk phone")
        ActivityScenario.launch<MainActivity>(stale).use {
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.link_confirm_title)).assertIsDisplayed()
        }
    }

    @Test fun aShortcutWithNeitherAKnownDeviceNorAnAddressDoesNothing() {
        val empty = intent()
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, "no-such-device")
        ActivityScenario.launch<MainActivity>(empty).use {
            compose.waitForIdle()
            assertFalse(
                "Nothing to connect to, so nothing should be offered",
                compose.onAllNodes(hasText(context.getString(R.string.link_confirm_title)))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test fun aShortcutCarryingAnUnusableAddressIsIgnored() {
        // The extras are ordinary intent data on an exported activity, so they are validated the
        // same way a typed address would be.
        val bad = intent()
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_SHORTCUT_DEVICE_ID, "no-such-device")
            .putExtra(MainActivity.EXTRA_SHORTCUT_HOST, "not a host")
            .putExtra(MainActivity.EXTRA_SHORTCUT_PORT, 555)
        ActivityScenario.launch<MainActivity>(bad).use {
            compose.waitForIdle()
            assertFalse(
                "An unusable address must not be offered",
                compose.onAllNodes(hasText(context.getString(R.string.link_confirm_title)))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}