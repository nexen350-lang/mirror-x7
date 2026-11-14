package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletMirroringUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun controlStripButtonsTriggerZoomFitMaximizeFullscreenAndDisconnect() {
        var zoom by mutableFloatStateOf(1f)
        var localZoomMode by mutableStateOf(false)
        var windowMode by mutableStateOf(MirrorWindowMode.WINDOWED)
        var fitCount = 0
        var disconnectCount = 0

        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(960.dp, 640.dp)) {
                        MirrorControlStrip(
                            zoom = zoom,
                            localZoomMode = localZoomMode,
                            windowMode = windowMode,
                            onZoomIn = { zoom = (zoom * 2f).coerceAtMost(VideoLayout.MAX_ZOOM) },
                            onZoomOut = { zoom = (zoom / 2f).coerceAtLeast(1f) },
                            onResetZoom = { zoom = 1f },
                            onFitToScreen = {
                                fitCount++
                                zoom = 1f
                            },
                            onToggleLocalZoomMode = { localZoomMode = !localZoomMode },
                            onToggleMaximize = {
                                windowMode = if (windowMode == MirrorWindowMode.MAXIMIZED) {
                                    MirrorWindowMode.WINDOWED
                                } else {
                                    MirrorWindowMode.MAXIMIZED
                                }
                            },
                            onToggleFullscreen = {
                                windowMode = if (windowMode == MirrorWindowMode.FULLSCREEN) {
                                    MirrorWindowMode.WINDOWED
                                } else {
                                    MirrorWindowMode.FULLSCREEN
                                }
                            },
                            onDisconnect = { disconnectCount++ },
                            autoHideDelayMs = 0L,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("mirror-control-strip").assertIsDisplayed()
        for (tag in listOf(
            "mirror-control-zoom-out",
            "mirror-control-reset-zoom",
            "mirror-control-zoom-in",
            "mirror-control-fit-screen",
            "mirror-control-pan-mode",
            "mirror-control-maximize",
            "mirror-control-fullscreen",
            "mirror-control-disconnect",
        )) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }

        compose.onNodeWithTag("mirror-control-zoom-out").assertIsNotEnabled()
        compose.onNodeWithTag("mirror-control-reset-zoom").assertIsNotEnabled()

        compose.onNodeWithTag("mirror-control-zoom-in").performClick()
        compose.runOnIdle { assertEquals(2f, zoom, 0.01f) }
        compose.onNodeWithText("2.0×").assertIsDisplayed()
        compose.onNodeWithTag("mirror-control-zoom-out").assertIsEnabled()
        compose.onNodeWithTag("mirror-control-reset-zoom").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1f, zoom, 0.01f) }

        compose.onNodeWithTag("mirror-control-fit-screen").performClick()
        compose.runOnIdle { assertEquals(1, fitCount) }

        compose.onNodeWithTag("mirror-control-pan-mode").performClick()
        compose.runOnIdle { assertTrue(localZoomMode) }
        compose.onNodeWithTag("mirror-control-pan-mode").performClick()
        compose.runOnIdle { assertFalse(localZoomMode) }

        compose.onNodeWithTag("mirror-control-maximize").performClick()
        compose.runOnIdle { assertEquals(MirrorWindowMode.MAXIMIZED, windowMode) }
        compose.onNodeWithContentDescription(text(R.string.mirror_control_restore_window)).performClick()
        compose.runOnIdle { assertEquals(MirrorWindowMode.WINDOWED, windowMode) }

        compose.onNodeWithTag("mirror-control-fullscreen").performClick()
        compose.runOnIdle { assertEquals(MirrorWindowMode.FULLSCREEN, windowMode) }
        compose.onNodeWithContentDescription(text(R.string.mirror_control_exit_fullscreen)).performClick()
        compose.runOnIdle { assertEquals(MirrorWindowMode.WINDOWED, windowMode) }

        compose.onNodeWithTag("mirror-control-disconnect").performClick()
        compose.runOnIdle { assertEquals(1, disconnectCount) }
    }

    @Test
    fun controlStripAutoHidesAndWakesOnTap() {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    Box(Modifier.requiredSize(960.dp, 640.dp)) {
                        MirrorControlStrip(
                            zoom = 1f,
                            localZoomMode = false,
                            windowMode = MirrorWindowMode.WINDOWED,
                            onZoomIn = {},
                            onZoomOut = {},
                            onResetZoom = {},
                            onFitToScreen = {},
                            onToggleLocalZoomMode = {},
                            onToggleMaximize = {},
                            onToggleFullscreen = {},
                            onDisconnect = {},
                            autoHideDelayMs = 250L,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("mirror-control-strip").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 3_000L) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("mirror-control-strip-wake")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("mirror-control-strip-wake").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mirror-control-strip").assertIsDisplayed()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
