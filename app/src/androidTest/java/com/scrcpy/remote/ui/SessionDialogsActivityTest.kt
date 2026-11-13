package com.scrcpy.remote.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The mirror screen's own questions and notices, on the real activity. */
@RunWith(AndroidJUnit4::class)
class SessionDialogsActivityTest {
    // Its Back from a keyboard leaves touch mode behind, which the rule puts back.
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun confirmingExitWhileAScreenshotIsSavingLeavesNoStayButtonBehind() {
        session { scenario, model ->
            forceConnected(model)
            lateinit var capture: MutableStateFlow<ScreenshotState>
            scenario.onActivity { activity ->
                capture = flow(ViewModelProvider(activity)[ScreenshotViewModel::class.java], "_state")
                // An export into a chosen document is still running, so leaving has to wait for it.
                capture.value = ScreenshotState(busy = true, pendingToken = "export-in-flight", pickerLaunched = true)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithText(text(R.string.session_leave_title)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.exit)).performClick()
            compose.onNodeWithText(text(R.string.session_leave_title)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.session_stay)).assertDoesNotExist()
            scenario.onActivity { assertFalse("Leaving waits for the screenshot", it.isFinishing) }
            scenario.onActivity { capture.value = ScreenshotState(notice = "saved") }
            // Read from this thread: the scenario waits for the main thread to settle first.
            val deadline = System.currentTimeMillis() + 15_000
            while (scenario.state != Lifecycle.State.DESTROYED) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("Once the export settles the ended session closes")
                Thread.sleep(100)
            }
        }
    }

    @Test fun theApplySettingsQuestionGoesOnceNothingIsWaitingToApply() {
        session { _, model ->
            forceConnected(model, pending = true)
            compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
            compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
            compose.onNodeWithText(text(R.string.session_apply_settings)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.session_apply_settings_title)).assertIsDisplayed()
            // The session it was about goes away, as after a drop or a restored process.
            main { flow<MirrorUiState>(model, "_state").value = MirrorUiState.Connecting(text(R.string.session_starting)) }
            compose.onNodeWithText(text(R.string.session_apply_settings_title)).assertDoesNotExist()
        }
    }

    @Test fun aNoticeOnScreenWhenTheAppIsLeftIsShownAgainOnReturn() {
        session { scenario, model ->
            awaitMain("The invalid endpoint fails without a transport") { model.state.value is MirrorUiState.Failed }
            main { model.showNotice(NOTICE) }
            compose.onNodeWithText(NOTICE).assertIsDisplayed()
            scenario.moveToState(Lifecycle.State.CREATED)
            // Longer than a short notice stays up.
            Thread.sleep(6_000)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText(NOTICE).assertIsDisplayed()
        }
    }

    @Test fun whileMoreControlsIsOpenThePadBarAndTheScreenAreOutOfAccessibilityReach() {
        session { scenario, model ->
            forceConnected(model)
            openMore()
            compose.onNodeWithContentDescription(text(R.string.precision_pad_toggle)).performScrollTo().performClick()
            compose.onNodeWithTag("precision-pad-bar").assertIsDisplayed()
            openMore()
            // TalkBack acts through these nodes directly, so covering them with the panel is not enough.
            compose.onNodeWithTag("precision-pad-bar").assertDoesNotExist()
            compose.onNodeWithTag("mirror-surface").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
            assertEquals("The mirror view itself is out of reach too",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, mirrorViewImportance(scenario))
            compose.onNodeWithContentDescription(text(R.string.compact_navigation_only)).performClick()
            compose.onNodeWithTag("precision-pad-bar").assertIsDisplayed()
            compose.onNodeWithTag("mirror-surface").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, mirrorViewImportance(scenario))
        }
    }

    @Test fun aMouseBackButtonGoesToTheDeviceWithoutAlsoAskingToEndTheSession() {
        // A mouse's Back button arrives twice: as a button press the screen sends to the device, and
        // as a Back key Android makes from it. Android 13+ gave that key to the back handling before
        // the screen could drop it, so one click went back on the device and asked to end the session.
        session { _, model ->
            forceConnected(model)
            compose.waitForIdle()
            pressBack(InputDevice.SOURCE_MOUSE)
            compose.onNodeWithText(text(R.string.session_leave_title)).assertDoesNotExist()
            // Back from anything else is this phone's own, and still asks.
            pressBack(InputDevice.SOURCE_KEYBOARD)
            compose.onNodeWithText(text(R.string.session_leave_title)).assertIsDisplayed()
        }
    }

    private fun pressBack(source: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(down, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)
            assertTrue("Back reached the screen", instrumentation.uiAutomation.injectInputEvent(event, true))
        }
        compose.waitForIdle()
    }

    private fun mirrorViewImportance(scenario: ActivityScenario<MirrorActivity>): Int {
        var importance = -1
        scenario.onActivity { activity ->
            fun find(view: View): ScrcpyMirrorView? = view as? ScrcpyMirrorView
                ?: (view as? ViewGroup)?.let { group ->
                    (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
                }
            importance = checkNotNull(find(activity.window.decorView)) { "No mirror view" }.importantForAccessibility
        }
        return importance
    }

    private fun openMore() {
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
    }

    private fun session(block: (ActivityScenario<MirrorActivity>, MirrorViewModel) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use { scenario ->
            lateinit var model: MirrorViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MirrorViewModel::class.java] }
            try {
                block(scenario, model)
            } finally {
                main { model.disconnect() }
                MirrorSessionService.stop(context)
            }
        }
    }

    /**
     * The fixture's session: the invalid endpoint has failed, then a screen session is shown. With
     * [pending], what it runs differs from the saved settings, so they wait to be applied.
     */
    private fun forceConnected(model: MirrorViewModel, pending: Boolean = false) {
        awaitMain("The invalid endpoint fails without a transport") { model.state.value is MirrorUiState.Failed }
        val configured = DeviceRepository.get(context).settings.value
        main {
            model.disconnect()
            flow<MirrorSettings?>(model, "_activeSettings").value =
                (if (pending) configured.copy(maxFps = if (configured.maxFps == 30) 60 else 30) else configured)
                    .copy(audioEnabled = false, backgroundAudio = false, autoReconnect = false)
            flow<MirrorUiState>(model, "_state").value = MirrorUiState.Connected(DEVICE, 720, 1280, 1, 1)
        }
    }

    private fun awaitMain(message: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                var ready = false
                main { ready = condition() }
                ready
            }
        } catch (error: Throwable) {
            throw AssertionError(message, error)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun text(id: Int) = context.getString(id)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
        const val DEVICE = "Dialog fixture"
        const val NOTICE = "A notice that must be seen"
    }
}
