package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.DeviceEncodersState
import com.scrcpy.remote.session.StreamChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * More controls > Picture & sound: the session's codecs and server, changed together and applied
 * with one reconnect, for this session only.
 */
@RunWith(AndroidJUnit4::class)
class StreamChoicesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val running = StreamChoice(VideoCodec.H264, AudioCodec.OPUS, ScrcpyServerVersion.V5_0)
    private val device = DeviceEncodersState(loaded = true, encoders = listOf(
        DeviceEncoder("h264", "c2.android.avc.encoder"), DeviceEncoder("h265", "c2.android.hevc.encoder"),
        DeviceEncoder("opus", "c2.android.opus.encoder"), DeviceEncoder("aac", "c2.android.aac.encoder")))

    @Test fun nothingIsAppliedUntilSomethingChangesAndThenAllOfItAtOnce() {
        val applied = mutableListOf<StreamChoice>()
        show(onApply = { applied += it })
        compose.onNodeWithText(text(R.string.stream_choices_running,
            text(R.string.stream_choice_summary, "H.264", "Opus", "5.0.1"))).assertIsDisplayed()
        // Selecting the running values performs no restart.
        compose.onNodeWithText(text(R.string.stream_choices_apply)).assertIsNotEnabled()
        compose.onNodeWithText(VideoCodec.H265.label, substring = true).performScrollTo().performClick()
        compose.onNodeWithText(AudioCodec.AAC.label, substring = true).performScrollTo().performClick()
        compose.onNodeWithText("scrcpy server", ignoreCase = true).assertDoesNotExist()
        compose.onNode(hasText("scrcpy 5.0.1", substring = true) and isSelectable()).assertDoesNotExist()
        compose.onNodeWithText("scrcpy 4.1", substring = true).assertDoesNotExist()
        // Changing a selection alone does not interrupt anything.
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText(text(R.string.stream_choices_apply)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(StreamChoice(VideoCodec.H265, AudioCodec.AAC, ScrcpyServerVersion.DEFAULT)), applied)
        }
    }

    @Test fun aCodecTheDeviceLacksCannotBeChosen() {
        show(videoEncoders = device, audioEncoders = device)
        compose.onNode(hasText(VideoCodec.VP9.label, substring = true) and
            hasText(text(R.string.codec_support_not_on_device), substring = true)).performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText(VideoCodec.H265.label, substring = true) and
            hasText(text(R.string.codec_support_on_device), substring = true)).performScrollTo().assertIsEnabled()
        compose.onNode(hasText(VideoCodec.H264.label, substring = true) and
            hasText(text(R.string.codec_support_running), substring = true)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aCodecChosenBeforeTheListsArriveGoesBackWhenTheyShowItMissing() {
        var lists by mutableStateOf(DeviceEncodersState(loading = true))
        val applied = mutableListOf<StreamChoice>()
        content {
            StreamChoicesDialog(active = running, audioEnabled = true, videoEncoders = lists, audioEncoders = lists,
                recording = false, onApply = { applied += it }, onCheckAgain = {}, onDismiss = {},
                supportedVideoCodecs = VideoCodec.entries, supportedAudioCodecs = AudioCodec.entries)
        }
        // While the lists load nothing is known, so every codec can be tapped.
        compose.onNodeWithText(VideoCodec.AV1.label, substring = true).performScrollTo().performClick()
        compose.onNodeWithText(AudioCodec.FLAC.label, substring = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.stream_choices_apply)).assertIsEnabled()
        // The lists then show the device has neither encoder: the sheet must not apply them.
        compose.runOnUiThread { lists = device }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.stream_choices_apply)).assertIsNotEnabled()
        compose.onNode(hasText(VideoCodec.H264.label, substring = true) and isSelected()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(AudioCodec.OPUS.label, substring = true) and isSelected()).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun anUncheckedDeviceIsSaidToBeUnchecked() {
        var checks = 0
        show(videoEncoders = DeviceEncodersState(error = "No answer"), audioEncoders = DeviceEncodersState(error = "No answer"),
            onCheckAgain = { checks++ })
        compose.onNodeWithText(text(R.string.stream_choices_unchecked)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.codec_support_not_checked), substring = true).fetchSemanticsNodes()
            .let { assertTrue("Every unlisted codec says so", it.size >= 4) }
        compose.onNodeWithText(text(R.string.stream_choices_check_again)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun aRecordingIsWarnedAboutBeforeApplying() {
        show(recording = true)
        compose.onNodeWithText(text(R.string.stream_choices_recording)).performScrollTo().assertIsDisplayed()
    }

    @Test fun withSoundOffThereIsNoSoundCodecToChoose() {
        show(audioEnabled = false)
        compose.onNodeWithText(text(R.string.stream_choices_audio_off)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(AudioCodec.FLAC.label, substring = true).fetchSemanticsNodes()
            .let { assertTrue(it.isEmpty()) }
    }

    @Test fun aFailedSwitchOffersTheSettingsThatWorked() {
        var reverted = 0
        val label = text(R.string.stream_choices_revert, text(R.string.stream_choice_summary, "H.264", "Opus", "5.0.1"))
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    ConnectionStatus(message = "Could not find any encoder for av1", connecting = false,
                        onDismiss = {}, revertLabel = label, onRevert = { reverted++ })
                }
            }
        }
        // Beside Retry in the screen's fixed actions, not in its scrolling body.
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, reverted) }
    }

    private fun show(
        videoEncoders: DeviceEncodersState = DeviceEncodersState(),
        audioEncoders: DeviceEncodersState = DeviceEncodersState(),
        recording: Boolean = false,
        audioEnabled: Boolean = true,
        onApply: (StreamChoice) -> Unit = {},
        onCheckAgain: () -> Unit = {},
    ) = content {
        StreamChoicesDialog(active = running, audioEnabled = audioEnabled, videoEncoders = videoEncoders,
            audioEncoders = audioEncoders, recording = recording, onApply = onApply, onCheckAgain = onCheckAgain,
            onDismiss = {}, supportedVideoCodecs = VideoCodec.entries, supportedAudioCodecs = AudioCodec.entries)
    }

    private fun content(block: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { ScrcpyRemoteTheme(darkTheme = false) { block() } } }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
