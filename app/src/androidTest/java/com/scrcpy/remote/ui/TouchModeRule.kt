package com.scrcpy.remote.ui

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource

/**
 * Starts a test in touch mode, as a finger leaves the device.
 *
 * A key event sent earlier, by another test or by adb, takes the whole device out of touch mode on
 * Android 8, and the dialogs then rightly give their first control focus, as they do for a
 * keyboard user. Tests of what a touch user sees order this rule before the one that launches the
 * activity, so the activity's window opens in touch mode. A test that presses keys itself leaves
 * touch mode behind for the next, so it is restored afterwards as well.
 */
class TouchModeRule : ExternalResource() {
    override fun before() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
    }
}

/** Fails as what it is, rather than as a focused field that looks like a defect. */
fun assertInTouchMode(activity: Activity) {
    var inTouchMode = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync { inTouchMode = activity.window.decorView.isInTouchMode }
    assertTrue("${activity.localClassName}'s window is in touch mode, as a finger leaves it", inTouchMode)
}
