package com.scrcpy.remote.ui

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two mirroring-settings dialogs: they open at the top with nothing focused, and Save waits
 * until the draft is one the app can store.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDialogsTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun globalSettingsOpenAtTheTopWithNothingFocusedAndFieldsStillTakeTyping() {
        show {
            SettingsDialog(MirrorSettings(startApp = "com.example.app"), onDismiss = {}, onSave = { _, _ -> })
        }
        awaitDialogFocus()
        assertInTouchMode(compose.activity)
        compose.onNode(hasScrollAction()).assert(scrolledToTop())
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        // A tap still focuses a field for typing.
        compose.onNodeWithText(text(R.string.setting_start_app_field)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_start_app_field)).assertIsFocused()
    }

    @Test fun deviceSettingsOpenAtTheTopWithNothingFocused() {
        show {
            DeviceSettingsDialog(device(MirrorSettings(startApp = "com.example.app")), MirrorSettings(),
                onDismiss = {}, onSave = { _, _ -> })
        }
        awaitDialogFocus()
        assertInTouchMode(compose.activity)
        compose.onNode(hasScrollAction()).assert(scrolledToTop())
        compose.onAllNodes(isFocused()).assertCountEquals(0)
    }

    @Test fun globalSaveWaitsForADraftTheAppCanStore() {
        var saves = 0
        show { SettingsDialog(MirrorSettings(startApp = ""), onDismiss = {}, onSave = { _, _ -> saves++ }) }
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.setting_start_app_field)).performScrollTo()
            .performTextReplacement("com.example.app")
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun deviceSaveWaitsForACustomDraftTheAppCanStore() {
        var saves = 0
        show {
            DeviceSettingsDialog(device(MirrorSettings(startApp = "")), MirrorSettings(),
                onDismiss = {}, onSave = { _, _ -> saves++ })
        }
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.setting_start_app_field)).performScrollTo()
            .performTextReplacement("com.example.app")
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun theSoundCodecSaysWhichSoundARecordingCanKeep() {
        show { SettingsDialog(MirrorSettings(audioEnabled = true), onDismiss = {}, onSave = { _, _ -> }) }
        compose.onNodeWithText(text(R.string.audio_codec_recording_hint)).performScrollTo().assertIsDisplayed()
    }

    /** Shown in a short window, so the dialog's sliders start below the fold. */
    private fun show(dialog: @Composable () -> Unit) {
        val configuration = Configuration(compose.activity.resources.configuration).apply { screenHeightDp = 420 }
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalConfiguration provides configuration) { dialog() }
                }
            }
        }
    }

    /** Initial focus is handed out when the dialog's window gains focus, which the activity loses. */
    private fun awaitDialogFocus() {
        compose.waitUntil(10_000) {
            var activityFocused = true
            compose.runOnUiThread { activityFocused = compose.activity.hasWindowFocus() }
            !activityFocused
        }
        compose.waitForIdle()
    }

    private fun scrolledToTop() = SemanticsMatcher("scrolled to the top") {
        it.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null }?.value?.invoke() == 0f
    }

    private fun device(custom: MirrorSettings) = SavedDevice(name = "Test tablet", host = "192.0.2.10",
        mirroringSettings = DeviceMirroringSettings.from(custom))

    private fun text(id: Int) = compose.activity.getString(id)
}
