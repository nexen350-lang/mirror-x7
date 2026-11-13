package com.scrcpy.remote

import android.view.KeyEvent
import android.view.MotionEvent
import com.scrcpy.remote.session.KeyForwardingPolicy
import com.scrcpy.remote.session.MouseButtonPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputForwardingPolicyTest {

    @Test fun thisAppKeepsTheKeysItsOwnControlsAlreadyOwn() {
        // Back stays local because the documented rule is that the local system Back controls
        // this app; volume acts on this phone's playback of the remote stream.
        for (reserved in listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
        )) {
            assertFalse("keycode $reserved", KeyForwardingPolicy.forwards(reserved))
        }
    }

    @Test fun ordinaryTypingAndNavigationReachTheDevice() {
        for (forwarded in listOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_MENU,
        )) {
            assertTrue("keycode $forwarded", KeyForwardingPolicy.forwards(forwarded))
        }
    }

    @Test fun nothingIsForwardedWithoutASessionOrWhileTypingLocally() {
        val key = KeyEvent.KEYCODE_A
        assertTrue(KeyForwardingPolicy.shouldForward(key, connected = true, typingLocally = false))
        // The draft field composes text locally; injecting the same keystroke would double it.
        assertFalse(KeyForwardingPolicy.shouldForward(key, connected = true, typingLocally = true))
        assertFalse(KeyForwardingPolicy.shouldForward(key, connected = false, typingLocally = false))
        // A reserved key stays local even when everything else would allow forwarding.
        assertFalse(KeyForwardingPolicy.shouldForward(
            KeyEvent.KEYCODE_BACK, connected = true, typingLocally = false))
    }

    @Test fun nonPrimaryMouseButtonsMapToNavigation() {
        assertEquals(KeyEvent.KEYCODE_BACK, MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_SECONDARY))
        assertEquals(KeyEvent.KEYCODE_HOME, MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_TERTIARY))
        assertEquals(KeyEvent.KEYCODE_BACK, MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_BACK))
        assertEquals(KeyEvent.KEYCODE_APP_SWITCH, MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_FORWARD))
    }

    @Test fun mouseBackKeyIsOnlyConsumedWhenItsButtonPressWasForwarded() {
        assertTrue(MouseButtonPolicy.consumesKey(connected = true, cameraSession = false, visible = true))
        assertFalse(MouseButtonPolicy.consumesKey(connected = false, cameraSession = false, visible = true))
        assertFalse(MouseButtonPolicy.consumesKey(connected = true, cameraSession = true, visible = true))
        assertFalse(MouseButtonPolicy.consumesKey(connected = true, cameraSession = false, visible = false))
    }

    @Test fun thePrimaryButtonStaysAPointerPress() {
        // Mapping it to navigation would make an ordinary click unusable for touching the screen.
        assertNull(MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_PRIMARY))
        assertNull(MouseButtonPolicy.keycodeFor(0))
        assertNull(MouseButtonPolicy.keycodeFor(MotionEvent.BUTTON_STYLUS_PRIMARY))
    }
}
