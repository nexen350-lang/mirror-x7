package com.scrcpy.remote.session

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Maps non-primary mouse buttons to the navigation they stand for on the mirrored device.
 *
 * The primary button is deliberately absent: it is an ordinary pointer press and belongs to the
 * touch path, not to navigation. The mapping follows what scrcpy's desktop client does, so a user
 * who already drives scrcpy with a mouse finds the same behaviour here.
 *
 * Pure so the mapping is unit-testable without an input device.
 */
object MouseButtonPolicy {

    /** Whether the synthesized Back/Forward key is a duplicate of a press that was forwarded. */
    fun consumesKey(connected: Boolean, cameraSession: Boolean, visible: Boolean): Boolean =
        connected && !cameraSession && visible

    /** The keycode [actionButton] stands for, or null when the button drives the pointer. */
    fun keycodeFor(actionButton: Int): Int? = when (actionButton) {
        MotionEvent.BUTTON_SECONDARY -> KeyEvent.KEYCODE_BACK
        MotionEvent.BUTTON_TERTIARY -> KeyEvent.KEYCODE_HOME
        MotionEvent.BUTTON_BACK -> KeyEvent.KEYCODE_BACK
        MotionEvent.BUTTON_FORWARD -> KeyEvent.KEYCODE_APP_SWITCH
        else -> null
    }
}
