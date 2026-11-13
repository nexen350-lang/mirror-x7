package com.scrcpy.remote.ui

import android.media.MediaFormat
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.session.EncodedMedia
import com.scrcpy.remote.session.RecordingStatus
import com.scrcpy.remote.session.SessionRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { ScrcpyRemoteTheme { content() } } }
        compose.waitForIdle()
    }

    @Test fun theIndicatorSaysHowLongAndWhetherThereIsSound() {
        var now = 1_000_000L
        val started = now - 65_000
        show {
            Box(Modifier.requiredSize(320.dp, 640.dp)) {
                RecordingIndicator(RecordingStatus(started, withSound = true), false, InputLockBannerPlacement(),
                    clock = { now })
            }
        }
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.recording_indicator_with_sound, DateUtils.formatElapsedTime(65)))
            .assertIsDisplayed()
        // It keeps counting, a second at a time.
        now += 1_000
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.recording_indicator_with_sound, DateUtils.formatElapsedTime(66)))
            .assertIsDisplayed()
    }

    @Test fun aPictureOnlyRecordingSaysSo() {
        show {
            RecordingIndicator(RecordingStatus(SystemClock.elapsedRealtime(), withSound = false), false,
                InputLockBannerPlacement())
        }
        compose.onNodeWithContentDescription(compose.activity.getString(
            R.string.recording_indicator_picture_only, DateUtils.formatElapsedTime(0))).assertIsDisplayed()
    }

    @Test fun touchesOnTheIndicatorStillReachTheScreenUnderIt() {
        var touched = 0
        show {
            Box(Modifier.requiredSize(320.dp, 640.dp)) {
                Box(Modifier.fillMaxSize().testTag("screen").clickable { touched++ })
                RecordingIndicator(RecordingStatus(SystemClock.elapsedRealtime(), withSound = true), false,
                    InputLockBannerPlacement(), Modifier.align(Alignment.TopStart))
            }
        }
        compose.onNodeWithTag("recording-indicator").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("The mirrored screen gets the tap", 1, touched) }
    }

    @Test fun whileInputIsLockedItSitsBelowTheUnlockButton() {
        val placement = InputLockBannerPlacement()
        show {
            Box(Modifier.requiredSize(320.dp, 640.dp)) {
                InputLockBanner(onUnlock = {}, modifier = Modifier.align(Alignment.TopCenter), placement = placement)
                RecordingIndicator(RecordingStatus(SystemClock.elapsedRealtime(), withSound = true), true,
                    placement, Modifier.align(Alignment.TopStart))
            }
        }
        compose.waitForIdle()
        val banner = compose.onNodeWithTag("input-lock-banner").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("recording-indicator").fetchSemanticsNode().boundsInRoot
        assertTrue("$indicator must sit below $banner", indicator.top >= banner.bottom - 1f)
    }

    @Test fun theChoiceNamesTheCodecAndEachAnswerDoesOneThing() {
        val answers = mutableListOf<String>()
        show {
            RecordSoundChoiceDialog(AudioCodec.OPUS, { answers += "reconnect" }, { answers += "picture" },
                { answers += "cancel" })
        }
        compose.onNodeWithText(compose.activity.getString(R.string.recording_sound_body, AudioCodec.OPUS.label))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.recording_sound_reconnect)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.recording_sound_picture_only)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf("reconnect", "picture", "cancel"), answers) }
    }

    @Test fun theSaveQuestionSaysHowLongTheRecordingIsAndWhetherItHasSound() {
        val withSound = record(sound = true)
        val pictureOnly = record(sound = false)
        try {
            show {
                androidx.compose.foundation.layout.Column {
                    RecordingSummary(withSound)
                    RecordingSummary(pictureOnly)
                }
            }
            compose.waitUntil(5_000) {
                runCatching {
                    compose.onNodeWithText(compose.activity.getString(R.string.recording_summary_with_sound,
                        DateUtils.formatElapsedTime(1))).assertExists()
                    compose.onNodeWithText(compose.activity.getString(R.string.recording_summary_picture_only,
                        DateUtils.formatElapsedTime(1))).assertExists()
                }.isSuccess
            }
        } finally {
            withSound.delete()
            pictureOnly.delete()
        }
    }

    /** About a second and a half of picture, with or without sound. */
    private fun record(sound: Boolean): File {
        val video = EncodedMedia.h264(count = 24)
        val audio = EncodedMedia.aac(seconds = 1.6)
        val recorder = SessionRecorder(File(compose.activity.cacheDir, "summary-${System.nanoTime()}.mp4"), sound)
        recorder.onFormat(MediaFormat.MIMETYPE_VIDEO_AVC, EncodedMedia.WIDTH, EncodedMedia.HEIGHT, video.config)
        if (sound) recorder.onAudioFormat(audio.config)
        audio.frames.take(2).forEach { recorder.onAudioSample(it.data, it.data.size, it.ptsUs) }
        video.frames.forEach { recorder.onSample(it.data, it.data.size, it.ptsUs, it.keyFrame) }
        audio.frames.drop(2).forEach { recorder.onAudioSample(it.data, it.data.size, it.ptsUs) }
        val file = recorder.finish()
        assertNotNull("Nothing recorded: ${recorder.error}", file)
        return file!!
    }
}
