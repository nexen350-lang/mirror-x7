package com.scrcpy.remote.ui

import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsActions
import com.scrcpy.remote.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun defaultNavigationUsesOnlyAShortStripWithFullSizeTouchTargets() {
        val dimensions = mutableStateOf(240 to 320)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                        Box(Modifier.requiredSize(dimensions.value.first.dp, dimensions.value.second.dp)) {
                            FloatingControlsOverlay(actions(), false, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        openCompact()
        for (size in listOf(240 to 320, 320 to 240, 240 to 180)) {
            compose.runOnIdle { dimensions.value = size }
            val strip = compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
            val bounds = strip.fetchSemanticsNode().boundsInRoot
            // Assert against the three layouts the bar is allowed to take rather than guessing
            // from the aspect ratio: the wrapped grid is wider than it is tall, so "wider means a
            // row" would misread it as the horizontal strip.
            val widthDp = bounds.width / density
            val heightDp = bounds.height / density
            val allowed = listOf(
                compactBarLongSide.value to compactBarShortSide.value,
                compactBarShortSide.value to compactBarLongSide.value,
                // Five buttons wrap as three then two when neither a row nor a column fits.
                152f to 104f,
            )
            assertTrue(
                "Unexpected compact layout ${widthDp}x${heightDp} dp for window $size",
                allowed.any { (w, h) ->
                    kotlin.math.abs(widthDp - w) < SHAPE_THRESHOLD_DP &&
                        kotlin.math.abs(heightDp - h) < SHAPE_THRESHOLD_DP
                },
            )
            for (label in listOf(R.string.control_back, R.string.control_home, R.string.control_recents,
                R.string.control_exit, R.string.compact_more_controls)) {
                compose.onNodeWithContentDescription(text(label)).assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
            compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.panel_section_input)).assertDoesNotExist()
        }
    }

    @Test fun controlsDoNotLeakTouchesAndTheRemainingMirroredScreenStillReceivesThem() {
        val touches = mutableListOf<Int>()
        var back = 0
        var home = 0
        var recents = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.requiredSize(320.dp, 540.dp).testTag("mirror-root")) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            ScrcpyMirrorView(context).apply {
                                setVideoSize(320, 540)
                                onTouch = { action, _, _, _, _, _, _, _, _ -> touches += action }
                            }
                        },
                    )
                    FloatingControlsOverlay(
                        actions().copy(onBack = { back++ }, onHome = { home++ }, onRecents = { recents++ }),
                        false, Modifier.fillMaxSize(),
                    )
                }
            }
        }
        openCompact()
        compose.onNodeWithTag("mirror-root").performTouchInput {
            click(Offset(width * 0.5f, height * 0.25f))
            click(Offset(width * 0.05f, height * 0.75f))
        }
        compose.runOnIdle {
            assertEquals(2, touches.count { it == MotionEvent.ACTION_DOWN })
            assertEquals(2, touches.count { it == MotionEvent.ACTION_UP })
            touches.clear()
        }
        compose.onNodeWithContentDescription(text(R.string.control_back)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_home)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_recents)).performClick()
        compose.onNodeWithTag("compact-navigation").performTouchInput {
            click(Offset(width / 2f, 1f))
        }
        compose.runOnIdle {
            assertEquals(1, back)
            assertEquals(1, home)
            assertEquals(1, recents)
            assertTrue("Navigation and its painted background must not inject a remote tap", touches.isEmpty())
        }
    }

    @Test fun availableLetterboxSpaceKeepsNavigationOffTheActualVideoInBothLayoutDirections() {
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val density = compose.activity.resources.displayMetrics.density
        val layout = VideoLayout.fitCentered((320 * density).toInt(), (480 * density).toInt(), 160, 800)
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                    Box(Modifier.requiredSize(320.dp, 480.dp).testTag("letterboxed-mirror")) {
                        FloatingControlsOverlay(actions(), false, Modifier.fillMaxSize(), videoLayout = layout)
                    }
                }
            }
        }
        openCompact()
        for (value in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { direction.value = value }
            val root = compose.onNodeWithTag("letterboxed-mirror").fetchSemanticsNode().boundsInRoot
            val strip = compose.onNodeWithTag("compact-navigation").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val videoLeft = root.left + layout.contentLeft
            val videoRight = videoLeft + layout.contentWidth
            assertTrue("Navigation should use a side margin rather than cover video",
                strip.right <= videoLeft || strip.left >= videoRight)
            compose.onNodeWithContentDescription(text(R.string.control_hide)).assertIsDisplayed()
            for (label in listOf(R.string.control_back, R.string.control_home, R.string.control_recents,
                R.string.control_exit, R.string.compact_more_controls)) {
                compose.onNodeWithContentDescription(text(label)).assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
        }
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.compact_navigation_only)).performClick()
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
    }

    @Test fun theCompactBarCanEndTheSessionWithoutOpeningTheFullPanel() {
        // Leaving used to mean opening the panel first. The button sits beside Back, Home and
        // Recents, so it asks rather than dropping the session on a mis-tap.
        var exits = 0
        var disconnects = 0
        render(actions().copy(onExit = { exits++ }, onDisconnect = { disconnects++ }))
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.control_exit))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(1, exits)
            assertEquals("Leaving is confirmed, not done straight from the bar", 0, disconnects)
        }
    }

    @Test fun moreIsExplicitAndCanReturnToCompactWithoutLeavingTheSession() {
        var disconnected = 0
        render(actions().copy(onDisconnect = { disconnected++ }))
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithTag("compact-navigation").assertDoesNotExist()
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.compact_navigation_only)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, disconnected) }
        compose.onNodeWithContentDescription(text(R.string.control_hide)).performClick()
        compose.onNodeWithTag("compact-navigation").assertDoesNotExist()
        openCompact()
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithText(text(R.string.exit)).performClick()
        compose.runOnIdle { assertEquals(1, disconnected) }
    }

    @Test fun localBackHidesCompactNavigationRatherThanSendingRemoteBack() {
        var remoteBack = 0
        render(actions().copy(onBack = { remoteBack++ }))
        openCompact()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("compact-navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.control_show)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, remoteBack) }
    }

    @Test fun audioWarningsRemainDiscoverableWithoutOpeningTheLargePanelAutomatically() {
        render(actions().copy(audioError = "Audio output needs attention"))
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.compact_audio_notice)))
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithText("Audio output needs attention").assertExists()
    }

    @Test fun compactingClosesTheKeyboardWithoutSendingOrDiscardingItsDraft() {
        val sent = mutableListOf<String>()
        render(actions().copy(onText = { sent += it; true }))
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_keyboard_draft)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo().performTextReplacement("Keep this draft")
        compose.onNodeWithContentDescription(text(R.string.compact_navigation_only)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_text_draft)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo().assertTextContains("Keep this draft")
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun savedCompactAndExpandedModesRestoreWithoutDispatchingRemoteCommands() {
        val mounted = mutableStateOf(true)
        var remoteCommands = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                val holder = rememberSaveableStateHolder()
                if (mounted.value) holder.SaveableStateProvider("controls") {
                    FloatingControlsOverlay(actions().copy(onHome = { remoteCommands++ }), false,
                        Modifier.requiredSize(320.dp, 540.dp))
                }
            }
        }
        openCompact()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithTag("compact-navigation").assertIsDisplayed()
        compose.onNodeWithTag("expanded-controls").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, remoteCommands) }
    }

    @Test fun theQuickSettingsControlOpensTheDeviceShadeWithoutLeavingTheSession() {
        var quickSettings = 0
        var notifications = 0
        render(actions().copy(
            onQuickSettings = { quickSettings++ },
            onNotifications = { notifications++ },
        ))
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_quick_settings))
            .performScrollTo().assertHasClickAction().performClick()
        // Quick settings and the notification shade are separate device panels; one must not
        // stand in for the other.
        compose.runOnIdle { assertEquals(1, quickSettings); assertEquals(0, notifications) }
        compose.onNodeWithTag("expanded-controls").assertIsDisplayed()
    }

    @Test fun theDeviceScreenToggleReportsWhichStateItWillSwitchTo() {
        // The same button both blanks and restores the device display, so its label and
        // description have to describe the action, not the current state.
        var toggles = 0
        val off = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(
                    actions().copy(onToggleDeviceScreen = { toggles++ }, deviceScreenOff = off.value),
                    false, Modifier.requiredSize(320.dp, 540.dp))
            }
        }
        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()

        compose.onNodeWithContentDescription(text(R.string.control_device_screen_off))
            .performScrollTo().assertHasClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performClick()
        compose.runOnIdle { assertEquals(1, toggles); off.value = true }

        // The action reads "turn on", but the state TalkBack announces is the current one: off.
        compose.onNodeWithContentDescription(text(R.string.control_device_screen_on))
            .performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                text(R.string.release_control_screen_off)))
        compose.onNodeWithContentDescription(text(R.string.control_device_screen_off)).assertDoesNotExist()
    }

    @Test fun openingTheDraftFieldClaimsThePhysicalKeyboardAndReleasesItOnClose() {
        // A physical keyboard drives the remote device, except while this draft field is open:
        // the keystrokes are composing local text and must not also be injected remotely.
        val typing = mutableListOf<Boolean>()
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions(), false, Modifier.requiredSize(320.dp, 540.dp),
                    onLocalTypingChanged = { typing += it })
            }
        }
        compose.runOnIdle { assertEquals(listOf(false), typing) }

        openCompact()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        // Driven through the semantics action rather than a coordinate tap: the panel scrolls,
        // and under a loaded emulator a tap dispatched before the scroll settles lands on a
        // neighbouring control, which looks exactly like the toggle not working.
        compose.onNodeWithContentDescription(text(R.string.control_keyboard))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 10_000) { typing.lastOrNull() == true }

        compose.onNodeWithContentDescription(text(R.string.control_keyboard))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 10_000) { typing.lastOrNull() == false }
    }

    @Test fun recordingAddsASixthButtonWithoutPushingAnyOfThemOutOfTheStrip() {
        // The strip's length was a constant fixed at five buttons while a running recording draws
        // six, and the container uses requiredSize, so the last one — More — was laid out beyond
        // the edge. assertIsDisplayed is not enough to catch that: a partially clipped node still
        // reports as displayed. Containment is the property that actually matters.
        val dimensions = mutableStateOf(320 to 540)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                        Box(Modifier.requiredSize(dimensions.value.first.dp, dimensions.value.second.dp)) {
                            FloatingControlsOverlay(actions().copy(recording = true), false, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        openCompact()
        // A wide window gives the row, a tall one the column, a small one the wrapped grid.
        for (size in listOf(320 to 240, 240 to 400, 240 to 180)) {
            compose.runOnIdle { dimensions.value = size }
            val strip = compose.onNodeWithTag("compact-navigation").fetchSemanticsNode().boundsInRoot
            for (label in listOf(R.string.control_back, R.string.control_home, R.string.control_recents,
                R.string.recording_action_stop, R.string.control_exit, R.string.compact_more_controls)) {
                val button = compose.onNodeWithContentDescription(text(label))
                    .assertIsDisplayed().assertHasClickAction().fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "${text(label)} at $button escapes the strip $strip for window $size",
                    button.left >= strip.left - EDGE_TOLERANCE_PX &&
                        button.top >= strip.top - EDGE_TOLERANCE_PX &&
                        button.right <= strip.right + EDGE_TOLERANCE_PX &&
                        button.bottom <= strip.bottom + EDGE_TOLERANCE_PX,
                )
            }
        }
    }

    private fun render(actions: MirrorActions) {
        compose.runOnUiThread {
            compose.activity.setContent {
                FloatingControlsOverlay(actions, false, Modifier.requiredSize(320.dp, 540.dp))
            }
        }
    }

    private fun openCompact() = compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})

    private companion object {
        /** Far below the 144 dp gap between the strip and group layouts, far above pixel rounding. */
        const val SHAPE_THRESHOLD_DP = 8f

        /** Sub-pixel slack, so rounding at the strip's own edge is not read as an escape. */
        const val EDGE_TOLERANCE_PX = 1f
    }
}
