package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.DeviceEncodersState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceEncodersDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val encoders = listOf(
        DeviceEncoder("h264", "c2.android.avc.encoder", "sw"),
        DeviceEncoder("h264", "c2.qti.avc.encoder", "hw", vendor = true),
        DeviceEncoder("h265", "c2.android.hevc.encoder", "sw"),
    )

    @Test fun theEncoderInUseAnnouncesItsSelectedStateOnTheRowItself() {
        // "In use" used to live on a sibling check icon, so a screen reader announced the encoder
        // actually in use exactly like every other one unless the user traversed past that icon.
        render(
            DeviceEncodersState(encoders = encoders, loaded = true),
            selected = "c2.qti.avc.encoder",
        )
        compose.onNodeWithTag("device-encoder-list")
            .performScrollToNode(hasText("c2.qti.avc.encoder"))
        compose.onNodeWithText("c2.qti.avc.encoder").assertIsSelected()
        compose.onNodeWithText("c2.android.avc.encoder").assertIsNotSelected()
    }

    @Test fun onlyEncodersForTheConfiguredCodecCanBeChosen() {
        // The device rejects a mismatched pair with "Incorrect encoder type", which the user
        // would only ever see as a connection that stopped working. Offering an H.265 encoder
        // while the session is configured for H.264 has to be impossible, not merely discouraged.
        render(DeviceEncodersState(encoders = encoders, loaded = true), codec = VideoCodec.H264)
        compose.onNodeWithText("c2.android.avc.encoder").assertIsDisplayed()
        compose.onNodeWithText("c2.android.hevc.encoder").assertDoesNotExist()

        render(DeviceEncodersState(encoders = encoders, loaded = true), codec = VideoCodec.H265)
        compose.onNodeWithText("c2.android.hevc.encoder").assertIsDisplayed()
        compose.onNodeWithText("c2.android.avc.encoder").assertDoesNotExist()
    }

    @Test fun aCodecWithNoEncodersSaysSoAndStillOffersTheDefault() {
        render(DeviceEncodersState(encoders = encoders, loaded = true), codec = VideoCodec.AV1)
        compose.onNodeWithText(
            compose.activity.getString(R.string.encoders_empty_for_codec, VideoCodec.AV1.label)
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoders_default)).assertIsDisplayed()
    }

    @Test fun theDeviceDefaultIsAlwaysOfferedSoAChoiceCanBeUndone() {
        // Picking a worse encoder must not be a one-way door.
        var chosen: String? = "c2.qti.avc.encoder"
        var selections = 0
        render(
            DeviceEncodersState(encoders = encoders, loaded = true),
            selected = "c2.qti.avc.encoder",
            onSelect = { chosen = it; selections++ },
        )
        compose.onNodeWithText(text(R.string.encoders_default)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertNull(chosen)
        }
    }

    @Test fun eachEncoderCanBeChosenByName() {
        var chosen: String? = null
        render(
            DeviceEncodersState(encoders = encoders, loaded = true),
            onSelect = { chosen = it },
        )
        compose.onNodeWithTag("device-encoder-list")
            .performScrollToNode(hasText("c2.qti.avc.encoder"))
        compose.onNodeWithText("c2.qti.avc.encoder").performClick()
        compose.runOnIdle { assertEquals("c2.qti.avc.encoder", chosen) }
    }

    @Test fun aDeviceStillOnTheDefaultsIsToldThatChoosingGivesItItsOwnSettings() {
        // Silently converting a device to a custom profile would surprise the user later, when
        // changes to the global settings stopped reaching it.
        render(DeviceEncodersState(encoders = encoders, loaded = true), createsProfile = true)
        compose.onNodeWithText(text(R.string.encoders_creates_profile)).assertIsDisplayed()

        render(DeviceEncodersState(encoders = encoders, loaded = true), createsProfile = false)
        compose.onNodeWithText(text(R.string.encoders_creates_profile)).assertDoesNotExist()
    }

    @Test fun withNoSavedDeviceAnEncoderStillAppliesToTheSession() {
        // There is nowhere to remember the choice, which used to mean the list was shown greyed
        // out and the user could read the encoders but not pick one. The session in front of them
        // can still use it, so it is offered and the note says what it costs.
        var chosen: String? = "unset"
        render(DeviceEncodersState(encoders = encoders, loaded = true), sessionOnly = true,
            onSelect = { chosen = it })
        compose.onNodeWithText(text(R.string.encoders_session_only)).assertIsDisplayed()
        compose.onNodeWithText("c2.android.avc.encoder").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("c2.android.avc.encoder", chosen) }
    }

    @Test fun aSavedDeviceIsNotToldAboutSessionOnlyChoices() {
        render(DeviceEncodersState(encoders = encoders, loaded = true), sessionOnly = false)
        compose.onNodeWithText(text(R.string.encoders_session_only)).assertDoesNotExist()
    }

    @Test fun aFailedListingIsRetryableWithoutLeavingTheSession() {
        var retries = 0
        render(DeviceEncodersState(error = text(R.string.encoders_list_failed)), onRetry = { retries++ })
        compose.onNodeWithText(text(R.string.encoders_list_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.apps_retry)).assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun progressIsShownWithoutOfferingAHalfLoadedList() {
        render(DeviceEncodersState(loading = true))
        compose.onNodeWithText(text(R.string.encoders_loading)).assertIsDisplayed()
        compose.onNodeWithTag("device-encoder-list").assertDoesNotExist()
    }

    @Test fun aDeviceReportingNoEncodersSaysSoAndStillOffersTheDefault() {
        render(DeviceEncodersState(encoders = emptyList(), loaded = true))
        compose.onNodeWithText(
            compose.activity.getString(R.string.encoders_empty_for_codec, VideoCodec.H264.label)
        ).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.encoders_default)).assertIsDisplayed()
    }

    @Test fun theHelpExplainsWhyAnyoneWouldChangeThis() {
        // The whole feature only matters to someone whose mirroring is broken; the dialog has to
        // say so, because an encoder name means nothing on its own.
        render(DeviceEncodersState(encoders = encoders, loaded = true))
        val help = compose.activity.getString(R.string.encoders_help_for_codec, VideoCodec.H264.label)
        assertTrue(help, "fails" in help || "corrupt" in help)
        compose.onNodeWithText(help).assertIsDisplayed()
    }

    @Test fun theAudioListOffersOnlyEncodersForTheConfiguredAudioCodec() {
        // Same rule as video: the server refuses an encoder whose type does not match the codec,
        // and a user would only ever see that as a connection that stopped working.
        val audio = listOf(
            DeviceEncoder("opus", "c2.android.opus.encoder", "sw"),
            DeviceEncoder("aac", "c2.android.aac.encoder", "sw"),
        )
        renderAudio(DeviceEncodersState(encoders = audio, loaded = true), codec = AudioCodec.OPUS)
        compose.onNodeWithText("c2.android.opus.encoder").assertIsDisplayed()
        compose.onNodeWithText("c2.android.aac.encoder").assertDoesNotExist()

        renderAudio(DeviceEncodersState(encoders = audio, loaded = true), codec = AudioCodec.AAC)
        compose.onNodeWithText("c2.android.aac.encoder").assertIsDisplayed()
        compose.onNodeWithText("c2.android.opus.encoder").assertDoesNotExist()
    }

    @Test fun theAudioListCanBeReturnedToTheDeviceDefault() {
        var chosen: String? = "c2.android.opus.encoder"
        var selections = 0
        renderAudio(
            DeviceEncodersState(encoders = listOf(DeviceEncoder("opus", "c2.android.opus.encoder", "sw")), loaded = true),
            selected = "c2.android.opus.encoder",
            onSelect = { chosen = it; selections++ },
        )
        compose.onNodeWithText(text(R.string.encoders_default)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, selections)
            assertNull(chosen)
        }
    }

    @Test fun theAudioHelpTalksAboutAudioRatherThanThePicture() {
        // The two dialogs share a body, so the video wording followed the audio list here until
        // it was seen on a device: "the picture is corrupt" says nothing about an audio encoder.
        renderAudio(DeviceEncodersState(encoders = emptyList(), loaded = true))
        val help = compose.activity.getString(R.string.audio_encoders_help_for_codec, AudioCodec.OPUS.label)
        assertTrue(help, "audio" in help.lowercase())
        assertFalse(help, "picture" in help.lowercase())
        compose.onNodeWithText(help).assertIsDisplayed()
    }

    @Test fun theAudioListIsReachedFromTheVideoDialogRatherThanASecondPanelButton() {
        // The tools row is already full on a small screen; an eighth button there pushed the
        // controls above it out of reach, so the two lists share one entry point instead.
        var switched = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    DeviceEncodersDialog(
                        state = DeviceEncodersState(encoders = encoders, loaded = true),
                        selected = null,
                        codec = VideoCodec.H264,
                        onSelect = {},
                        createsProfile = false,
                        sessionOnly = false,
                        onRetry = {},
                        onDismiss = {},
                        onSwitch = { switched++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.audio_encoders_title))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, switched) }
    }

    private fun renderAudio(
        state: DeviceEncodersState,
        selected: String? = null,
        codec: AudioCodec = AudioCodec.OPUS,
        onSelect: (String?) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    DeviceAudioEncodersDialog(
                        state = state,
                        selected = selected,
                        codec = codec,
                        onSelect = onSelect,
                        createsProfile = false,
                        sessionOnly = false,
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun render(
        state: DeviceEncodersState,
        selected: String? = null,
        codec: VideoCodec = VideoCodec.H264,
        onSelect: (String?) -> Unit = {},
        createsProfile: Boolean = false,
        sessionOnly: Boolean = false,
        onRetry: () -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    DeviceEncodersDialog(
                        state = state,
                        selected = selected,
                        codec = codec,
                        onSelect = onSelect,
                        createsProfile = createsProfile,
                        sessionOnly = sessionOnly,
                        onRetry = onRetry,
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
