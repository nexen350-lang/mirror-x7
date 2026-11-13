package com.scrcpy.remote.ui

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scrcpy.remote.R

/**
 * Presses Search in the device list's search field once the keyboard is up, as a user does.
 *
 * Pressed sooner, Search cleared the field's focus before the window had taken the keyboard's own
 * show. Android 13 then dropped the window's hide, and brought the keyboard back for nothing when
 * input restarted (ATTACH_NEW_INPUT), where it stayed. Window insets report the keyboard from API 30.
 */
internal fun AndroidComposeTestRule<*, out ComponentActivity>.pressDeviceSearch() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        waitUntil(conditionDescription = "the keyboard is up before Search", timeoutMillis = 10_000) {
            keyboardShown(activity)
        }
    }
    onNodeWithText(activity.getString(R.string.device_list_search)).performImeAction()
}

private fun keyboardShown(activity: Activity): Boolean =
    ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
