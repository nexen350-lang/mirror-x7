package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputLockControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun persistentUnlockAndExitRemainUsableAt240DpAndDoubleTextSize() {
        val locked = mutableStateOf(true)
        var exits = 0
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(Modifier.requiredSize(240.dp, 360.dp).testTag("locked-window")) {
                        FloatingControlsOverlay(actions().copy(inputLocked = locked.value,
                            onInputLockedChange = { locked.value = it }, onExit = { exits++ }),
                            false, Modifier.fillMaxSize())
                    }
                }
            }
        }
        val banner = compose.onNodeWithTag("input-lock-banner").assertIsDisplayed().assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val window = compose.onNodeWithTag("locked-window").fetchSemanticsNode().boundsInRoot
        assertTrue(banner.left >= window.left - 1 && banner.right <= window.right + 1)
        showCompact()
        compose.onNodeWithContentDescription(text(R.string.control_back)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.control_home)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.control_recents)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.control_exit)).assertIsDisplayed()
            .assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, exits); assertTrue(locked.value) }
        compose.onNodeWithTag("input-lock-banner").performClick()
        compose.runOnIdle { assertFalse(locked.value) }
        compose.onNodeWithTag("input-lock-banner").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.control_back)).assertIsEnabled()
    }

    @Test fun lockingClosesTheDraftWithoutSendingItAndUnlockDoesNotDiscardItsText() {
        val locked = mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions().copy(inputLocked = locked.value,
                    onInputLockedChange = { locked.value = it }, onText = { sent += it; true }),
                    false, Modifier.requiredSize(320.dp, 560.dp))
            }
        }
        showTools()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_keyboard_draft)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo()
            .performTextReplacement("Keep this private draft")
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText(text(R.string.ui_text_draft)).assertDoesNotExist()
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("input-lock-banner").performClick()
        showTools()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo()
            .assertTextContains("Keep this private draft")
    }

    @Test fun remotePanelActionsDisableButPlaybackCaptureAndFileToolsStayEnabled() {
        render(actions().copy(inputLocked = true, viewZoom = 2f))
        showTools()
        val blocked = listOf(R.string.control_back, R.string.control_home, R.string.control_recents,
            R.string.control_notifications, R.string.control_quick_settings, R.string.control_collapse_panels,
            R.string.control_apps, R.string.control_rotate, R.string.control_power,
            R.string.control_device_screen_off, R.string.control_volume_up, R.string.control_volume_down,
            R.string.control_keyboard, R.string.control_copy, R.string.control_cut, R.string.control_paste)
        blocked.forEach { id ->
            val node = compose.onNodeWithContentDescription(text(id))
            if (id !in listOf(R.string.control_back, R.string.control_home, R.string.control_recents)) {
                node.performScrollTo()
            }
            node.assertIsNotEnabled()
        }
        val available = listOf(R.string.view_zoom_in, R.string.view_zoom_out, R.string.view_zoom_reset,
            R.string.control_toggle_audio, R.string.capture_screenshot, R.string.capture_share_screenshot,
            R.string.recording_action_start, R.string.control_send_file, R.string.details_action)
        available.forEach { id ->
            compose.onNodeWithContentDescription(text(id)).performScrollTo().assertIsEnabled()
        }
    }

    @Test fun aLateSendAfterLockingPreservesTheDraftUntilAnExplicitRetry() {
        val locked = mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions().copy(inputLocked = locked.value,
                    onInputLockedChange = { locked.value = it },
                    onText = { text -> if (locked.value) false else { sent += text; true } }),
                    false, Modifier.requiredSize(320.dp, 560.dp))
            }
        }
        showTools()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_keyboard_draft)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo()
            .performTextReplacement("Retain after a late send")
        val submit = compose.onNodeWithText(text(R.string.ui_send_text)).performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action
        compose.runOnUiThread {
            locked.value = true
            requireNotNull(submit).invoke()
        }
        compose.onNodeWithTag("input-lock-banner").assertIsDisplayed().performClick()
        showTools()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo()
            .assertTextContains("Retain after a late send")
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithText(text(R.string.ui_send_text)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("Retain after a late send"), sent) }
    }

    @Test fun cameraControlsHonorTheSameLockWhileNativePipAndRecordingStayAvailable() {
        render(actions().copy(inputLocked = true, cameraSession = true, onCameraPip = {}))
        showTools()
        listOf(R.string.camera_torch_on, R.string.camera_zoom_in, R.string.camera_zoom_out).forEach { id ->
            compose.onNodeWithContentDescription(text(id)).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithContentDescription(text(R.string.camera_pip_action)).performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription(text(R.string.recording_action_start)).performScrollTo().assertIsEnabled()
    }

    @Test fun lockingIsOneExplicitTileWithoutAParagraphAndTheSameTileUnlocks() {
        val locked = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions().copy(inputLocked = locked.value,
                    onInputLockedChange = { locked.value = it }), false,
                    Modifier.requiredSize(320.dp, 560.dp))
            }
        }
        compose.onNodeWithTag("input-lock-banner").assertDoesNotExist()
        showTools()
        compose.onAllNodesWithText("not restricted ADB access", substring = true).assertCountEquals(0)
        compose.runOnIdle { assertFalse(locked.value) }
        compose.onNodeWithContentDescription(text(R.string.input_lock_action)).performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        compose.onNodeWithTag("input-lock-banner").assertIsDisplayed()
        compose.runOnIdle { assertTrue(locked.value) }
        // Opened again while locked, the same tile reads Unlock and says the input is locked.
        showTools()
        compose.onNodeWithContentDescription(text(R.string.input_unlock_action)).performScrollTo()
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.input_locked)))
            .performClick()
        compose.runOnIdle { assertFalse(locked.value) }
        compose.onNodeWithTag("input-lock-banner").assertDoesNotExist()
    }

    @Test fun sessionNoticesSitBelowThePersistentUnlockAt240DpAndDoubleTextSize() {
        val snackbar = SnackbarHostState()
        val placement = InputLockBannerPlacement()
        val fixed = mutableStateOf(false)
        val locked = mutableStateOf(true)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(Modifier.requiredSize(240.dp, 360.dp)) {
                        FloatingControlsOverlay(actions().copy(inputLocked = locked.value,
                            onInputLockedChange = { locked.value = it }), false, Modifier.fillMaxSize(),
                            inputLockBanner = placement)
                        if (fixed.value) {
                            SessionSnackbarHost(snackbar, locked.value, placement, Modifier.align(Alignment.TopCenter))
                        } else {
                            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).testTag("plain-notices"))
                        }
                    }
                }
            }
        }
        val message = text(R.string.recording_started)
        compose.activity.lifecycleScope.launch {
            snackbar.showSnackbar(message, duration = SnackbarDuration.Indefinite)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        val banner = compose.onNodeWithTag("input-lock-banner").fetchSemanticsNode().boundsInRoot
        // The fixture is meaningful only if a notice in the old top-centre slot covers the banner.
        val plain = compose.onNodeWithTag("plain-notices").fetchSemanticsNode().boundsInRoot
        assertTrue("$plain should cover $banner in the old arrangement", plain.overlaps(banner))

        compose.runOnIdle { fixed.value = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("session-notices").fetchSemanticsNodes().isNotEmpty() }
        val notice = compose.onNodeWithTag("session-notices").fetchSemanticsNode().boundsInRoot
        assertTrue("$notice must sit below $banner", notice.top >= banner.bottom - 1f)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("input-lock-banner").performClick()
        compose.runOnIdle { assertFalse(locked.value) }
    }

    private fun render(actions: MirrorActions) {
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions, false, Modifier.requiredSize(320.dp, 640.dp))
            }
        }
    }

    private fun showCompact() = compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
    private fun showTools() {
        showCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
}
