package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.AudioState
import com.scrcpy.remote.session.ConnectionQualityHistory
import com.scrcpy.remote.session.ConnectionQualitySnapshot
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.VideoFrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionQualityDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun measuredSummariesExposeFirstLatestRangeAndMeasurementCountAsText() {
        val history = measuredHistory()
        render { Details(history) }
        scrollTo("quality-fps-summary")
        compose.onNodeWithTag("quality-fps-summary").assertTextEquals(text(
            R.string.quality_metric_summary, "30 fps", "0 fps", "0 fps", "30 fps", 3,
        ))
        scrollTo("quality-latency-summary")
        compose.onNodeWithTag("quality-latency-summary").assertTextEquals(text(
            R.string.quality_metric_summary, "25 ms", "25 ms", "25 ms", "25 ms", 3,
        ))
        scrollTo("quality-deltas")
        compose.onNodeWithTag("quality-deltas").assertTextContains(
            text(R.string.quality_observed_total, 4L, 2), substring = true,
        )
    }

    @Test fun enabledButFailedAudioDoesNotDisplayAHealthyZeroOrReuseItsOldQueue() {
        val history = measuredHistory(audioAvailable = false)
        render {
            Details(
                history,
                audioState = AudioState(error = "Playback unavailable"),
                timing = MediaTimingDiagnostics(audioQueuedMs = 80, outputQueuedMs = 40, underruns = 100),
            )
        }
        scrollTo("quality-audio-summary")
        compose.onNodeWithTag("quality-audio-summary").assertTextEquals(text(R.string.quality_missing))
        scrollTo("quality-underruns-summary")
        compose.onNodeWithTag("quality-underruns-summary").assertTextEquals(text(R.string.quality_missing))
        compose.onNodeWithTag("connection-details")
            .performScrollToNode(hasText(text(R.string.details_buffered)))
        compose.onNodeWithText(text(R.string.quality_audio_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_ms, 80)).assertDoesNotExist()
    }

    @Test fun disabledAudioIsExplicitlyNotSampled() {
        val history = measuredHistory(audioAvailable = false)
        render { Details(history, settings = MirrorSettings(audioEnabled = false), audioState = AudioState()) }
        scrollTo("quality-audio-summary")
        compose.onNodeWithTag("quality-audio-summary").assertTextEquals(text(R.string.quality_audio_off))
        scrollTo("quality-underruns-summary")
        compose.onNodeWithTag("quality-underruns-summary").assertTextEquals(text(R.string.quality_audio_off))
    }

    @Test fun measuredStaticFramesAndAnEmptyPlayingAudioQueueCanHonestlyReportZero() {
        val history = ConnectionQualityHistory().apply {
            setWatching(true, 0)
            record(0, fps = 0, queued = 0)
        }.record(500, fps = 0, queued = 0)
        render {
            Details(
                history,
                frames = VideoFrameStats(surfaceFrames = 100),
                timing = MediaTimingDiagnostics(outputQueuedMs = 0),
            )
        }
        scrollTo("quality-fps-summary")
        compose.onNodeWithTag("quality-fps-summary").assertTextContains("Latest sampled: 0 fps", substring = true)
        scrollTo("quality-audio-summary")
        compose.onNodeWithTag("quality-audio-summary").assertTextContains("Latest sampled: 0 ms", substring = true)
        // The value is centered beside a multi-line description; scrolling to its title alone
        // does not guarantee that the value has entered the dialog's clipped viewport.
        val zeroFps = text(R.string.details_fps, 0)
        compose.onNodeWithTag("connection-details")
            .performScrollToNode(hasText(zeroFps))
        compose.onNodeWithText(zeroFps).assertIsDisplayed()
    }

    @Test fun copiedSummaryIsBoundedAndSeparatesRequestsCumulativeCountersAndObservedDeltas() {
        val history = ConnectionQualityHistory().apply { setWatching(true, 0) }
        var snapshot = ConnectionQualitySnapshot()
        repeat(160) {
            snapshot = history.record(it * 500L, skipped = 10_000L + it, underruns = 1_000 + it)
        }
        var copied = ""
        render {
            Details(
                snapshot,
                frames = VideoFrameStats(surfaceFps = 30, surfaceFrames = 500, skippedFrames = 10_159),
                onCopy = { copied = it },
            )
        }
        compose.onNodeWithText(text(R.string.details_copy)).performClick()
        compose.runOnIdle {
            assertTrue(copied, copied.contains("Requested video bit rate: 8 Mbps (not measured bandwidth)"))
            assertTrue(copied, copied.contains("Frames skipped (session total): 10159"))
            assertTrue(copied, copied.contains("120 retained samples"))
            assertTrue(copied, copied.contains(
                "Skipped increases (observed intervals, not session total): 119 across 119 observed intervals",
            ))
            assertTrue(copied, copied.contains("closed/hidden gaps are not measurements"))
            assertTrue(copied, copied.contains("not network or glass-to-glass latency"))
            assertTrue(copied, copied.contains("no persistence or upload"))
            assertFalse(copied.substringAfter("Recent quality —").contains("192.0.2.1"))
            assertTrue("Copy summarizes bounded samples instead of exporting a growing log", copied.length < 4_000)
        }
    }

    @Test fun reopeningKeepsUnexpiredSamplesButNeverCountsTheClosedGap() {
        val history = ConnectionQualityHistory()
        val snapshot = mutableStateOf(ConnectionQualitySnapshot())
        val shown = mutableStateOf(true)
        var elapsed = 0L
        var copied = ""
        val watched = mutableListOf<Boolean>()
        render {
            if (shown.value) {
                Details(
                    snapshot.value,
                    onStatsWatched = {
                        watched += it
                        snapshot.value = history.setWatching(it, elapsed)
                    },
                    onCopy = { copied = it },
                    onDismiss = { shown.value = false },
                )
            }
        }
        compose.runOnIdle {
            snapshot.value = history.record(0, skipped = 10)
            elapsed = 500
            snapshot.value = history.record(elapsed, skipped = 11)
            elapsed = 600
        }
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle {
            assertFalse(snapshot.value.collecting)
            elapsed = 5_000
            shown.value = true
        }
        scrollTo("quality-window")
        compose.onNodeWithTag("quality-window").assertTextEquals(text(R.string.quality_window, 2))
        compose.runOnIdle {
            snapshot.value = history.record(elapsed, skipped = 100)
            assertNull(snapshot.value.samples.last().skippedDelta)
            elapsed = 5_500
            snapshot.value = history.record(elapsed, skipped = 102)
        }
        compose.onNodeWithText(text(R.string.details_copy)).performClick()
        compose.runOnIdle {
            assertTrue(copied, copied.contains(
                "Skipped increases (observed intervals, not session total): 3 across 2 observed intervals",
            ))
            elapsed = 6_000
        }
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle {
            elapsed = 66_000
            shown.value = true
        }
        scrollTo("quality-window")
        compose.onNodeWithTag("quality-window").assertTextEquals(text(R.string.quality_window, 0))
        compose.runOnIdle {
            assertTrue(snapshot.value.samples.isEmpty())
            assertEquals(listOf(true, false, true, false, true), watched)
        }
    }

    @Test fun emptyHistoryWaitsForMeasurementsInsteadOfDrawingInventedZeros() {
        render { Details(ConnectionQualitySnapshot(), frames = VideoFrameStats(), audioState = AudioState()) }
        compose.onNodeWithTag("connection-details")
            .performScrollToNode(hasText(text(R.string.quality_waiting)))
        compose.onNodeWithText(text(R.string.quality_waiting)).assertIsDisplayed()
        listOf("quality-fps-summary", "quality-latency-summary", "quality-audio-summary", "quality-underruns-summary")
            .forEach {
                scrollTo(it)
                compose.onNodeWithTag(it).assertTextEquals(text(R.string.quality_missing))
            }
    }

    @Test fun missingLatestValuesDoNotCarryForwardAnOlderMeasurement() {
        val history = ConnectionQualityHistory().apply {
            setWatching(true, 0)
            record(0)
        }.sample(500, null, null, null, audioAvailable = false)
        var copied = ""
        render { Details(history, onCopy = { copied = it }) }
        scrollTo("quality-fps-summary")
        compose.onNodeWithTag("quality-fps-summary").assertTextEquals(text(
            R.string.quality_metric_summary, "30 fps", text(R.string.quality_missing), "30 fps", "30 fps", 1,
        ))
        compose.onNodeWithText(text(R.string.details_copy)).performClick()
        compose.runOnIdle {
            assertTrue(copied, copied.contains("Rendered FPS: first 30 fps; latest not measured"))
        }
    }

    private fun measuredHistory(audioAvailable: Boolean = true): ConnectionQualitySnapshot {
        val history = ConnectionQualityHistory().apply { setWatching(true, 0) }
        history.record(0, fps = 30, skipped = 100, underruns = 10, audioAvailable = audioAvailable)
        history.record(500, fps = 20, skipped = 102, underruns = 11, audioAvailable = audioAvailable)
        return history.record(1_000, fps = 0, skipped = 104, underruns = 12, audioAvailable = audioAvailable)
    }

    private fun ConnectionQualityHistory.record(
        elapsed: Long,
        fps: Int = 30,
        skipped: Long = 0,
        underruns: Int = 0,
        queued: Int = 10,
        audioAvailable: Boolean = true,
    ) = sample(
        elapsed,
        VideoFrameStats(surfaceFps = fps, surfaceFrames = 100, skippedFrames = skipped),
        LatencyStats(samples = 10, p95Millis = 25),
        MediaTimingDiagnostics(audioQueuedMs = queued, outputQueuedMs = 0, underruns = underruns),
        audioAvailable,
    )

    @Composable
    private fun Details(
        history: ConnectionQualitySnapshot,
        settings: MirrorSettings = MirrorSettings(),
        frames: VideoFrameStats = VideoFrameStats(surfaceFps = 30, surfaceFrames = 100, skippedFrames = 104),
        audioState: AudioState = AudioState(playing = true),
        timing: MediaTimingDiagnostics = MediaTimingDiagnostics(audioQueuedMs = 10, outputQueuedMs = 0),
        onStatsWatched: (Boolean) -> Unit = {},
        onCopy: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        ConnectionDetailsDialog(
            address = "192.0.2.1:5555",
            deviceName = "Test device",
            settings = settings,
            videoWidth = 1080,
            videoHeight = 1920,
            frames = frames,
            latency = LatencyStats(samples = 10, p95Millis = 25),
            timing = timing,
            recording = false,
            history = history,
            audioState = audioState,
            onStatsWatched = onStatsWatched,
            onCopy = onCopy,
            onDismiss = onDismiss,
        )
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent { ScrcpyRemoteTheme { content() } }
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("connection-details").performScrollToNode(hasTestTag(tag))
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
