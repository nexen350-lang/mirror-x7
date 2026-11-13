package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceDisplay
import com.scrcpy.remote.session.DeviceDisplaysState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDisplaysDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val displays = listOf(DeviceDisplay(0, 1080, 2400), DeviceDisplay(3, 720, 1600))

    @Test fun everyDisplayIsListedWithItsSizeAndTheCurrentOneIsMarked() {
        render(DeviceDisplaysState(displays = displays, loaded = true), selected = 3)
        compose.onNodeWithText(text(R.string.displays_primary, 0)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.displays_size, 1080, 2400)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.displays_secondary, 3)).assertIsSelected()
    }

    @Test fun choosingADisplayReportsItsId() {
        var chosen: Int? = null
        render(DeviceDisplaysState(displays = displays, loaded = true), onSelect = { chosen = it })
        compose.onNodeWithText(text(R.string.displays_secondary, 3)).performClick()
        compose.runOnIdle { assertEquals(3, chosen) }
    }

    @Test fun aDisplayWhoseSizeIsUnknownIsStillOffered() {
        // The server reports "size unknown" when it cannot measure one; hiding it would hide a
        // display that can still be mirrored.
        render(DeviceDisplaysState(displays = listOf(DeviceDisplay(2, 0, 0)), loaded = true))
        compose.onNodeWithText(text(R.string.displays_secondary, 2)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.displays_size_unknown)).assertIsDisplayed()
    }

    @Test fun aVirtualDisplayTakesPrecedenceAndSaysSo() {
        // The server refuses display_id and new_display together, so offering both would let the
        // user pick something that is then silently ignored.
        render(DeviceDisplaysState(displays = displays, loaded = true), virtualDisplayActive = true)
        compose.onNodeWithText(text(R.string.displays_virtual_active)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.displays_primary, 0)).assertIsNotEnabled()
    }

    @Test fun theProfileNoteTalksAboutDisplaysRatherThanEncoders() {
        // This dialog was built from the encoder one, and the encoder wording followed it here
        // until it was seen on a device.
        render(DeviceDisplaysState(displays = displays, loaded = true), createsProfile = true)
        val note = compose.activity.getString(R.string.displays_creates_profile)
        assertTrue(note, "display" in note.lowercase())
        assertFalse(note, "encoder" in note.lowercase())
        compose.onNodeWithText(note).assertIsDisplayed()
    }

    @Test fun aFailedListingIsRetryableWithoutLeavingTheSession() {
        var retries = 0
        render(DeviceDisplaysState(error = text(R.string.displays_list_failed)), onRetry = { retries++ })
        compose.onNodeWithText(text(R.string.displays_list_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun aSessionOnlyChoiceCanLeaveAVirtualDisplayWithoutChangingASavedProfile() {
        var chosen: Int? = null
        render(
            DeviceDisplaysState(displays = displays, loaded = true),
            virtualDisplayActive = true,
            sessionOnly = true,
            onSelect = { chosen = it },
        )
        compose.onNodeWithText(text(R.string.desktop_return_primary)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.displays_primary, 0)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, chosen) }
    }

    @Test fun aVirtualDisplayCannotOfferASecondaryIdThatMayDisappearOnReconnect() {
        render(
            DeviceDisplaysState(displays = displays, loaded = true),
            virtualDisplayActive = true,
            sessionOnly = true,
        )
        compose.onNodeWithTag("device-display-list")
            .performScrollToNode(hasText(text(R.string.displays_secondary, 3)))
        compose.onNodeWithText(text(R.string.displays_secondary, 3)).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun startingAnAdaptiveDesktopIsAnExplicitSessionAction() {
        var starts = 0
        render(
            DeviceDisplaysState(displays = displays, loaded = true),
            sessionOnly = true,
            onAdaptiveDesktop = { starts++ },
        )
        compose.onNodeWithText(text(R.string.desktop_start)).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun recordingPreventsDesktopRestartsAndPhysicalDisplaySwitches() {
        render(
            DeviceDisplaysState(displays = displays, loaded = true),
            sessionOnly = true,
            recording = true,
            adaptiveDesktopActive = true,
            onAdaptiveDesktop = {},
        )
        compose.onNodeWithText(text(R.string.desktop_recording_paused)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.desktop_restart)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("device-display-list")
            .performScrollToNode(hasText(text(R.string.displays_secondary, 3)))
        compose.onNodeWithText(text(R.string.displays_secondary, 3)).assertIsDisplayed().assertIsNotEnabled()
    }

    private fun render(
        state: DeviceDisplaysState,
        selected: Int = 0,
        onSelect: ((Int) -> Unit)? = {},
        createsProfile: Boolean = false,
        virtualDisplayActive: Boolean = false,
        onRetry: () -> Unit = {},
        sessionOnly: Boolean = false,
        recording: Boolean = false,
        adaptiveDesktopActive: Boolean = false,
        onAdaptiveDesktop: (() -> Unit)? = null,
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    DeviceDisplaysDialog(
                        state = state,
                        selected = selected,
                        onSelect = onSelect,
                        createsProfile = createsProfile,
                        virtualDisplayActive = virtualDisplayActive,
                        onRetry = onRetry,
                        onDismiss = {},
                        sessionOnly = sessionOnly,
                        recording = recording,
                        adaptiveDesktopActive = adaptiveDesktopActive,
                        onAdaptiveDesktop = onAdaptiveDesktop,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
