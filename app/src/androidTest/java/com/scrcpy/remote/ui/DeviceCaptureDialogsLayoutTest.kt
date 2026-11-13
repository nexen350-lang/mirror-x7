package com.scrcpy.remote.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CameraFacing
import com.scrcpy.remote.scrcpy.DeviceCamera
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.DeviceCamerasState
import com.scrcpy.remote.session.DeviceEncodersState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DeviceCaptureDialogsLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalOrientation: Int? = null

    @After
    fun restoreOrientation() {
        compose.runOnUiThread {
            compose.activity.setContent {}
            originalOrientation?.let { compose.activity.requestedOrientation = it }
        }
    }

    @Test
    fun cameraChoicesRemainTouchableInShortLargeTextWindow() {
        var chosen: CameraCapture? = null
        var selections = 0
        renderInConstrainedWindow(text(R.string.cameras_help)) {
            DeviceCamerasDialog(
                state = DeviceCamerasState(
                    cameras = listOf(DeviceCamera("front", CameraFacing.FRONT, 1280, 720)),
                    loaded = true,
                ),
                selected = null,
                onSelect = { chosen = it; selections++ },
                createsProfile = false,
                sessionOnly = true,
                onRetry = {},
                onDismiss = {},
            )
        }

        touchChoice("device-camera-list", text(R.string.cameras_front))
        compose.onNodeWithTag("camera-save").assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals("front", chosen?.id)
        }
        touchChoice("device-camera-list", text(R.string.cameras_screen))
        compose.onNodeWithTag("camera-save").assertIsDisplayed().performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, selections)
            assertNull(chosen)
        }
    }

    @Test
    fun videoEncoderChoicesRemainTouchableInShortLargeTextWindow() {
        var chosen: String? = "unset"
        var selections = 0
        renderInConstrainedWindow(
            compose.activity.getString(R.string.encoders_help_for_codec, VideoCodec.H264.label),
        ) {
            DeviceEncodersDialog(
                state = DeviceEncodersState(
                    encoders = listOf(DeviceEncoder("h264", "c2.android.avc.encoder", "sw")),
                    loaded = true,
                ),
                selected = null,
                codec = VideoCodec.H264,
                onSelect = { chosen = it; selections++ },
                createsProfile = false,
                sessionOnly = true,
                onRetry = {},
                onDismiss = {},
            )
        }

        touchChoice("device-encoder-list", "c2.android.avc.encoder")
        compose.runOnIdle { assertEquals("c2.android.avc.encoder", chosen) }
        touchChoice("device-encoder-list", text(R.string.encoders_default))
        compose.runOnIdle {
            assertEquals(2, selections)
            assertNull(chosen)
        }
    }

    @Test
    fun audioEncoderChoicesRemainTouchableInShortLargeTextWindow() {
        var chosen: String? = "unset"
        var selections = 0
        renderInConstrainedWindow(
            compose.activity.getString(R.string.audio_encoders_help_for_codec, AudioCodec.OPUS.label),
        ) {
            DeviceAudioEncodersDialog(
                state = DeviceEncodersState(
                    encoders = listOf(DeviceEncoder("opus", "c2.android.opus.encoder", "sw")),
                    loaded = true,
                ),
                selected = null,
                codec = AudioCodec.OPUS,
                onSelect = { chosen = it; selections++ },
                createsProfile = false,
                sessionOnly = true,
                onRetry = {},
                onDismiss = {},
            )
        }

        touchChoice("device-encoder-list", "c2.android.opus.encoder")
        compose.runOnIdle { assertEquals("c2.android.opus.encoder", chosen) }
        touchChoice("device-encoder-list", text(R.string.encoders_default))
        compose.runOnIdle {
            assertEquals(2, selections)
            assertNull(chosen)
        }
    }

    private fun touchChoice(listTag: String, label: String) {
        val list = compose.onNodeWithTag(listTag)
        val bounds = list.fetchSemanticsNode().boundsInRoot
        assertTrue("Explanatory text must leave a visible, scrollable choice viewport", bounds.height > 0f)
        list.performScrollToNode(hasText(label))
        compose.onNodeWithText(label).assertIsDisplayed().performTouchInput { click(center) }
    }

    private fun renderInConstrainedWindow(helpText: String, content: @Composable () -> Unit) {
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
            // Dialog's own Android Compose root gets its density from the View's Context.
            val context = ContextThemeWrapper(activity, R.style.Theme_ScrcpyRemote).apply {
                applyOverrideConfiguration(Configuration().apply { fontScale = 2f })
            }
            activity.setContentView(ComposeView(context).apply {
                setContent { ScrcpyRemoteTheme { content() } }
            })
        }
        compose.waitForIdle()

        val density = compose.activity.resources.displayMetrics.density
        val widthPx = (640 * density).roundToInt()
        val heightPx = (320 * density).roundToInt()
        val provider = compose.runOnIdle {
            val decor = compose.activity.window.decorView
            assertTrue(
                "Test setup needs a landscape display large enough for a real 640x320dp dialog",
                decor.width >= widthPx && decor.height >= heightPx,
            )
            WindowInspector.getGlobalWindowViews().mapNotNull(::findDialog).single().also {
                // AlertDialog is a separate Window: constraining its caller's Box does not constrain it.
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
            assertEquals("Actual dialog window width", widthPx, provider.window.decorView.width)
            assertEquals("Actual dialog window height", heightPx, provider.window.decorView.height)
        }
        val textLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(helpText).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Dialog help text must have a measured text layout", it(textLayouts))
        }
        assertEquals(1, textLayouts.size)
        assertEquals(
            "Actual dialog text must be measured at 2x, not just its caller",
            2f,
            textLayouts.single().layoutInput.density.fontScale,
            0f,
        )
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

    private fun text(id: Int): String = compose.activity.getString(id)
}
