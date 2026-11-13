package com.scrcpy.remote.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.session.ConnectionRecoveryPolicy
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.session.SessionGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MirrorActivityIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun inputLockSurvivesRecreationAndRetryButNotAnExplicitDifferentTarget() {
        withFailedSession { scenario, model, generations ->
            scenario.onActivity { model.setInputLocked(true) }
            scenario.recreate()
            scenario.onActivity {
                assertSame(model, ViewModelProvider(it)[MirrorViewModel::class.java])
                assertTrue(model.inputState.value.locked)
            }
            val before = generations.token()
            scenario.onActivity { activity ->
                activity.startActivity(MirrorActivity.newIntent(activity, HOST, INVALID_PORT))
            }
            awaitCondition("The failed target retries while retaining Input Lock") {
                generations.token() > before && model.state.value is MirrorUiState.Failed
            }
            val retried = generations.token()
            scenario.onActivity {
                assertTrue(model.inputState.value.locked)
                it.startActivity(MirrorActivity.newIntent(it, HOST, INVALID_PORT,
                    linkOptions = LinkRequest(maxFps = 30).toQuery()))
            }
            awaitCondition("Changing options for the same endpoint preserves the lock") {
                generations.token() > retried && model.state.value is MirrorUiState.Failed
            }
            scenario.onActivity {
                assertTrue(model.inputState.value.locked)
                it.startActivity(MirrorActivity.newIntent(it, "different-input-target.example", INVALID_PORT))
            }
            awaitCondition("The explicit new target starts unlocked") {
                model.endpointLabel().startsWith("different-input-target.example:") &&
                    model.state.value is MirrorUiState.Failed && !model.inputState.value.locked
            }
        }
    }

    @Test
    fun repeatingTheSameTargetRetriesAFailedSession() {
        withFailedSession { scenario, model, generations ->
            val previous = generations.token()
            scenario.onActivity { activity ->
                activity.startActivity(MirrorActivity.newIntent(activity, HOST, INVALID_PORT))
            }
            awaitCondition("The repeated connection request must start a new attempt after failure") {
                generations.token() > previous && model.state.value is MirrorUiState.Failed
            }
            scenario.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[MirrorViewModel::class.java])
            }
        }
    }

    @Test
    fun repeatingTheSameTargetStartsADisconnectedSession() {
        withFailedSession { scenario, model, generations ->
            scenario.onActivity { model.disconnect() }
            val previous = generations.token()
            scenario.onActivity { activity ->
                activity.startActivity(MirrorActivity.newIntent(activity, HOST, INVALID_PORT))
            }
            awaitCondition("The repeated connection request must not leave the session disconnected") {
                generations.token() > previous && model.state.value is MirrorUiState.Failed
            }
        }
    }

    @Test
    fun recreatingAFailedSessionKeepsItsAttemptAndRecoveryAllowance() {
        withFailedSession { scenario, model, generations ->
            val failed = model.state.value
            scenario.onActivity {
                setField(model, "recovery", ConnectionRecoveryPolicy.State(retriesUsed = 1))
                setField(model, "retryPort", 4321)
            }
            val before = generations.token()
            // A font-scale, locale or keyboard change rebuilds this screen. That is not a new
            // request: it must neither dial again nor hand the request a fresh retry allowance.
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(200)
            scenario.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[MirrorViewModel::class.java])
                assertEquals("Recreation must not start another attempt", before, generations.token())
                assertEquals(failed, model.state.value)
                assertEquals(ConnectionRecoveryPolicy.State(retriesUsed = 1), field(model, "recovery"))
                assertEquals("A rediscovered port must survive recreation", 4321, field(model, "retryPort"))
            }
        }
    }

    @Test
    fun recreatingAnEndedSessionDoesNotReconnectWhileItsRecordingIsPending() {
        withEndedRecording { scenario, model, generations, recording ->
            val retired = generations.token()
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            scenario.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[MirrorViewModel::class.java])
                assertEquals("Recreation must not start an ended session", retired, generations.token())
                assertEquals(MirrorUiState.Disconnected, model.state.value)
                assertEquals(recording, model.finishedRecording.value)
                assertTrue(recording.isFile)
                assertFalse(activity.isFinishing)
            }
        }
    }

    @Test
    fun discardingTheRecordingStillClosesAnEndedSessionAfterRecreation() {
        withEndedRecording { scenario, model, _, recording ->
            scenario.recreate()
            scenario.onActivity { model.consumeFinishedRecording(keep = false) }
            awaitCondition("Discard must finish the recreated session owner") {
                scenario.state == Lifecycle.State.DESTROYED
            }
            assertFalse(recording.exists())
        }
    }

    @Test
    fun cancellingThenSavingTheRecordingStillClosesTheRecreatedSession() {
        withEndedRecording { scenario, model, _, recording ->
            val output = File.createTempFile("ended-recording-export-", ".mp4", context.cacheDir)
            val payload = recording.readBytes()
            try {
                scenario.recreate()
                scenario.onActivity { activity ->
                    model.saveFinishedRecording(null)
                    assertEquals(recording, model.finishedRecording.value)
                    assertFalse("Cancelling the picker is not choosing Discard", activity.isFinishing)
                }
                scenario.recreate()
                scenario.onActivity { model.saveFinishedRecording(Uri.fromFile(output)) }
                awaitCondition("A completed save must finish the recreated session owner") {
                    scenario.state == Lifecycle.State.DESTROYED
                }
                assertArrayEquals(payload, output.readBytes())
            } finally {
                output.delete()
            }
        }
    }

    @Test
    fun endingTheSessionWaitsForAScreenshotStillBeingSaved() {
        withFailedSession { scenario, model, _ ->
            lateinit var capture: MutableStateFlow<ScreenshotState>
            scenario.onActivity { activity ->
                val screenshots = ViewModelProvider(activity)[ScreenshotViewModel::class.java]
                @Suppress("UNCHECKED_CAST")
                capture = ScreenshotViewModel::class.java.getDeclaredField("_state")
                    .apply { isAccessible = true }.get(screenshots) as MutableStateFlow<ScreenshotState>
                // The picker has returned and the export into the chosen document is running in
                // this screen's view model; finishing now would cancel it half written.
                capture.value = ScreenshotState(busy = true, pendingToken = "export-in-flight", pickerLaunched = true)
                MirrorActivity::class.java.getDeclaredMethod("leaveSession").run {
                    isAccessible = true
                    invoke(activity)
                }
                assertEquals(MirrorUiState.Disconnected, model.state.value)
                assertFalse("Leaving must wait for the screenshot export", activity.isFinishing)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.onActivity { capture.value = ScreenshotState(notice = "saved") }
            awaitCondition("Once the export settles the ended session closes") {
                scenario.state == Lifecycle.State.DESTROYED
            }
        }
    }

    @Test
    fun anExplicitNewRequestCancelsTheDeferredExitWithoutDiscardingTheRecording() {
        withEndedRecording { scenario, model, generations, recording ->
            val retired = generations.token()
            scenario.onActivity { activity ->
                activity.startActivity(MirrorActivity.newIntent(activity, "new-target.example", INVALID_PORT))
            }
            awaitCondition("The explicit target must be connected independently of the old exit") {
                generations.token() > retired && model.state.value is MirrorUiState.Failed &&
                    model.endpointLabel().startsWith("new-target.example:")
            }
            scenario.onActivity { activity ->
                assertEquals(recording, model.finishedRecording.value)
                model.consumeFinishedRecording(keep = false)
                assertFalse("Resolving the old recording must not exit the new session", activity.isFinishing)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(scenario.state.isAtLeast(Lifecycle.State.STARTED))
        }
    }

    @Test fun aRebuiltScreenReturnsToTheTargetItLastShowedNotTheOneItWasLaunchedFor() {
        val launch = MirrorActivity.newIntent(context, HOST, INVALID_PORT)
        ActivityScenario.launch<MirrorActivity>(launch).use { scenario ->
            val model = modelOf(scenario)
            awaitCondition("The first target fails without contacting a device") { model.state.value is MirrorUiState.Failed }
            scenario.onActivity { it.startActivity(MirrorActivity.newIntent(it, LATER_HOST, INVALID_PORT)) }
            awaitCondition("The later target replaces the first") {
                model.endpointLabel() == "$LATER_HOST:$INVALID_PORT" && model.state.value is MirrorUiState.Failed
            }
            rebuildFromLaunchIntent(scenario, launch)
            assertEquals("$LATER_HOST:$INVALID_PORT", model.endpointLabel())
            scenario.onActivity { assertEquals(LATER_HOST, MirrorTarget.from(it.intent)?.host) }
        }
    }

    @Test fun aRebuiltScreenDoesNotTakeAgainAFileItAlreadyHandled() {
        val copy = File.createTempFile("rebuilt-share-", ".bin", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val launch = MirrorActivity.newIntent(context, HOST, INVALID_PORT, sharePath = copy.path, shareName = "notes.txt")
        // A queued file holds the process with the session service, whose notification Android 13+
        // asks about first; the question would pause the screen being rebuilt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        try {
            ActivityScenario.launch<MirrorActivity>(launch).use { scenario ->
                val model = modelOf(scenario)
                awaitCondition("The failed session gives up the file it could not send") {
                    model.state.value is MirrorUiState.Failed && field(model, "pendingShare") == null
                }
                rebuildFromLaunchIntent(scenario, launch)
                assertNull("A file already handled must not be queued again", field(model, "pendingShare"))
            }
        } finally {
            copy.delete()
        }
    }

    /**
     * Rebuilds the screen as Android does after its process was killed or the activity discarded:
     * from the intent that first launched it, with the state it saved. A switch made through
     * [setIntent] and the extras taken from the intent since live only in memory, and a plain
     * recreate keeps them, which is what hid this.
     *
     * Once the screen has saved its state, its live intent is turned back into the first one; the
     * recreate then hands the new screen that intent, as the system would.
     */
    private fun rebuildFromLaunchIntent(scenario: ActivityScenario<MirrorActivity>, launch: Intent) {
        val application = context as Application
        val relaunchAsFirstLaunched = object : Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                if (activity is MirrorActivity) activity.intent.replaceExtras(launch)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(relaunchAsFirstLaunched)
        try {
            scenario.recreate()
        } finally {
            application.unregisterActivityLifecycleCallbacks(relaunchAsFirstLaunched)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun modelOf(scenario: ActivityScenario<MirrorActivity>): MirrorViewModel {
        lateinit var model: MirrorViewModel
        scenario.onActivity { model = ViewModelProvider(it)[MirrorViewModel::class.java] }
        return model
    }

    private fun withEndedRecording(
        assertions: (ActivityScenario<MirrorActivity>, MirrorViewModel, SessionGeneration, File) -> Unit,
    ) {
        withFailedSession { scenario, model, generations ->
            val directory = File(context.cacheDir, MirrorViewModel.RECORDING_DIRECTORY).apply { mkdirs() }
            val recording = File.createTempFile("ended-recording-", ".mp4", directory)
            recording.writeBytes(byteArrayOf(1, 3, 5, 7))
            try {
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    val pending = MirrorViewModel::class.java.getDeclaredField("_finishedRecording").run {
                        isAccessible = true
                        get(model) as MutableStateFlow<File?>
                    }
                    pending.value = recording
                    MirrorActivity::class.java.getDeclaredMethod("leaveSession").run {
                        isAccessible = true
                        invoke(activity)
                    }
                    assertEquals(MirrorUiState.Disconnected, model.state.value)
                    assertEquals(recording, model.finishedRecording.value)
                    assertFalse(activity.isFinishing)
                }
                assertions(scenario, model, generations, recording)
            } finally {
                recording.delete()
            }
        }
    }

    private fun withFailedSession(
        block: (ActivityScenario<MirrorActivity>, MirrorViewModel, SessionGeneration) -> Unit,
    ) {
        // Port zero is rejected before the ADB transport or identity is created.
        ActivityScenario.launch<MirrorActivity>(
            MirrorActivity.newIntent(context, HOST, INVALID_PORT),
        ).use { scenario ->
            lateinit var model: MirrorViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[MirrorViewModel::class.java]
            }
            awaitCondition("The invalid endpoint must fail without contacting a device") {
                model.state.value is MirrorUiState.Failed
            }
            val field = MirrorViewModel::class.java.getDeclaredField("generations").apply {
                isAccessible = true
            }
            block(scenario, model, field.get(model) as SessionGeneration)
        }
    }

    private fun setField(model: MirrorViewModel, name: String, value: Any?) {
        MirrorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
    }

    private fun field(model: MirrorViewModel, name: String): Any? =
        MirrorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model)

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
        const val LATER_HOST = "later-target.example"
    }
}
