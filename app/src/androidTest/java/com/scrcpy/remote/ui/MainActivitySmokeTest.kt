package com.scrcpy.remote.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.UiModeManager
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherOpensAndSettingsCanBeSavedWithoutChangingValues() {
        compose.onNodeWithContentDescription(text(R.string.settings))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text(R.string.settings_title)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(text(R.string.add_device))
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun profileToolsExposeHistoryWithoutRestoringOrDeletingAnything() {
        compose.onNodeWithContentDescription(text(R.string.profile_tools_menu)).performClick()
        compose.onNodeWithText(text(R.string.history_title)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text(R.string.history_busy)).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(text(R.string.history_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.history_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.onNodeWithContentDescription(text(R.string.add_device)).assertIsDisplayed()
    }

    @Test
    fun changingAppearanceUpdatesSystemBarIconsWithoutRestartingTheScreen() {
        val manager = compose.activity.getSystemService(UiModeManager::class.java)
        val original = when (manager.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> "yes"
            UiModeManager.MODE_NIGHT_NO -> "no"
            UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
            else -> "auto"
        }
        fun changeMode(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd uimode night $mode"),
            ).use { it.readBytes() }
        }
        try {
            for (dark in listOf(true, false)) {
                changeMode(if (dark) "yes" else "no")
                compose.waitUntil(timeoutMillis = 10_000) {
                    val activity = compose.activity
                    val actualDark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    actualDark == dark && controller.isAppearanceLightStatusBars == !dark &&
                        controller.isAppearanceLightNavigationBars == !dark
                }
                compose.onNodeWithContentDescription(text(R.string.add_device)).assertIsDisplayed()
            }
        } finally {
            changeMode(original)
        }
    }

    @Test
    fun settingsCalibrationAndDismissRemainAccessibleInLandscape() {
        compose.runOnIdle {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().assertHasClickAction()

        val label = text(R.string.setting_horizontal_offset)
        val increaseDescription = compose.activity.getString(R.string.control_plus_one, label)
        val decreaseDescription = compose.activity.getString(R.string.control_minus_one, label)
        compose.onNodeWithContentDescription(increaseDescription)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithContentDescription(decreaseDescription)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        // The slider carries its own name. The label beside it is a separate node, so without
        // this a screen reader says only that something is adjustable, not which axis it moves.
        compose.onNodeWithContentDescription(label)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_edge_gestures))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()

        // Dismiss the draft so this test is also safe on a developer's existing installation.
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}
