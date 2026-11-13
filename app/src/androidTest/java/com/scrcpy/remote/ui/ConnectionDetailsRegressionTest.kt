package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.VideoFrameStats
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionDetailsRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun noRenderedFramesAreUnknownRatherThanAnObservedZeroFps() {
        render(
            settings = MirrorSettings(audioEnabled = false),
            frames = VideoFrameStats(receivedFrames = 10, decodedFrames = 10),
        )
        compose.onNodeWithTag("connection-details")
            .performScrollToNode(hasText(text(R.string.details_frame_rate)))
        compose.onNodeWithText(text(R.string.details_frame_rate)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_fps, 0)).assertDoesNotExist()
    }

    @Test fun audioWithoutPlaybackMeasurementsIsUnknownRatherThanAnObservedZeroQueue() {
        render(settings = MirrorSettings(audioEnabled = true))
        compose.onNodeWithTag("connection-details")
            .performScrollToNode(hasText(text(R.string.details_buffered)))
        compose.onNodeWithText(text(R.string.details_buffered)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.details_ms, 0)).assertDoesNotExist()
    }

    @Test fun copiedReportDistinguishesRequestedBitrateLifetimeSkipsAndLocalLatency() {
        var copied = ""
        val settings = MirrorSettings(audioEnabled = false, videoBitRateMbps = 8)
        render(
            settings = settings,
            frames = VideoFrameStats(
                receivedFps = 60, decodedFps = 30, surfaceFps = 29,
                receivedFrames = 1_000, decodedFrames = 600, surfaceFrames = 500,
                skippedFrames = 120,
            ),
            latency = LatencyStats(samples = 20, medianMillis = 14, p95Millis = 25, worstMillis = 30),
            onCopy = { copied = it },
        )
        compose.onNodeWithText(text(R.string.details_copy)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertTrue(copied, copied.contains("Requested video bit rate: 8 Mbps (not measured bandwidth)"))
            assertTrue(copied, copied.contains("Frames skipped (session total): 120"))
            assertTrue(copied, copied.contains("not network or glass-to-glass latency"))
        }
    }

    private fun render(
        settings: MirrorSettings,
        frames: VideoFrameStats = VideoFrameStats(),
        latency: LatencyStats = LatencyStats(),
        onCopy: (String) -> Unit = {},
    ) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    ConnectionDetailsDialog(
                        address = "192.0.2.1:5555",
                        deviceName = "Test device",
                        settings = settings,
                        videoWidth = 1080,
                        videoHeight = 1920,
                        frames = frames,
                        latency = latency,
                        timing = MediaTimingDiagnostics(),
                        recording = false,
                        onStatsWatched = {},
                        onCopy = onCopy,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
}
