package com.scrcpy.remote.ui

import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The full control panel, More controls, behaves as a menu: touching the screen around it
 * minimizes it to the compact bar, and that touch is never sent to the device. Its contents are
 * ordered by use, with what needs attention first.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedPanelTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Every touch the mirrored device would have been sent. */
    private val touches = mutableListOf<Int>()

    @Test fun touchingTheScreenAroundThePanelMinimizesItWithoutTouchingTheDevice() {
        overMirror()
        openPanel()
        compose.onNodeWithTag("panel-scrim").assertExists()
        compose.onNodeWithTag("mirror-root").performTouchInput { click(Offset(width * 0.3f, height * 0.1f)) }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        compose.onNodeWithTag("panel-scrim").assertDoesNotExist()
        compose.runOnIdle { assertTrue("The touch that closed the panel reached the device: $touches", touches.isEmpty()) }
        // From the compact bar the screen belongs to the device again.
        compose.onNodeWithTag("mirror-root").performTouchInput { click(Offset(width * 0.3f, height * 0.1f)) }
        compose.runOnIdle { assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touches) }
    }

    @Test fun aDragThatBeginsOutsideThePanelSendsTheDeviceNothing() {
        overMirror()
        openPanel()
        compose.onNodeWithTag("mirror-root").performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.1f), Offset(width * 0.5f, height * 0.6f), durationMillis = 400)
        }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.runOnIdle { assertTrue("Part of the drag reached the device: $touches", touches.isEmpty()) }
    }

    @Test fun aSecondFingerDuringTheMinimizingTouchStillSendsNothing() {
        overMirror()
        openPanel()
        // One finger outside the panel and, a frame later, a second elsewhere on the screen.
        compose.onNodeWithTag("mirror-root").performTouchInput { down(0, Offset(width * 0.2f, height * 0.1f)) }
        compose.waitForIdle()
        compose.onNodeWithTag("mirror-root").performTouchInput { down(1, Offset(width * 0.7f, height * 0.2f)) }
        compose.waitForIdle()
        // Minimized only once the touch ends: the compact bar's Back, Home and Recents must not
        // appear under fingers that are still on the screen.
        compose.onNodeWithTag("compact-navigation").assertDoesNotExist()
        compose.onNodeWithTag("mirror-root").performTouchInput {
            up(1)
            up(0)
        }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        compose.runOnIdle { assertTrue("A finger of the minimizing touch reached the device: $touches", touches.isEmpty()) }
    }

    @Test fun calibratingSwitchesThePadOffBecauseThePadIgnoresTheOffsets() {
        var pad = true
        render(actions().copy(precisionPad = true, onPrecisionPadChange = { pad = it }))
        openPanel()
        compose.onNodeWithContentDescription(text(R.string.calibrate)).performScrollTo().performClick()
        compose.runOnIdle { assertFalse("Calibration is tried with direct touch", pad) }
    }

    @Test fun aCameraSessionOffersItsOverlayDirectlyAndStaysAMenu() {
        var overlay = false
        overMirror(actions().copy(cameraSession = true, onToggleDiagnostics = { overlay = !overlay }))
        openPanel()
        // A camera takes no touches, so there is no offset to try and nothing to calibrate.
        compose.onNodeWithContentDescription(text(R.string.calibrate)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.panel_overlay_description)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue("The overlay tile switches the overlay", overlay) }
        compose.onNodeWithTag("mirror-root").performTouchInput { click(Offset(width * 0.3f, height * 0.1f)) }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.runOnIdle { assertTrue(touches.isEmpty()) }
    }

    @Test fun touchesOnThePanelKeepItOpenAndItsOwnButtonsStillWork() {
        var back = 0
        overMirror(actions().copy(onBack = { back++ }))
        openPanel()
        // The painted panel between its buttons, just inside its left edge.
        compose.onNodeWithTag("expanded-controls").performTouchInput { click(Offset(8f, height / 2f)) }
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.control_back)).performClick()
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, back)
            assertTrue("The panel itself must not touch the device: $touches", touches.isEmpty())
        }
    }

    @Test fun aVisibleHandleStillHidesEverything() {
        overMirror()
        // Dragged to the top, where the panel opening at the bottom cannot cover it.
        compose.onNodeWithContentDescription(text(R.string.control_show)).performTouchInput {
            swipe(center, Offset(center.x, center.y - 1_500f), durationMillis = 300)
        }
        openPanel()
        compose.onNodeWithContentDescription(text(R.string.control_hide)).performClick()
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithTag("compact-navigation").assertDoesNotExist()
        compose.onNodeWithTag("panel-scrim").assertDoesNotExist()
        compose.runOnIdle { assertTrue(touches.isEmpty()) }
    }

    @Test fun whileCalibratingTheScreenStaysTouchableToTryTheOffset() {
        overMirror()
        openPanel()
        compose.onNode(hasContentDescription(text(R.string.calibrate)) or hasText(text(R.string.calibrate)))
            .performScrollTo().performClick()
        compose.onNodeWithTag("mirror-root").performTouchInput { click(Offset(width * 0.3f, height * 0.1f)) }
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touches) }
    }

    @Test fun attentionNoticesComeFirstAndTheirActionsNeedNoScrolling() {
        var retries = 0
        var applies = 0
        render(actions().copy(
            audioError = "Audio output needs attention", onRetryAudio = { retries++ },
            pendingSettings = true, onApplySettings = { applies++ },
        ))
        openPanel()
        compose.onNodeWithText("Audio output needs attention").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_retry_audio)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.session_apply_settings)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, applies)
        }
    }

    @Test fun soundAndroid13WillNotShareOffersToHearItHereRatherThanARetry() {
        var heard = 0
        var retries = 0
        val message = text(R.string.audio_dup_refused)
        render(actions().copy(audioError = message, audioDupRefused = true,
            onHearSoundHere = { heard++ }, onRetryAudio = { retries++ }))
        openPanel()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.audio_dup_refused_help)).assertIsDisplayed()
        // A retry would ask Android 13 for the same capture it has just refused.
        compose.onAllNodesWithText(text(R.string.ui_retry_audio)).assertCountEquals(0)
        compose.onAllNodesWithText("Use scrcpy 4.1").assertCountEquals(0)
        compose.onNodeWithText(text(R.string.audio_hear_here)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, heard)
            assertEquals(0, retries)
        }
    }

    @Test fun pictureAndSoundIsASessionTool() {
        var opened = 0
        render(actions().copy(onStreamChoices = { opened++ }))
        openPanel()
        compose.onNodeWithContentDescription(text(R.string.stream_choices_title)).performScrollTo().assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun deviceControlsComeFirstAndInputLockIsATileRatherThanAParagraph() {
        render(actions())
        openPanel()
        // The first rows, without scrolling: what a remote control is used for most.
        for (id in listOf(R.string.control_volume_down, R.string.control_volume_up, R.string.control_power)) {
            compose.onNodeWithContentDescription(text(id)).assertIsDisplayed()
        }
        compose.onAllNodesWithText("not restricted ADB access", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription(text(R.string.input_lock_action)).performScrollTo().assertHasClickAction()
        // Grouped by purpose, in this order: Device, Input, Capture, This screen, Session. Read from
        // positions in the panel's content, which scrolling and clipping do not change.
        val order = listOf(R.string.control_power, R.string.control_keyboard, R.string.capture_screenshot,
            R.string.view_zoom_in, R.string.details_action)
        val tops = order.map { compose.onNodeWithContentDescription(text(it)).fetchSemanticsNode().positionInRoot.y }
        assertEquals("Sections out of order: $tops", tops.sorted(), tops)
        // Calibrate is a tile among the zoom controls rather than a full-width button below them:
        // after the zoom tiles and before the Session section, however the row wraps.
        val zoom = compose.onNodeWithContentDescription(text(R.string.view_zoom_in)).fetchSemanticsNode().positionInRoot
        val calibrate = compose.onNodeWithContentDescription(text(R.string.calibrate)).assertHasClickAction()
            .fetchSemanticsNode().positionInRoot
        assertTrue("Calibrate sits with This screen: $calibrate after $zoom, before ${tops.last()}",
            calibrate.y >= zoom.y && calibrate.y < tops.last())
    }

    @Test fun anActionThatChangesTheDevicesScreenReturnsThePanelToTheCompactBar() {
        val requests = mutableIntStateOf(0)
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions().copy(compactRequests = requests.intValue), false,
                    Modifier.requiredSize(320.dp, 640.dp))
            }
        }
        openPanel()
        // Such as opening an app from the Apps dialog: what happens next is on the device's screen.
        compose.runOnIdle { requests.intValue++ }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        // An answered request does not close the panel when it is next opened.
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
    }

    @Test fun tilesFillThePanelWidthOnAGridThatStartsUnderTheirHeading() {
        render(actions())
        openPanel()
        val density = compose.activity.resources.displayMetrics.density
        val body = compose.onNodeWithTag("expanded-controls-body").fetchSemanticsNode().boundsInRoot
        val heading = compose.onNodeWithText(text(R.string.release_controls_device)).fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithContentDescription(text(R.string.control_volume_down)).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithContentDescription(text(R.string.control_volume_up)).fetchSemanticsNode().boundsInRoot
        assertEquals("The first tile starts under its heading", heading.left, first.left, density)
        assertEquals("Tiles share one width", first.width, second.width, density)
        assertTrue("Tiles stay at least 72 dp wide: ${first.width / density}", first.width >= 72 * density - 1f)
        // Whole columns of equal tiles and gaps span the panel exactly, so no row is centred.
        val gap = second.left - first.right
        val columns = Math.round((body.width + gap) / (first.width + gap))
        assertEquals("Columns span the body: $columns of ${first.width / density} dp", body.width,
            columns * (first.width + gap) - gap, 1.5f * density)
    }

    @Test fun rowsStayWholeAtInBetweenDensities() {
        // Display size gives densities such as 2.55, where 8 dp is 20.4 px: FlowRow ends rows on
        // 21 px gaps and places tiles on 20 px ones. Tiles planned on 20 px left every full row a
        // tile short, with an empty column at its end.
        for (density in listOf(2.3875f, 2.55f)) {
            for (width in 300..400 step 10) {
                renderAt(density, width)
                openPanel()
                val body = compose.onNodeWithTag("expanded-controls-body").fetchSemanticsNode().boundsInRoot
                val first = compose.onNodeWithContentDescription(text(R.string.control_volume_down))
                    .fetchSemanticsNode().boundsInRoot
                val row = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("expanded-controls-body")))
                    .fetchSemanticsNodes().map { it.boundsInRoot }.filter { abs(it.top - first.top) < 1f }
                val end = row.maxOf { it.right }
                assertTrue("At $density and $width dp, ${row.size} tiles of ${first.width} px leave " +
                    "${body.right - end} px of ${body.width}", body.right - end < first.width / 2)
            }
        }
    }

    private fun renderAt(density: Float, width: Int) {
        compose.runOnUiThread {
            compose.activity.setContent {
                // A new composition each time: setContent keeps the old one, open panel and all.
                key(density, width) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                        FloatingControlsOverlay(actions(), false, Modifier.requiredSize(width.dp, 640.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun overMirror(actions: MirrorActions = actions()) {
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("mirror-root")) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            ScrcpyMirrorView(context).apply {
                                setVideoSize(320, 640)
                                onTouch = { action, _, _, _, _, _, _, _, _ -> touches += action }
                            }
                        },
                    )
                    FloatingControlsOverlay(actions, false, Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun render(actions: MirrorActions) {
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions, false, Modifier.requiredSize(320.dp, 640.dp))
            }
        }
    }

    private fun openPanel() {
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
}
