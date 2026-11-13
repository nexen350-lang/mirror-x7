package com.scrcpy.remote

import android.view.KeyEvent
import com.scrcpy.remote.session.ClipboardKeys
import com.scrcpy.remote.session.PortDiscoveryNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardKeysTest {
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

    private fun syncs(action: Int = KeyEvent.ACTION_DOWN, keycode: Int = KeyEvent.KEYCODE_V, repeat: Int = 0,
                      meta: Int = ctrl, autosync: Boolean = true, locked: Boolean = false) =
        ClipboardKeys.syncsBeforeForwarding(action, keycode, repeat, meta, autosync, locked)

    @Test fun ctrlVSendsThisPhonesClipboardFirstWhileClipboardsAreSynced() {
        // As on scrcpy's desktop client: Ctrl+V on this phone's keyboard pastes this phone's clipboard.
        assertTrue(syncs())
        // Ctrl+Shift+V pastes as plain text in many apps, and takes the same clipboard.
        assertTrue(syncs(meta = ctrl or KeyEvent.META_SHIFT_ON))
    }

    @Test fun onlyTheFirstPressOfCtrlVAndOnlyWhileSyncedAndUnlocked() {
        assertFalse("the release", syncs(action = KeyEvent.ACTION_UP))
        assertFalse("a held key's repeat", syncs(repeat = 1))
        assertFalse("a plain V types a letter", syncs(meta = 0))
        assertFalse("Ctrl+C copies on the device", syncs(keycode = KeyEvent.KEYCODE_C))
        assertFalse("another shortcut", syncs(meta = ctrl or KeyEvent.META_ALT_ON))
        assertFalse("another shortcut", syncs(meta = ctrl or KeyEvent.META_META_ON))
        assertFalse("each clipboard is kept private", syncs(autosync = false))
        assertFalse("Input Lock sends nothing", syncs(locked = true))
    }

    @Test fun portDiscoveryThatFindsNothingNamesThePortActuallyRetried() {
        assertEquals(PortDiscoveryNotice.Notice(R.string.recovery_original_port), PortDiscoveryNotice.noReplacement(null))
        // A port found by an earlier discovery is still the one dialled, not the saved address.
        assertEquals(PortDiscoveryNotice.Notice(R.string.recovery_kept_port, 40_123), PortDiscoveryNotice.noReplacement(40_123))
    }

    @Test fun theNewerCopyIsTheOneCtrlVPastes() {
        // A copy made on the device while the mirror was hidden never reached this phone; sending
        // this phone's older clip over it would paste the wrong text and lose the device's copy.
        assertTrue("nothing has come from the device", ClipboardKeys.phoneClipIsNewer(200, null))
        assertTrue(ClipboardKeys.phoneClipIsNewer(200, 100))
        assertTrue("the device's copy, put on this phone", ClipboardKeys.phoneClipIsNewer(150, 150))
        assertFalse(ClipboardKeys.phoneClipIsNewer(100, 200))
        assertFalse("a clip of unknown age loses to the device's", ClipboardKeys.phoneClipIsNewer(0, 100))
        assertTrue(ClipboardKeys.phoneClipIsNewer(0, null))
    }
}