package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraPipControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cameraPipIsAnExplicitAccessibleActionOnANarrowLargeTextScreen() {
        var entered = 0
        show(actions().copy(cameraSession = true, onCameraPip = { entered++ }))
        openTools()
        compose.runOnIdle { assertEquals("Opening controls must not enter PiP", 0, entered) }
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action))
            .performScrollTo().assertIsDisplayed().assertHasClickAction().assertIsEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, entered) }
    }

    @Test fun screenMirroringNeverOffersTheCameraActionEvenIfACallbackWasSupplied() {
        show(actions().copy(onCameraPip = { error("A screen must never enter camera PiP") }))
        openTools()
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action)).assertDoesNotExist()
    }

    @Test fun camerasOnDevicesWithoutPipDoNotOfferADeadAction() {
        show(actions().copy(cameraSession = true))
        openTools()
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action)).assertDoesNotExist()
    }

    @Test fun busyCameraExplainsTheRestrictionAndRecoversWhenWorkFinishes() {
        var entered = 0
        val busy = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(300.dp, 540.dp)) {
                        FloatingControlsOverlay(
                            actions().copy(cameraSession = true, cameraPipBlocked = busy.value,
                                onCameraPip = { entered++ }),
                            muted = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        openTools()
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action))
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.camera_pip_busy)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, entered)
            busy.value = false
        }
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action))
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, entered) }
        compose.onNodeWithText(text(R.string.camera_pip_busy)).assertDoesNotExist()
    }

    @Test fun cameraCompactStripOmitsRemoteKeysButKeepsEndMoreAndRecordingStop() {
        val camera = mutableStateOf(true)
        val recording = mutableStateOf(false)
        var remoteKeys = 0
        var exits = 0
        var stops = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    FloatingControlsOverlay(
                        actions().copy(
                            cameraSession = camera.value,
                            recording = recording.value,
                            onBack = { remoteKeys++ },
                            onHome = { remoteKeys++ },
                            onRecents = { remoteKeys++ },
                            onExit = { exits++ },
                            onToggleRecording = { stops++; recording.value = false },
                        ),
                        muted = false,
                        modifier = Modifier.requiredSize(320.dp, 540.dp),
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        for (isRecording in listOf(false, true)) {
            compose.runOnIdle { recording.value = isRecording }
            for (label in listOf(R.string.control_back, R.string.control_home, R.string.control_recents)) {
                compose.onNodeWithContentDescription(text(label)).assertDoesNotExist()
            }
            for (label in listOf(R.string.control_exit, R.string.compact_more_controls)) {
                compose.onNodeWithContentDescription(text(label)).assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
            compose.onNodeWithTag("compact-navigation")
                .assertWidthIsEqualTo(compactBarLength(if (isRecording) 3 else 2))
            if (isRecording) {
                compose.onNodeWithContentDescription(text(R.string.recording_action_stop))
                    .assertIsDisplayed().assertHasClickAction().performClick()
            } else {
                compose.onNodeWithContentDescription(text(R.string.recording_action_stop)).assertDoesNotExist()
            }
        }
        compose.onNodeWithContentDescription(text(R.string.control_exit)).performClick()
        compose.runOnIdle {
            assertEquals(0, remoteKeys)
            assertEquals(1, stops)
            assertEquals(1, exits)
            camera.value = false
        }
        // Gesture/cutout insets may make the unchanged five-button screen strip vertical.
        val screenStrip = compose.onNodeWithTag("compact-navigation").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(compactBarLongSide.value * density,
            maxOf(screenStrip.width, screenStrip.height), 1f)
        assertEquals(compactBarShortSide.value * density,
            minOf(screenStrip.width, screenStrip.height), 1f)
        for (label in listOf(R.string.control_back, R.string.control_home, R.string.control_recents)) {
            compose.onNodeWithContentDescription(text(label)).assertIsDisplayed().performClick()
        }
        compose.runOnIdle {
            assertEquals("Ordinary mirrored-screen navigation remains unchanged", 3, remoteKeys)
            camera.value = true
        }
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
    }

    private fun show(actions: MirrorActions) {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                        Box(Modifier.requiredSize(240.dp, 480.dp)) {
                            FloatingControlsOverlay(actions, muted = false, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private fun openTools() {
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
}
