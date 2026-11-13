package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingControlsSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun exitRemainsVisibleWhileCalibrationScrollsOnNarrowLargeTextScreen() {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                        Box(Modifier.requiredSize(240.dp, 320.dp)) {
                            FloatingControlsOverlay(actions(), muted = false, Modifier.requiredSize(240.dp, 320.dp))
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.calibrate)).performScrollTo().performClick()
        val horizontal = text(R.string.setting_horizontal_offset)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.control_plus_ten, horizontal))
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(R.string.hide)).assertIsDisplayed()
    }

    @Test fun localBackHidesControlsWithoutSendingRemoteBack() {
        var remoteBack = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    FloatingControlsOverlay(actions(onBack = { remoteBack++ }), muted = false,
                        Modifier.requiredSize(300.dp, 480.dp))
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.exit)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, remoteBack) }
    }

    @Test fun deviceCalibrationScopeAndDefaultResetAreAccessible() {
        var inherited = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    FloatingControlsOverlay(
                        actions().copy(calibrationScope = "Test tablet / remote portrait",
                            calibrationOverridesDefaults = true, onUseGlobalCalibration = { inherited = true }),
                        muted = false, Modifier.requiredSize(300.dp, 480.dp),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithContentDescription(text(R.string.calibrate)).performScrollTo().performClick()
        compose.onNodeWithText("Test tablet / remote portrait").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.profile_use_defaults)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(inherited) }
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun actions(onBack: () -> Unit = {}) = MirrorActions(
        onBack, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {},
    )
}
