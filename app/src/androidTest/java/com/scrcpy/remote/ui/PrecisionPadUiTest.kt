package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

@RunWith(AndroidJUnit4::class)
class PrecisionPadUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyPadActionStaysReachableAtNarrowWidthAndDoubleText() {
        var exits = 0
        val dragging = mutableStateOf(false)
        val speed = mutableFloatStateOf(PrecisionPad.DEFAULT_SPEED)
        render(width = 240, height = 480, fontScale = 2f) {
            PrecisionPadBar(
                dragging = dragging.value,
                speed = speed.floatValue,
                cursorY = { Float.NaN },
                onToggleDrag = { dragging.value = !dragging.value },
                onSpeedChange = { speed.floatValue = it },
                onExit = { exits++ },
            )
        }
        val bar = compose.onNodeWithTag("precision-pad-bar").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("precision-pad-drag", "precision-pad-speed", "precision-pad-exit")) {
            compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag wraps inside the bar rather than off the screen: $bounds in $bar",
                bounds.left >= bar.left - 1f && bounds.right <= bar.right + 1f)
        }

        compose.onNodeWithTag("precision-pad-drag")
            .assert(description(text(R.string.precision_pad_drag)))
            .assert(state(text(R.string.precision_pad_drag_free)))
            .performClick()
        compose.onNodeWithTag("precision-pad-drag")
            .assert(description(text(R.string.precision_pad_drag)))
            .assert(state(text(R.string.precision_pad_drag_held)))
            .performClick()
        compose.runOnIdle { assertFalse(dragging.value) }

        compose.onNodeWithTag("precision-pad-speed").performClick()
        compose.runOnIdle { assertEquals(1.5f, speed.floatValue, 0f) }
        compose.onNodeWithTag("precision-pad-speed")
            .assert(description(text(R.string.precision_pad_speed_description, number(1.5f))))

        compose.onNodeWithTag("precision-pad-exit")
            .assert(description(text(R.string.precision_pad_exit_description)))
            .performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun theBarMovesToTheOtherEdgeRatherThanHideTheCursor() {
        val cursor = mutableFloatStateOf(Float.NaN)
        render(width = 360, height = 640) {
            PrecisionPadBar(dragging = false, speed = 1f, cursorY = { cursor.floatValue },
                onToggleDrag = {}, onSpeedChange = {}, onExit = {})
        }
        val node = compose.onNodeWithTag("precision-pad-bar")
        val bottom = node.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { cursor.floatValue = bottom.center.y }
        compose.waitUntil(5_000) { node.fetchSemanticsNode().boundsInRoot.top < bottom.top }
        val top = node.fetchSemanticsNode().boundsInRoot
        assertTrue("At the top it is clear of the cursor: $top vs ${bottom.center.y}", top.bottom < bottom.center.y)
        // Elsewhere the cursor leaves it where it is.
        compose.runOnIdle { cursor.floatValue = (top.bottom + bottom.top) / 2f }
        compose.waitForIdle()
        assertEquals(top, node.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { cursor.floatValue = top.center.y }
        compose.waitUntil(5_000) { node.fetchSemanticsNode().boundsInRoot.top > top.top }
        assertEquals(bottom, node.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun aBarReturningOverAStillCursorMovesAwayWithoutWaitingForItToMove() {
        val cursor = mutableFloatStateOf(Float.NaN)
        val shown = mutableStateOf(true)
        render(width = 360, height = 640) {
            if (shown.value) {
                PrecisionPadBar(dragging = false, speed = 1f, cursorY = { cursor.floatValue },
                    onToggleDrag = {}, onSpeedChange = {}, onExit = {})
            }
        }
        val node = compose.onNodeWithTag("precision-pad-bar")
        val bottom = node.fetchSemanticsNode().boundsInRoot
        // Gone while input is locked, say; meanwhile the cursor came to rest where the bar returns.
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { cursor.floatValue = bottom.center.y }
        compose.runOnIdle { shown.value = true }
        compose.waitUntil(5_000) {
            runCatching { node.fetchSemanticsNode().boundsInRoot.top < bottom.top }.getOrDefault(false)
        }
        val top = node.fetchSemanticsNode().boundsInRoot
        assertTrue("It cleared the cursor: $top vs ${bottom.center.y}", top.bottom < bottom.center.y)
        // And it settles there rather than going back.
        compose.waitForIdle()
        assertEquals(top, node.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun theBarMovesAwayWhenItShiftsOverAStillCursorAsWhenTheKeyboardOpens() {
        val cursor = mutableFloatStateOf(Float.NaN)
        val keyboard = mutableStateOf(0.dp)
        render(width = 360, height = 640) {
            // The bar's own inset padding lifts it above a keyboard; this lifts it the same way.
            Box(Modifier.fillMaxSize().padding(bottom = keyboard.value)) {
                PrecisionPadBar(dragging = false, speed = 1f, cursorY = { cursor.floatValue },
                    onToggleDrag = {}, onSpeedChange = {}, onExit = {})
            }
        }
        val node = compose.onNodeWithTag("precision-pad-bar")
        val bottom = node.fetchSemanticsNode().boundsInRoot
        val lift = with(compose.density) { 260.dp.toPx() }
        val resting = bottom.center.y - lift
        compose.runOnIdle { cursor.floatValue = resting }
        compose.waitForIdle()
        assertEquals("Clear of the cursor, the bar stays", bottom, node.fetchSemanticsNode().boundsInRoot)
        // Lifted onto the cursor, which has not moved.
        compose.runOnIdle { keyboard.value = 260.dp }
        compose.waitUntil(5_000) { node.fetchSemanticsNode().boundsInRoot.bottom < resting }
        val top = node.fetchSemanticsNode().boundsInRoot
        compose.waitForIdle()
        assertEquals("It settles at the top", top, node.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun thePanelOffersThePadForAScreenButNotForACameraOrWhileLocked() {
        val pad = mutableStateOf(false)
        val camera = mutableStateOf(false)
        val locked = mutableStateOf(false)
        render(width = 320, height = 640, dark = true) {
            FloatingControlsOverlay(
                actions().copy(cameraSession = camera.value, inputLocked = locked.value,
                    precisionPad = pad.value, onPrecisionPadChange = { pad.value = it }),
                muted = false,
            )
        }
        openMoreControls()
        compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).performScrollTo()
            .assert(state(text(R.string.precision_pad_state_off)))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertTrue(pad.value) }
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()

        openMoreControls()
        compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).performScrollTo()
            .assert(state(text(R.string.precision_pad_state_on)))
            .performClick()
        compose.runOnIdle { assertFalse(pad.value) }
        compose.onNodeWithTag("expanded-controls").assertExists()

        compose.runOnIdle { camera.value = true }
        compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).assertDoesNotExist()

        compose.runOnIdle { camera.value = false; locked.value = true }
        openMoreControls()
        compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).performScrollTo().assertIsNotEnabled()
    }

    private fun openMoreControls() {
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
    }

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun description(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(value))

    private fun number(value: Float): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)

    private fun render(width: Int, height: Int, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Surface { Box(Modifier.requiredSize(width.dp, height.dp)) { content() } }
                    }
                }
            }
        }
    }

    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
