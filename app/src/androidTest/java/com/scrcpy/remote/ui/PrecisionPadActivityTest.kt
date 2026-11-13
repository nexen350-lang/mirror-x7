package com.scrcpy.remote.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pad as a mode of the real mirror screen: switched on from More controls, visible as a
 * cursor and a bar, out of the way while input is locked, kept across recreation, and left with
 * Direct touch.
 */
@RunWith(AndroidJUnit4::class)
class PrecisionPadActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun thePadIsAVisibleModeThatYieldsToTheLockAndSurvivesRecreation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use { scenario ->
            lateinit var model: MirrorViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MirrorViewModel::class.java] }
            try {
                forceConnected(model)
                val view = awaitMirror(scenario)
                main { assertFalse(view.precisionPad) }

                openMoreControls()
                compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).performScrollTo().performClick()
                compose.onNodeWithTag("precision-pad-bar").assertIsDisplayed()
                compose.onNodeWithTag("mirror-surface")
                    .assert(description(text(R.string.precision_pad_surface_description, DEVICE)))
                var start: Pair<Float, Float>? = null
                var speed = 0f
                main {
                    assertTrue(view.precisionPad)
                    start = view.precisionPadCursor
                    speed = view.precisionPadSpeed
                }
                val (startX, startY) = requireNotNull(start) { "The cursor is shown as soon as the pad is on" }

                // A real slide across the screen moves the cursor by that distance times the speed.
                compose.onNodeWithTag("mirror-surface").performTouchInput {
                    val from = Offset(width * 0.3f, height * 0.4f)
                    swipe(from, from + Offset(150f, 90f), durationMillis = 500)
                }
                compose.waitForIdle()
                main {
                    val (x, y) = requireNotNull(view.precisionPadCursor)
                    assertEquals(startX + 150f * speed, x, 2f)
                    assertEquals(startY + 90f * speed, y, 2f)
                }

                compose.onNodeWithTag("precision-pad-drag").performClick()
                compose.waitForIdle()
                main { assertTrue(view.precisionPadDragging) }

                // Locking lifts the press, hides the cursor and puts the lock banner in charge.
                main { model.setInputLocked(true) }
                compose.waitForIdle()
                compose.onNodeWithTag("precision-pad-bar").assertDoesNotExist()
                compose.onNodeWithTag("input-lock-banner").assertIsDisplayed()
                main {
                    assertFalse(view.precisionPadDragging)
                    assertNull(view.precisionPadCursor)
                }
                main { model.setInputLocked(false) }
                compose.onNodeWithTag("precision-pad-bar").assertIsDisplayed()

                scenario.recreate()
                // The fixture has no real request to keep, so the new Activity dials the invalid
                // endpoint again; a live session keeps its request and is not redialled.
                forceConnected(model)
                val recreated = awaitMirror(scenario)
                compose.onNodeWithTag("precision-pad-bar").assertIsDisplayed()
                main { assertTrue("The mode survives recreation", recreated.precisionPad) }

                compose.onNodeWithTag("precision-pad-exit").performClick()
                compose.onNodeWithTag("precision-pad-bar").assertDoesNotExist()
                compose.onNodeWithTag("mirror-surface")
                    .assert(description(text(R.string.mirror_surface_description, DEVICE)))
                main {
                    assertFalse(recreated.precisionPad)
                    assertNull(recreated.precisionPadCursor)
                }
            } finally {
                main { model.disconnect() }
                MirrorSessionService.stop(context)
            }
        }
    }

    /** The fixture's session: the invalid endpoint has failed, then a screen session is shown. */
    private fun forceConnected(model: MirrorViewModel) {
        awaitMain("The invalid endpoint fails without a transport") { model.state.value is MirrorUiState.Failed }
        main {
            model.disconnect()
            flow<MirrorSettings?>(model, "_activeSettings").value =
                MirrorSettings(audioEnabled = false, backgroundAudio = false, autoReconnect = false)
            flow<MirrorUiState>(model, "_state").value = MirrorUiState.Connected(DEVICE, 720, 1280, 1, 1)
        }
    }

    private fun awaitMirror(scenario: ActivityScenario<MirrorActivity>): ScrcpyMirrorView {
        var activity: MirrorActivity? = null
        scenario.onActivity { activity = it }
        awaitMain("The mirror view is on screen and focused", diagnostics = {
            val current = activity
            "activity=$current; state=${current?.lifecycle?.currentState}; finishing=${current?.isFinishing}; " +
                "mirror=${current?.let { findMirror(it.window.decorView) }}; focus=${current?.hasWindowFocus()}; " +
                "model=${current?.let { ViewModelProvider(it)[MirrorViewModel::class.java].state.value }}"
        }) {
            val current = activity
            current != null && findMirror(current.window.decorView) != null && current.hasWindowFocus()
        }
        var view: ScrcpyMirrorView? = null
        main { view = findMirror(requireNotNull(activity).window.decorView) }
        return requireNotNull(view)
    }

    private fun openMoreControls() {
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
    }

    private fun findMirror(view: View): ScrcpyMirrorView? {
        if (view is ScrcpyMirrorView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMirror(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun awaitMain(message: String, diagnostics: (() -> String)? = null, condition: () -> Boolean) {
        try {
            // Waiting through the test rule drives Compose frames as well as real window callbacks.
            compose.waitUntil(timeoutMillis = 15_000) {
                var ready = false
                main { ready = condition() }
                ready
            }
        } catch (error: Throwable) {
            var detail = ""
            if (diagnostics != null) main { detail = diagnostics() }
            throw AssertionError("$message; $detail", error)
        }
    }

    private fun description(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(value))

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
        const val DEVICE = "Pad fixture"
    }
}
