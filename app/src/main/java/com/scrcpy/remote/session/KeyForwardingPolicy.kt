package com.scrcpy.remote.session

import android.view.KeyEvent

/**
 * Decides which physical key presses are sent to the mirrored device.
 *
 * Forwarding is deliberately opt-out rather than opt-in: a hardware keyboard is used to drive the
 * remote device, so anything that is not explicitly reserved for this app belongs there. The
 * reserved set is kept small and is the same set the on-screen controls already own locally, so
 * the two input paths cannot disagree about who handles a key.
 *
 * Pure and free of Android state so the policy itself is unit-testable.
 */
object KeyForwardingPolicy {

    /**
     * Keys this app keeps.
     *
     * - Back matches the documented rule that the local system Back controls this app, and the
     *   panel's own Back button is what drives the device.
     * - Volume and mute act on this phone's playback of the remote audio stream, which is what a
     *   user pressing them while watching actually means.
     * - Power, sleep, wake and brightness would put the wrong device to sleep.
     * - Home and recents are consumed by this phone's system and never reach an app anyway;
     *   listing them documents that they are not silently dropped by this policy.
     */
    private val RESERVED = intSetOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_BRIGHTNESS_UP,
        KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
    )

    /** True when [keyCode] drives the mirrored device rather than this app. */
    fun forwards(keyCode: Int): Boolean = keyCode !in RESERVED

    /**
     * Whether a key event should reach the device at all.
     *
     * @param connected a session is live; there is nothing to send to otherwise.
     * @param typingLocally the on-screen draft field is open and holds focus, so the keystroke is
     * composing local text and must not also be injected remotely.
     */
    fun shouldForward(keyCode: Int, connected: Boolean, typingLocally: Boolean): Boolean =
        connected && !typingLocally && forwards(keyCode)

    private fun intSetOf(vararg values: Int): Set<Int> = values.toHashSet()
}
