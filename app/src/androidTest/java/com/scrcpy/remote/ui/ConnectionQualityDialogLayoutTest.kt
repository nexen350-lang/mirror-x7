package com.scrcpy.remote.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.AudioState
import com.scrcpy.remote.session.ConnectionQualityHistory
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.VideoFrameStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ConnectionQualityDialogLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalOrientation: Int? = null

    @After fun restoreOrientation() {
        compose.runOnUiThread {
            compose.activity.setContent {}
            originalOrientation?.let { compose.activity.requestedOrientation = it }
        }
    }

    @Test fun lightHistoryRemainsReadableAndTouchableInARealShortWindowAt2xText() =
        verifyConstrainedDialog(dark = false)

    @Test fun darkHistoryRemainsReadableAndTouchableInARealShortWindowAt2xText() =
        verifyConstrainedDialog(dark = true)

    private fun verifyConstrainedDialog(dark: Boolean) {
        val history = ConnectionQualityHistory().apply {
            setWatching(true, 0)
            repeat(120) { index ->
                sample(
                    index * 500L,
                    VideoFrameStats(surfaceFps = index % 61, surfaceFrames = 1_000, skippedFrames = 100L + index),
                    LatencyStats(samples = 30, p95Millis = 10 + index % 30),
                    MediaTimingDiagnostics(audioQueuedMs = index % 50, outputQueuedMs = 0, underruns = index / 10),
                    audioAvailable = true,
                )
            }
        }.snapshot(59_500)
        var copied = ""
        var dismissed = false
        var textColor = Color.Unspecified
        var pointColor = Color.Unspecified
        var background = Color.Unspecified
        compose.runOnIdle {
            originalOrientation = compose.activity.requestedOrientation
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            val activity = compose.activity
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                activity.window.decorView.width > activity.window.decorView.height
        }
        compose.runOnUiThread {
            val activity = compose.activity
            val context = ContextThemeWrapper(activity, R.style.Theme_ScrcpyRemote).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = 2f })
            }
            activity.setContentView(ComposeView(context).apply {
                setContent {
                    ScrcpyRemoteTheme(darkTheme = dark) {
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                        pointColor = MaterialTheme.colorScheme.primary
                        background = MaterialTheme.colorScheme.surfaceContainerLowest
                        ConnectionDetailsDialog(
                            address = "2001:db8:1234:5678:90ab:cdef:1234:5678:5555",
                            deviceName = "A long device name for the living-room display",
                            settings = MirrorSettings(),
                            videoWidth = 1080,
                            videoHeight = 1920,
                            frames = VideoFrameStats(surfaceFps = 30, surfaceFrames = 1_000, skippedFrames = 219),
                            latency = LatencyStats(samples = 30, p95Millis = 25),
                            timing = MediaTimingDiagnostics(audioQueuedMs = 10, outputQueuedMs = 0),
                            recording = false,
                            history = history,
                            audioState = AudioState(playing = true),
                            onStatsWatched = {},
                            onCopy = { copied = it },
                            onDismiss = { dismissed = true },
                        )
                    }
                }
            })
        }
        compose.waitForIdle()
        constrainDialogWindow()
        val scroll = compose.onNodeWithTag("connection-details")
        assertTrue("The dialog must leave a scrollable viewport", scroll.fetchSemanticsNode().boundsInRoot.height > 0f)
        scroll.performScrollToNode(hasText(text(R.string.quality_collection_help)))
        verifyWrappedAt2x(compose.onNodeWithText(text(R.string.quality_collection_help)))
        listOf(
            "quality-window", "quality-fps-summary", "quality-latency-summary",
            "quality-audio-summary", "quality-underruns-summary", "quality-deltas",
        ).forEach { tag ->
            scroll.performScrollToNode(hasTestTag(tag))
            verifyWrappedAt2x(compose.onNodeWithTag(tag))
        }
        scroll.performScrollToNode(hasText(text(R.string.quality_method_help)))
        verifyWrappedAt2x(compose.onNodeWithText(text(R.string.quality_method_help)))
        compose.onNodeWithText(text(R.string.details_copy)).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        compose.onNodeWithText(text(R.string.done)).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click(center) }
        compose.runOnIdle {
            assertTrue(copied.contains("120 retained samples"))
            assertTrue(dismissed)
            assertTrue("Summary text needs readable contrast", contrast(textColor, background) >= 4.5)
            assertTrue("Trend dots must stand out from the card", contrast(pointColor, background) >= 3.0)
        }
    }

    private fun constrainDialogWindow() {
        val density = compose.activity.resources.displayMetrics.density
        val widthPx = (640 * density).roundToInt()
        val heightPx = (320 * density).roundToInt()
        val provider = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue("The fixture needs a landscape display at least 640x320dp",
                decor.width >= widthPx && decor.height >= heightPx)
            WindowInspector.getGlobalWindowViews().mapNotNull(::findDialog).single().also {
                val dialogView = it as View
                dialogView.layoutParams = dialogView.layoutParams.apply {
                    width = widthPx
                    height = heightPx
                }
                it.window.setLayout(widthPx, heightPx)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(widthPx, provider.window.decorView.width)
            assertEquals(heightPx, provider.window.decorView.height)
        }
    }

    private fun verifyWrappedAt2x(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(layouts))
        }
        val layout = layouts.single()
        assertEquals("The dialog itself, not only its caller, uses large text",
            2f, layout.layoutInput.density.fontScale, 0f)
        assertFalse("Quality text must wrap rather than clip or ellipsize", layout.hasVisualOverflow)
        repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
    }

    private fun findDialog(view: View): DialogWindowProvider? {
        if (view is DialogWindowProvider) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findDialog(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val rendered = foreground.compositeOver(background)
        return (maxOf(rendered.luminance(), background.luminance()) + 0.05) /
            (minOf(rendered.luminance(), background.luminance()) + 0.05)
    }

    private fun text(id: Int): String = compose.activity.getString(id)
}
