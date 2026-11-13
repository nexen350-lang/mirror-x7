package com.scrcpy.remote.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PictureInPictureParams
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.session.FileTransferState
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.session.VideoDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** Real native PiP around the production Activity; no remote device or ADB listener is needed. */
@RunWith(AndroidJUnit4::class)
class CameraPipActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val userId = shellOutput("am get-current-user").trim().toInt().also { require(it >= 0) }
    private val paintedSurfaces = mutableMapOf<SurfaceHolder, Int>()
    private var originalImmersiveConfirmation: String? = null
    private var immersiveConfirmationRead = false

    @Before fun rememberSystemImmersiveConfirmation() {
        originalImmersiveConfirmation = readImmersiveConfirmation()
        immersiveConfirmationRead = true
    }

    @After fun restoreSystemImmersiveConfirmation() {
        if (!immersiveConfirmationRead) return
        instrumentation.uiAutomation.waitForIdle(300, 5_000)
        if (readImmersiveConfirmation() == originalImmersiveConfirmation) return
        val original = originalImmersiveConfirmation
        val command = if (original == null) {
            // Android 8 rejects --user for "settings delete"; the provider supports scoped deletion.
            "content delete --user $userId --uri content://settings/secure/immersive_mode_confirmations"
        } else {
            require(original.none { it.isWhitespace() }) { "Unexpected fullscreen-education preference" }
            // UiAutomation executes the program directly; shell quotes would become stored data.
            "settings --user $userId put secure immersive_mode_confirmations $original"
        }
        shellOutput(command)
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (readImmersiveConfirmation() != original && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            shellOutput(command)
        }
        assertEquals("The fixture must restore Android's fullscreen-education preference",
            originalImmersiveConfirmation, readImmersiveConfirmation())
    }

    @Test fun fullscreenEducationCleanupRestoresBothAbsentAndConfirmedPreferencesForTheCurrentUser() {
        val preserved = originalImmersiveConfirmation
        try {
            originalImmersiveConfirmation = null
            shellOutput("settings --user $userId put secure immersive_mode_confirmations confirmed")
            assertEquals("confirmed", readImmersiveConfirmation())
            restoreSystemImmersiveConfirmation()
            assertNull(readImmersiveConfirmation())

            originalImmersiveConfirmation = "confirmed"
            restoreSystemImmersiveConfirmation()
            assertEquals("confirmed", readImmersiveConfirmation())
        } finally {
            originalImmersiveConfirmation = preserved
        }
    }

    @Test fun physicalVirtualConnectingFailedAndDisconnectedSessionsNeverReachThePlatformEntryCall() {
        withCamera { fixture ->
            main {
                val settings = flow<MirrorSettings?>(fixture.model, "_activeSettings")
                val states = flow<MirrorUiState>(fixture.model, "_state")
                for (screen in listOf(MirrorSettings(), MirrorSettings(virtualDisplay = "1280x720"))) {
                    settings.value = screen
                    assertRejected(fixture, R.string.camera_pip_camera_only)
                }
                settings.value = cameraSettings()
                for (state in listOf(MirrorUiState.Connecting("Starting"),
                    MirrorUiState.Failed("Failed"), MirrorUiState.Disconnected)) {
                    states.value = state
                    assertRejected(fixture, R.string.camera_pip_not_live)
                }
            }
        }
    }

    @Test fun entryReadsCurrentArtifactAndTransferStateRatherThanAStaleEnabledButton() {
        withCamera { fixture ->
            val original = File(context.cacheDir, "camera-pip-protected-${System.nanoTime()}.mp4")
            original.writeBytes(byteArrayOf(1, 3, 5, 7))
            try {
                main {
                    val recording = flow<Boolean>(fixture.model, "_recording")
                    recording.value = true
                    assertRejected(fixture, R.string.camera_pip_busy)
                    recording.value = false

                    val finished = flow<File?>(fixture.model, "_finishedRecording")
                    finished.value = original
                    assertRejected(fixture, R.string.camera_pip_busy)
                    assertEquals(original, fixture.model.finishedRecording.value)
                    finished.value = null

                    val exporting = flow<Boolean>(fixture.model, "_recordingSaveInProgress")
                    exporting.value = true
                    assertRejected(fixture, R.string.camera_pip_busy)
                    exporting.value = false

                    val transfer = flow<FileTransferState>(fixture.model, "_fileTransfer")
                    transfer.value = FileTransferState(running = true)
                    assertRejected(fixture, R.string.camera_pip_busy)
                    transfer.value = FileTransferState()

                    val picker = flow<Boolean>(fixture.model, "_transferHoldsProcess")
                    picker.value = true
                    assertRejected(fixture, R.string.camera_pip_busy)
                    picker.value = false

                    val capture = flow<ScreenshotState>(fixture.screenshots, "_state")
                    for (state in listOf(
                        ScreenshotState(busy = true),
                        ScreenshotState(busy = true, pendingToken = "pending", pickerLaunched = true),
                        ScreenshotState(busy = true, shareFile = original, pickerLaunched = true),
                    )) {
                        capture.value = state
                        assertRejected(fixture, R.string.camera_pip_busy)
                        capture.value = ScreenshotState()
                    }
                }
                assertArrayEquals(byteArrayOf(1, 3, 5, 7), original.readBytes())
                compose.onNodeWithContentDescription(text(R.string.control_show)).assertIsDisplayed()
            } finally {
                original.delete()
            }
        }
    }

    @Test fun platformRefusalAndIllegalStateRestoreControlsAndExplainTheFailure() {
        withCamera { fixture ->
            val attempts: List<(PictureInPictureParams) -> Boolean> = listOf(
                { false },
                { throw IllegalStateException("PiP disabled by this device") },
                { throw IllegalArgumentException("PiP unavailable") },
                { throw SecurityException("PiP access denied") },
            )
            for (attempt in attempts) {
                awaitMain("Foreground camera before simulating platform refusal",
                    diagnostics = { fixtureStatus(fixture) }) {
                    if (fixture.activity.lifecycle.currentState != Lifecycle.State.RESUMED ||
                        !fixture.activity.hasWindowFocus()
                    ) false else {
                        var called = false
                        assertFalse(fixture.activity.requestCameraPictureInPicture { params ->
                            called = true
                            attempt(params)
                        })
                        assertTrue("The refusal must come from the platform callback, not an eligibility gate", called)
                        assertEquals(text(R.string.camera_pip_refused), fixture.model.notice.value)
                        assertFalse(fixture.activity.isInPictureInPictureMode)
                        true
                    }
                }
                compose.onNodeWithContentDescription(text(R.string.control_show)).assertIsDisplayed()
                compose.onNodeWithTag("mirror-surface").assertIsDisplayed()
            }
        }
    }

    @Test fun nativeParametersUseVideoBoundsAndClampBothAspectExtremes() {
        withCamera { fixture ->
            main {
                val states = flow<MirrorUiState>(fixture.model, "_state")
                val original = states.value as MirrorUiState.Connected
                val sizes = listOf(
                    Triple(1080, 1920, Rational(9, 16)),
                    Triple(1920, 1080, Rational(16, 9)),
                    Triple(Int.MAX_VALUE, 1, Rational(239, 100)),
                    Triple(1, Int.MAX_VALUE, Rational(100, 239)),
                )
                val bounds = Rect()
                assertTrue(fixture.view.screenshotSurface.getGlobalVisibleRect(bounds))
                for ((width, height, ratio) in sizes) {
                    states.value = original.copy(videoWidth = width, videoHeight = height)
                    var params: PictureInPictureParams? = null
                    assertFalse(fixture.activity.requestCameraPictureInPicture { params = it; false })
                    val expected = PictureInPictureParams.Builder()
                        .setAspectRatio(ratio).setSourceRectHint(bounds).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(false)
                        }.build()
                    // The getters are hidden platform APIs on older Android. Compare through
                    // Parcelable instead, without reflection into Android or an assumed layout.
                    assertArrayEquals(parcelBytes(expected), parcelBytes(requireNotNull(params)))
                }
                states.value = original
            }
        }
    }

    @Test fun anOpenSessionDialogBlocksProgrammaticAndStaleEntryRequests() {
        withCamera { fixture ->
            openTools()
            compose.onNodeWithContentDescription(text(R.string.details_action)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.details_title)).assertIsDisplayed()
            main { assertRejected(fixture, R.string.camera_pip_busy) }
            main { fixture.activity.onBackPressedDispatcher.onBackPressed() }
        }
    }

    @Test fun actualNativePipKeepsTheSurfaceRenderingAndRestoresControlsAndDiagnostics() {
        withCamera { fixture ->
            val cameraLabel = context.getString(R.string.camera_surface_description, "Camera fixture")
            compose.onNodeWithContentDescription(cameraLabel).assertIsDisplayed()
            compose.onNodeWithContentDescription(
                context.getString(R.string.mirror_surface_description, "Camera fixture"),
            ).assertDoesNotExist()
            openTools()
            // A camera session's overlay is its own tile: a camera takes no touches to calibrate.
            compose.onNodeWithContentDescription(text(R.string.panel_overlay_description)).performScrollTo().performClick()
            awaitMain("Diagnostics enabled") { fixture.model.diagnosticsEnabled }
            paintAndCheck(fixture.view, Color.GREEN)
            val camera = fixture.model.activeSettings.value?.camera
            compose.onNodeWithContentDescription(text(R.string.camera_pip_action))
                .performScrollTo().assertIsDisplayed().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick)
            withAccessibleWindows {
                var retryAfterEducation = false
                awaitMain("Native camera PiP", diagnostics = { fixtureStatus(fixture) }, beforeCheck = {
                    // Android may show its delayed fullscreen education after the first tap.
                    // Only retry after dismissing that observed system interruption.
                    if (dismissImmersiveConfirmation()) retryAfterEducation = true
                    if (retryAfterEducation) {
                        var foreground = false
                        main {
                            foreground = fixture.activity.hasWindowFocus() &&
                                !fixture.activity.isInPictureInPictureMode
                        }
                        if (foreground) {
                            compose.onNodeWithContentDescription(text(R.string.camera_pip_action))
                                .performSemanticsAction(SemanticsActions.OnClick)
                            retryAfterEducation = false
                        }
                    }
                }) {
                    fixture.pipEntryCompleted.get() && fixture.activity.isInPictureInPictureMode
                }
            }
            awaitMain("PiP remains started with diagnostics disabled") {
                fixture.activity.lifecycle.currentState == Lifecycle.State.STARTED &&
                    !fixture.model.diagnosticsEnabled
            }
            main {
                assertSame(fixture.view, findMirror(fixture.activity.window.decorView))
                assertTrue("Pause must not detach the video sink", field<Boolean>(fixture.model, "visible"))
                assertFalse("Hidden details must not keep their sampler active",
                    flow<Boolean>(fixture.model, "_statsWatched").value)
            }
            assertLocalPipVideoUi(fixture, cameraLabel)
            paintAndCheck(fixture.view, Color.MAGENTA)
            returnToCamera(fixture)
            compose.onNodeWithContentDescription(text(R.string.control_show)).assertIsDisplayed()
            main {
                assertSame(fixture.view, findMirror(fixture.activity.window.decorView))
                assertEquals(camera, fixture.model.activeSettings.value?.camera)
                assertTrue(fixture.model.diagnosticsEnabled)
            }
            paintAndCheck(fixture.view, Color.BLUE)
        }
    }

    @Test fun homeDoesNotEnterPipOrDisconnectEitherBeforeEntryOrAfterReturning() {
        withCamera { fixture ->
            for (afterPip in listOf(false, true)) {
                if (afterPip) {
                    enterPip(fixture)
                    returnToCamera(fixture)
                }
                assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
                awaitMain("Ordinary Home stops the fullscreen Activity") {
                    fixture.activity.lifecycle.currentState == Lifecycle.State.CREATED
                }
                main {
                    assertFalse(fixture.activity.isInPictureInPictureMode)
                    assertFalse(fixture.activity.isFinishing)
                    assertTrue(fixture.model.state.value is MirrorUiState.Connected)
                    assertFalse(field<Boolean>(fixture.model, "visible"))
                    assertRejected(fixture, R.string.camera_pip_not_foreground)
                }
                returnToCamera(fixture)
            }
        }
    }

    @Test fun aFailedPipStreamShowsACompactAccessibleStateAndReturnsToFullFailureControls() {
        withCamera { fixture ->
            enterPip(fixture)
            lateinit var owner: ViewRootForTest
            main {
                owner = requireNotNull(findComposeRoot(fixture.activity.window.decorView))
                flow<MirrorUiState>(fixture.model, "_state").value = MirrorUiState.Failed("Fixture stream stopped")
            }
            val cardBounds = awaitLocalPipFailureCard(fixture, owner)
            assertWindowFailureCardPixels(fixture, owner, cardBounds)
            // Android 8 may expose only virtual PiP actions, not the paused app's text.
            // Retain the additional native accessibility integration assertion on API 36+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                assertNativePipContent(
                    fixture,
                    expected = listOf(text(R.string.camera_pip_failed), text(R.string.camera_pip_return)),
                    absent = listOf("Fixture stream stopped", text(R.string.control_show),
                        text(R.string.control_hide), text(R.string.capture_share_screenshot)),
                )
            }
            // A native expand does not restart a failed target; unlike a new connection intent,
            // it simply returns to the existing error and its retry/exit controls.
            clickNativePipButton(fixture, dismiss = false)
            awaitMain("Native expand from failed PiP") {
                !fixture.activity.isInPictureInPictureMode &&
                    fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            compose.onNodeWithText("Fixture stream stopped").assertIsDisplayed()
            compose.onNodeWithText(text(R.string.reconnect))
                .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            compose.onNodeWithTag("camera-pip-status").assertDoesNotExist()
        }
    }

    @Test fun nativeDismissDisconnectsAndFinishesInsteadOfLeavingAHiddenSession() {
        withCamera { fixture ->
            enterPip(fixture)
            clickNativePipButton(fixture, dismiss = true)
            awaitMain("Native close releases the camera Activity") { fixture.activity.isDestroyed }
            assertEquals(MirrorUiState.Disconnected, fixture.model.state.value)
            assertFalse(fixture.model.recording.value)
        }
    }

    @Test fun lockingAndUnlockingTheControllerDoesNotDismissItsCameraPipSession() {
        withCamera { fixture ->
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            main {
                flow<MirrorSettings?>(fixture.model, "_activeSettings").value =
                    cameraSettings().copy(audioEnabled = true, backgroundAudio = true)
            }
            enterPip(fixture)
            val connected = fixture.model.state.value
            try {
                shellOutput("input keyevent KEYCODE_SLEEP")
                awaitMain("A sleeping display hides the PiP owner", diagnostics = { fixtureStatus(fixture) }) {
                    !power.isInteractive &&
                        !fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
                main {
                    assertEquals("Screen lock is not the native Close action", connected, fixture.model.state.value)
                    assertFalse(fixture.activity.isFinishing)
                    assertFalse(field<Boolean>(fixture.model, "visible"))
                    assertTrue(fixture.model.activeSettings.value?.backgroundAudio == true)
                }
            } finally {
                shellOutput("input keyevent KEYCODE_WAKEUP")
                shellOutput("wm dismiss-keyguard")
                awaitMain("The test must leave the controller awake and unlocked") {
                    power.isInteractive && !keyguard.isKeyguardLocked
                }
            }
            awaitMain("Unlock restores the existing visible camera owner", diagnostics = { fixtureStatus(fixture) }) {
                fixture.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    field<Boolean>(fixture.model, "visible")
            }
            main { fixture.activity.startActivity(MirrorActivity.newIntent(fixture.activity, HOST, INVALID_PORT)) }
            awaitMain("Returning to the unlocked camera reuses the existing session",
                diagnostics = { fixtureStatus(fixture) }) {
                !fixture.activity.isInPictureInPictureMode &&
                    !fixture.pipEntryCompleted.get() &&
                    fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    fixture.activity.hasWindowFocus()
            }
            compose.waitForIdle()
            main {
                assertEquals(connected, fixture.model.state.value)
                assertTrue(fixture.model.activeSettings.value?.backgroundAudio == true)
            }
            enterPip(fixture)
            clickNativePipButton(fixture, dismiss = true)
            awaitMain("An actual native close still releases the camera owner") { fixture.activity.isDestroyed }
            assertEquals(MirrorUiState.Disconnected, fixture.model.state.value)
        }
    }

    @Test fun aRecordingArrivingDuringNativeEntryReturnsToItsOfferWithoutDeletingIt() {
        withCamera { fixture ->
            val originalTarget = MirrorTarget.from(fixture.activity.intent)
            val file = File(context.cacheDir, "camera-pip-entry-race-${System.nanoTime()}.mp4")
            file.writeBytes(byteArrayOf(2, 4, 6, 8))
            try {
                main {
                    assertTrue(fixture.activity.requestCameraPictureInPicture { params ->
                        // Models a completion delivered after the button's eligibility check.
                        flow<File?>(fixture.model, "_finishedRecording").value = file
                        fixture.activity.enterPictureInPictureMode(params)
                    })
                }
                withAccessibleWindows {
                    var offerFocused = false
                    awaitMain("Pending recording returns to its focused full-sized offer",
                        diagnostics = { fixtureStatus(fixture) }, beforeCheck = {
                            dismissImmersiveConfirmation()
                            offerFocused = instrumentation.uiAutomation.windows.any { window ->
                                val root = window.root
                                if (root == null || !window.isFocused || window.isInPictureInPictureMode ||
                                    root.packageName != context.packageName
                                ) false else {
                                    val labels = mutableSetOf<String>()
                                    collectVisibleText(root, labels)
                                    labels.any { it.contains(text(R.string.recording_save_title)) }
                                }
                            }
                        }) {
                        !fixture.activity.isInPictureInPictureMode &&
                            fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED && offerFocused
                    }
                }
                compose.onNodeWithText(text(R.string.recording_save_title)).assertIsDisplayed()
                assertEquals(file, fixture.model.finishedRecording.value)
                assertArrayEquals(byteArrayOf(2, 4, 6, 8), file.readBytes())
                assertFalse(fixture.activity.isFinishing)
                assertEquals("The private return intent must not replace the connection target",
                    originalTarget, MirrorTarget.from(fixture.activity.intent))
                fixture.scenario.onActivity {
                    assertSame(fixture.activity, it)
                    assertSame(fixture.model, ViewModelProvider(it)[MirrorViewModel::class.java])
                }
                main { fixture.model.consumeFinishedRecording(keep = true) }
            } finally {
                file.delete()
            }
        }
    }

    @Test fun sameTargetAndPrivateReturnIntentsExpandTheExistingPipOwnerWithoutReconnect() {
        withCamera { fixture ->
            val originalTarget = MirrorTarget.from(fixture.activity.intent)
            val connected = fixture.model.state.value
            val requests = listOf(
                MirrorActivity.newIntent(fixture.activity, HOST, INVALID_PORT),
                Intent(fixture.activity, MirrorActivity::class.java)
                    .setAction("com.scrcpy.remote.RETURN_FROM_CAMERA_PIP")
                    .putExtra(MirrorActivity.EXTRA_HOST, "ignored-return-target.example")
                    .putExtra(MirrorActivity.EXTRA_PORT, INVALID_PORT)
                    .putExtra(MirrorActivity.EXTRA_DEVICE_ID, "ignored-return-profile"),
            )
            for (request in requests) {
                enterPip(fixture)
                main {
                    assertEquals("The manifest must own task reuse, not legacy reorder flags", 0,
                        request.flags and (Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    fixture.activity.startActivity(request)
                }
                awaitMain("A ${request.action ?: "same-target"} request expands the same PiP owner",
                    diagnostics = { fixtureStatus(fixture) }) {
                    !fixture.activity.isInPictureInPictureMode && fixture.activity.hasWindowFocus() &&
                        fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                }
                fixture.scenario.onActivity {
                    assertSame("There must still be only one session Activity", fixture.activity, it)
                    assertSame(fixture.model, ViewModelProvider(it)[MirrorViewModel::class.java])
                    assertEquals(originalTarget, MirrorTarget.from(it.intent))
                    assertEquals("A repeat/private return is not a reconnect", connected, fixture.model.state.value)
                    assertSame(fixture.view, findMirror(it.window.decorView))
                }
            }
        }
    }

    @Test fun repeatedNativePipReentryAfterFullscreenReturnKeepsTheExistingCameraOwner() {
        withCamera { fixture ->
            val connected = fixture.model.state.value
            val history = CopyOnWriteArrayList<String>()
            var round = 0
            val observer = LifecycleEventObserver { _, event ->
                history += "Round $round $event: ${fixtureStatus(fixture)}; " +
                    "closeArmed=${field<Boolean>(fixture.activity, "pipCloseArmed")}; " +
                    "entryRequested=${field<androidx.compose.runtime.MutableState<Boolean>>(
                        fixture.activity, "pipEntryRequested\$delegate").value}; " +
                    "entryCallback=${fixture.pipEntryCompleted.get()}"
            }
            main { fixture.activity.lifecycle.addObserver(observer) }
            try {
                repeat(12) {
                    round = it + 1
                    enterPip(fixture)
                    main {
                        fixture.activity.startActivity(MirrorActivity.newIntent(fixture.activity, HOST, INVALID_PORT))
                    }
                    awaitMain("Round $round returns to the same foreground camera",
                        diagnostics = { fixtureStatus(fixture) }) {
                        !fixture.pipEntryCompleted.get() && !fixture.activity.isInPictureInPictureMode &&
                            fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                            fixture.activity.hasWindowFocus()
                    }
                    main {
                        assertEquals(connected, fixture.model.state.value)
                        assertFalse(fixture.activity.isFinishing)
                    }
                }
            } catch (error: AssertionError) {
                throw AssertionError("PiP reentry round $round\n${history.joinToString("\n")}", error)
            } finally {
                main { fixture.activity.lifecycle.removeObserver(observer) }
            }
        }
    }

    @Test fun aNewTargetDeliveredWhilePinnedWaitsForFullscreenBeforeReplacingTheCamera() {
        withCamera { fixture ->
            enterPip(fixture)
            main {
                val camera = fixture.model.activeSettings.value?.camera
                fixture.activity.startActivity(
                    MirrorActivity.newIntent(fixture.activity, "new-camera-target.example", INVALID_PORT))
                assertEquals("The new screen/profile must not replace the pinned camera",
                    camera, fixture.model.activeSettings.value?.camera)
                assertTrue(fixture.model.state.value is MirrorUiState.Connected)
            }
            awaitMain("New target is applied only after expansion", diagnostics = { fixtureStatus(fixture) }) {
                fixture.model.state.value is MirrorUiState.Failed &&
                    fixture.model.endpointLabel().startsWith("new-camera-target.example:")
            }
            fixture.scenario.onActivity {
                assertFalse(it.isInPictureInPictureMode)
                assertEquals("new-camera-target.example", it.intent.getStringExtra(MirrorActivity.EXTRA_HOST))
            }
        }
    }

    @Test fun aNonCameraSourceEncounteredInPipIsStoppedEvenBeforeExpansionCompletes() {
        withCamera { fixture ->
            enterPip(fixture)
            main {
                flow<MirrorSettings?>(fixture.model, "_activeSettings").value =
                    cameraSettings().copy(camera = null, virtualDisplay = "1280x720")
            }
            awaitMain("No screen session may keep running in camera-only PiP") {
                fixture.model.state.value is MirrorUiState.Disconnected
            }
            awaitMain("Non-camera PiP returns to its full-sized UI") {
                !fixture.activity.isInPictureInPictureMode &&
                    fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
        }
    }

    @Test fun aDifferentTargetDropsTheOldCameraDialogButPreservesArtifactOwners() {
        withCamera(requirePipSupport = false) { fixture ->
            openTools()
            compose.onNodeWithContentDescription(text(R.string.cameras_action))
                .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithTag("camera-save").assertIsDisplayed()
            val recording = File(context.cacheDir, "camera-target-owner-${System.nanoTime()}.mp4")
            val payload = byteArrayOf(4, 8, 15, 16, 23, 42)
            recording.writeBytes(payload)
            try {
                main {
                    flow<File?>(fixture.model, "_finishedRecording").value = recording
                    fixture.activity.startActivity(
                        MirrorActivity.newIntent(fixture.activity, "new-ui-target.example", INVALID_PORT),
                    )
                }
                awaitMain("A different target replaces only its UI scope",
                    diagnostics = { fixtureStatus(fixture) }) {
                    fixture.model.state.value is MirrorUiState.Failed &&
                        fixture.model.endpointLabel().startsWith("new-ui-target.example:")
                }
                compose.onNodeWithTag("camera-save").assertDoesNotExist()
                compose.onNodeWithText(text(R.string.recording_save_title)).assertIsDisplayed()
                fixture.scenario.onActivity {
                    assertSame(fixture.model, ViewModelProvider(it)[MirrorViewModel::class.java])
                    assertSame(fixture.screenshots, ViewModelProvider(it)[ScreenshotViewModel::class.java])
                    assertEquals(recording, fixture.model.finishedRecording.value)
                }
                assertArrayEquals(payload, recording.readBytes())
                main { fixture.model.consumeFinishedRecording(keep = true) }
            } finally {
                recording.delete()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    fun realLoopbackCameraKeepsItsDecoderAndSessionAcrossNativePipRoundTrips() {
        val arguments = InstrumentationRegistry.getArguments()
        val portText = arguments.getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", portText != null)
        val port = requireNotNull(portText).toInt()
        require(port in 1..65535)
        val cameraId = requireNotNull(arguments.getString("qaLoopbackCameraId"))
        withCamera(livePort = port, cameraId = cameraId) { fixture ->
            lateinit var decoder: VideoDecoder
            lateinit var session: ScrcpySession
            main {
                decoder = requireNotNull(field<VideoDecoder?>(fixture.model, "video"))
                session = requireNotNull(field<ScrcpySession?>(fixture.model, "session"))
            }
            val connected = fixture.model.state.value as MirrorUiState.Connected
            repeat(20) { index ->
                awaitMain("Live camera ready for PiP round ${index + 1}",
                    diagnostics = { fixtureStatus(fixture) }) {
                    if (fixture.pipEntryCompleted.get() || fixture.activity.isInPictureInPictureMode ||
                        fixture.activity.lifecycle.currentState != Lifecycle.State.RESUMED ||
                        !fixture.activity.hasWindowFocus()
                    ) false else {
                        assertTrue(fixture.activity.requestCameraPictureInPicture())
                        true
                    }
                }
                awaitMain("Live camera enters native PiP in round ${index + 1}",
                    diagnostics = { fixtureStatus(fixture) }) {
                    fixture.pipEntryCompleted.get() && fixture.activity.isInPictureInPictureMode &&
                        fixture.activity.lifecycle.currentState == Lifecycle.State.STARTED
                }
                val pinnedFrames = decoder.frameStats().surfaceFrames
                awaitMain("A new decoded camera buffer reaches the pinned surface") {
                    decoder.frameStats().surfaceFrames > pinnedFrames
                }
                main {
                    assertSame(session, field<ScrcpySession?>(fixture.model, "session"))
                    fixture.activity.startActivity(MirrorActivity.newIntent(fixture.activity, HOST, port,
                        linkOptions = requireNotNull(MirrorTarget.from(fixture.activity.intent)).linkOptions))
                }
                awaitMain("Real camera returns to fullscreen in round ${index + 1}",
                    diagnostics = { fixtureStatus(fixture) }) {
                    !fixture.pipEntryCompleted.get() && !fixture.activity.isInPictureInPictureMode &&
                        fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        fixture.activity.hasWindowFocus()
                }
                val returnedFrames = decoder.frameStats().surfaceFrames
                awaitMain("A new decoded camera buffer reaches the returned fullscreen surface") {
                    decoder.frameStats().surfaceFrames > returnedFrames
                }
                main {
                    assertSame(session, field<ScrcpySession?>(fixture.model, "session"))
                    assertSame(decoder, field<VideoDecoder?>(fixture.model, "video"))
                    assertEquals(connected.sessionGeneration,
                        (fixture.model.state.value as MirrorUiState.Connected).sessionGeneration)
                    assertFalse(fixture.activity.isFinishing)
                }
                Log.i("LiveCameraPiP", "Round ${index + 1}: ${decoder.frameStats()}; same session and decoder")
            }
        }
    }

    private data class Fixture(
        val scenario: ActivityScenario<MirrorActivity>,
        val activity: MirrorActivity,
        val model: MirrorViewModel,
        val screenshots: ScreenshotViewModel,
        val view: ScrcpyMirrorView,
        val pipEntryCompleted: AtomicBoolean,
    )

    private fun withCamera(
        requirePipSupport: Boolean = true,
        livePort: Int? = null,
        cameraId: String = "0",
        block: (Fixture) -> Unit,
    ) {
        if (requirePipSupport) {
            assumeTrue("This device does not advertise native PiP",
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch<MirrorActivity>(
            MirrorActivity.newIntent(context, HOST, livePort ?: INVALID_PORT,
                linkOptions = if (livePort == null) null else
                    LinkRequest(maxSize = 640, maxFps = 30, audio = LinkAudio.OFF).toQuery()),
        ).use { scenario ->
            lateinit var activity: MirrorActivity
            lateinit var model: MirrorViewModel
            lateinit var screenshots: ScreenshotViewModel
            val pipEntryCompleted = AtomicBoolean(false)
            val pipListener = Consumer<PictureInPictureModeChangedInfo> {
                pipEntryCompleted.set(it.isInPictureInPictureMode)
            }
            scenario.onActivity {
                activity = it
                model = ViewModelProvider(it)[MirrorViewModel::class.java]
                screenshots = ViewModelProvider(it)[ScreenshotViewModel::class.java]
                it.addOnPictureInPictureModeChangedListener(pipListener)
            }
            try {
                if (livePort == null) {
                    awaitMain("Invalid endpoint fails without opening an ADB transport") {
                        model.state.value is MirrorUiState.Failed
                    }
                    main {
                        model.disconnect()
                        flow<MirrorSettings?>(model, "_activeSettings").value = cameraSettings()
                        flow<MirrorUiState>(model, "_state").value =
                            MirrorUiState.Connected("Camera fixture", 720, 1280, 1, 1)
                    }
                } else {
                    awaitMain("The explicitly configured loopback ADB session connects",
                        timeoutMillis = 60_000,
                        diagnostics = { "state=${model.state.value}; notice=${model.notice.value}" }) {
                        assertFalse("Loopback connection failed: ${model.state.value}",
                            model.state.value is MirrorUiState.Failed)
                        model.state.value is MirrorUiState.Connected
                    }
                    main { model.useCameraForSession(CameraCapture(id = cameraId, fps = 30)) }
                    awaitMain("The loopback camera has a real decoder and rendered buffers",
                        timeoutMillis = 60_000,
                        diagnostics = { "state=${model.state.value}; notice=${model.notice.value}" }) {
                        assertFalse("Loopback camera failed: ${model.state.value}",
                            model.state.value is MirrorUiState.Failed)
                        model.state.value is MirrorUiState.Connected &&
                            model.activeSettings.value?.camera?.id == cameraId &&
                            (field<VideoDecoder?>(model, "video")?.frameStats()?.surfaceFrames ?: 0) > 0
                    }
                }
                withAccessibleWindows {
                    awaitMain("Camera video surface and foreground focus", diagnostics = {
                        val surface = findMirror(activity.window.decorView)?.screenshotSurface
                        "state=${model.state.value}; lifecycle=${activity.lifecycle.currentState}; " +
                            "focus=${activity.hasWindowFocus()}; surface=${surface?.holder?.surface?.isValid}; " +
                            "size=${surface?.width}x${surface?.height}"
                    }) {
                        val surface = findMirror(activity.window.decorView)?.screenshotSurface
                        surface?.holder?.surface?.isValid == true && surface.width > 0 &&
                            activity.hasWindowFocus() && activity.lifecycle.currentState == Lifecycle.State.RESUMED
                    }
                    lateinit var view: ScrcpyMirrorView
                    main { view = requireNotNull(findMirror(activity.window.decorView)) }
                    val renderer = if (livePort == null) fixtureRenderer() else null
                    if (renderer != null) main { view.surfaceHolder.addCallback(renderer) }
                    try {
                        block(Fixture(scenario, activity, model, screenshots, view, pipEntryCompleted))
                    } finally {
                        main {
                            if (renderer != null) view.surfaceHolder.removeCallback(renderer)
                            paintedSurfaces.remove(view.surfaceHolder)
                        }
                    }
                }
            } finally {
                main {
                    if (!activity.isDestroyed) {
                        activity.removeOnPictureInPictureModeChangedListener(pipListener)
                        flow<Boolean>(model, "_recording").value = false
                        flow<Boolean>(model, "_recordingSaveInProgress").value = false
                        flow<File?>(model, "_finishedRecording").value = null
                        flow<ScreenshotState>(screenshots, "_state").value = ScreenshotState()
                        model.disconnect()
                        activity.finish()
                    }
                }
                MirrorSessionService.stop(context)
            }
        }
    }

    private fun enterPip(fixture: Fixture) {
        awaitMain("The previous native PiP transition has completed",
            diagnostics = { fixtureStatus(fixture) }) {
            !fixture.pipEntryCompleted.get() && !fixture.activity.isInPictureInPictureMode &&
                fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                fixture.activity.hasWindowFocus()
        }
        paintAndCheck(fixture.view, Color.GREEN)
        main { assertTrue("Android must accept the eligible camera", fixture.activity.requestCameraPictureInPicture()) }
        awaitMain("Native PiP entry", diagnostics = { fixtureStatus(fixture) }) {
            fixture.pipEntryCompleted.get() && fixture.activity.isInPictureInPictureMode &&
                fixture.activity.lifecycle.currentState == Lifecycle.State.STARTED
        }
    }

    private fun returnToCamera(fixture: Fixture) {
        var pinned = false
        main { pinned = fixture.activity.isInPictureInPictureMode }
        if (pinned) {
            clickNativePipButton(fixture, dismiss = false)
        } else {
            main { fixture.activity.startActivity(MirrorActivity.newIntent(fixture.activity, HOST, INVALID_PORT)) }
        }
        awaitMain("Camera returned to fullscreen", diagnostics = { fixtureStatus(fixture) }) {
            !fixture.activity.isInPictureInPictureMode && fixture.activity.hasWindowFocus() &&
                fixture.activity.lifecycle.currentState == Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }

    private fun assertRejected(fixture: Fixture, message: Int) {
        var called = false
        assertFalse(fixture.activity.requestCameraPictureInPicture { called = true; false })
        assertFalse("The platform must not be called for an ineligible request", called)
        assertEquals(text(message), fixture.model.notice.value)
    }

    private fun paintAndCheck(view: ScrcpyMirrorView, color: Int) {
        main { paintFrame(view.surfaceHolder, color) }
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            var result = -1
            PixelCopy.request(view.screenshotSurface, bitmap, {
                result = it
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("Rendered buffer must remain readable in native PiP", copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            assertEquals("The surface must render a new frame, not a frozen entry snapshot",
                color, bitmap.getPixel(8, 8))
        } finally {
            bitmap.recycle()
        }
    }

    private fun paintFrame(holder: SurfaceHolder, color: Int) {
        paintedSurfaces[holder] = color
        assertTrue(holder.surface.isValid)
        val canvas = holder.lockCanvas()
        assertNotNull(canvas)
        try { canvas.drawColor(color) } finally { holder.unlockCanvasAndPost(canvas) }
    }

    private fun fixtureRenderer() = object : SurfaceHolder.Callback2 {
        override fun surfaceCreated(holder: SurfaceHolder) = redraw(holder)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = redraw(holder)
        override fun surfaceRedrawNeeded(holder: SurfaceHolder) = redraw(holder)
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

        private fun redraw(holder: SurfaceHolder) = paintFrame(holder, paintedSurfaces[holder] ?: Color.GREEN)
    }

    private fun clickNativePipButton(fixture: Fixture, dismiss: Boolean) {
        main { assertTrue("Native controls require the Activity to remain pinned",
            fixture.activity.isInPictureInPictureMode) }
        val automation = instrumentation.uiAutomation
        val previous = automation.serviceInfo
        automation.serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = previous.eventTypes
            feedbackType = previous.feedbackType
            notificationTimeout = previous.notificationTimeout
            flags = previous.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            automation.waitForIdle(500, 5_000)
            var button: AccessibilityNodeInfo? = null
            var lastTap = 0L
            val nativeAction = if (dismiss) AccessibilityNodeInfo.ACTION_DISMISS else AccessibilityNodeInfo.ACTION_EXPAND
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (button == null && SystemClock.elapsedRealtime() < deadline) {
                // Newer Android can show the menu on entry. Do not toggle it closed first.
                for (nativeWindow in automation.windows) {
                    button = findPipButton(nativeWindow.root, dismiss)
                    if (button != null) break
                }
                val window = automation.windows.firstOrNull {
                    it.isInPictureInPictureMode && it.root?.packageName == context.packageName
                }
                val root = window?.root
                // A windowless embedded PiP menu need not expose separate button nodes.
                // These offered root actions are SystemUI's real native expand/dismiss path.
                if (button == null && root?.actionList?.any { it.id == nativeAction } == true) {
                    assertTrue("SystemUI must accept its offered native PiP action", root.performAction(nativeAction))
                    return
                }
                if (button == null && SystemClock.elapsedRealtime() - lastTap >= 1_000) {
                    if (window != null) {
                        val bounds = Rect()
                        window.getBoundsInScreen(bounds)
                        assertFalse("The native PiP window must have real bounds", bounds.isEmpty)
                        val now = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                                bounds.exactCenterX(), bounds.exactCenterY(), 0).apply {
                                source = InputDevice.SOURCE_TOUCHSCREEN
                            }
                            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                        }
                        lastTap = SystemClock.elapsedRealtime()
                    }
                }
                if (button == null) SystemClock.sleep(25)
            }
            assertNotNull("Native PiP ${if (dismiss) "close" else "expand"} control must be accessible", button)
            assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            automation.serviceInfo = previous
        }
    }

    private fun findPipButton(node: AccessibilityNodeInfo?, dismiss: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/')
        val description = node.contentDescription?.toString().orEmpty().lowercase()
        val names = if (dismiss) setOf("dismiss", "dismiss_button", "close_button")
            else setOf("expand", "expand_button", "expand_container", "fullscreen_button")
        val labels = if (dismiss) setOf("close", "dismiss", "close picture-in-picture")
            else setOf("expand", "full screen", "fullscreen", "expand picture-in-picture")
        if (node.packageName?.toString() in setOf("com.android.systemui", "com.android.wm.shell") &&
            (id in names || description in labels) && node.isClickable && node.isEnabled && node.isVisibleToUser
        ) return node
        for (index in 0 until node.childCount) {
            findPipButton(node.getChild(index), dismiss)?.let { return it }
        }
        return null
    }

    private fun withAccessibleWindows(block: () -> Unit) {
        val automation = instrumentation.uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        info.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        try {
            block()
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
    }

    private fun assertNativePipContent(fixture: Fixture, expected: List<String>, absent: List<String>) {
        var visible = emptySet<String>()
        withAccessibleWindows {
            try {
                // Compose's test root registry only includes resumed Activities. PiP is paused,
                // so inspect its real accessibility window rather than accepting an empty tree.
                compose.waitUntil(timeoutMillis = 15_000) {
                    main {
                        // The normal test registry omits this paused owner, so also perform
                        // its pending layout before asking Android for the updated text.
                        findComposeRoot(fixture.activity.window.decorView)?.measureAndLayoutForTest()
                    }
                    visible = buildSet {
                        for (window in instrumentation.uiAutomation.windows) {
                            val root = window.root ?: continue
                            if (window.isInPictureInPictureMode && root.packageName == context.packageName) {
                                if (!root.refresh()) continue
                                collectVisibleText(root, this)
                            }
                        }
                    }
                    expected.all { label -> visible.any { it.contains(label) } }
                }
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("Native PiP must expose $expected; visible accessibility text: $visible", error)
            }
        }
        main {
            assertTrue(fixture.activity.isInPictureInPictureMode)
            assertEquals(Lifecycle.State.STARTED, fixture.activity.lifecycle.currentState)
        }
        for (label in absent) {
            assertFalse("Fullscreen-only content leaked into native PiP: $label",
                visible.any { it.contains(label) })
        }
    }

    private fun assertLocalPipVideoUi(fixture: Fixture, cameraLabel: String) {
        main {
            // Android may replace the native video window's accessibility tree with virtual PiP
            // actions. Check the actual paused Compose owner, not an empty OS tree or resumed-only
            // test registry. PixelCopy below independently verifies new video buffers.
            val owner = requireNotNull(findComposeRoot(fixture.activity.window.decorView))
            assertTrue(owner.view.isAttachedToWindow && owner.view.isShown)
            val nodes = semanticsNodes(owner.semanticsOwner.unmergedRootSemanticsNode)
            val video = nodes.single { it.config.getOrNull(SemanticsProperties.TestTag) == "mirror-surface" }
            assertTrue(video.boundsInRoot.width > 0 && video.boundsInRoot.height > 0)
            assertTrue(video.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(cameraLabel) == true)
            assertFalse(nodes.any {
                it.config.getOrNull(SemanticsProperties.TestTag) in setOf("expanded-controls", "compact-navigation")
            })
            val controlLabels = setOf(text(R.string.control_show), text(R.string.control_hide),
                text(R.string.capture_share_screenshot), text(R.string.recording_action_start))
            assertFalse("Fullscreen controls must be removed from the real paused composition", nodes.any {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any(controlLabels::contains) == true
            })
        }
    }

    private fun awaitLocalPipFailureCard(fixture: Fixture, owner: ViewRootForTest): Rect {
        var cardBounds: Rect? = null
        awaitMain("Visible local failure card in the retained PiP owner",
            diagnostics = { fixtureStatus(fixture) }) {
            assertSame(owner, findComposeRoot(fixture.activity.window.decorView))
            owner.measureAndLayoutForTest()
            val nodes = semanticsNodes(owner.semanticsOwner.unmergedRootSemanticsNode)
            val card = nodes.firstOrNull {
                it.config.getOrNull(SemanticsProperties.TestTag) == "camera-pip-status"
            }
            val expected = listOf(text(R.string.camera_pip_failed), text(R.string.camera_pip_return))
            val visibleText = card?.let(::semanticsNodes).orEmpty().filter {
                it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0
            }.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            val ready = fixture.activity.isInPictureInPictureMode &&
                fixture.activity.lifecycle.currentState == Lifecycle.State.STARTED &&
                owner.view.isAttachedToWindow && owner.view.isShown && card != null &&
                card.boundsInRoot.width > 0 && card.boundsInRoot.height > 0 &&
                expected.all(visibleText::contains)
            if (ready && card != null) {
                assertFalse(nodes.any {
                    it.config.getOrNull(SemanticsProperties.TestTag) in
                        setOf("expanded-controls", "compact-navigation", "mirror-surface")
                })
                assertFalse(visibleText.contains("Fixture stream stopped"))
                val offset = IntArray(2)
                owner.view.getLocationInWindow(offset)
                val bounds = card.boundsInRoot
                cardBounds = Rect(
                    floor(bounds.left).toInt() + offset[0],
                    floor(bounds.top).toInt() + offset[1],
                    ceil(bounds.right).toInt() + offset[0],
                    ceil(bounds.bottom).toInt() + offset[1],
                ).also {
                    val decor = fixture.activity.window.decorView
                    assertTrue("The rendered failure card must intersect the actual window",
                        it.intersect(0, 0, decor.width, decor.height))
                }
            }
            ready
        }
        return requireNotNull(cardBounds)
    }

    private fun assertWindowFailureCardPixels(fixture: Fixture, owner: ViewRootForTest, bounds: Rect) {
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        var result = -1
        var contrast = 0
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            do {
                val copied = CountDownLatch(1)
                main {
                    assertTrue(fixture.activity.isInPictureInPictureMode)
                    owner.measureAndLayoutForTest()
                    owner.view.invalidate()
                    PixelCopy.request(fixture.activity.window, Rect(bounds), bitmap, {
                        result = it
                        copied.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                assertTrue("The actual PiP window must produce a copyable frame",
                    copied.await(5, TimeUnit.SECONDS))
                if (result == PixelCopy.SUCCESS) {
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val inset = minOf(4, bitmap.width / 4, bitmap.height / 4)
                    var darkest = 255
                    var brightest = 0
                    var opaque = 0
                    var sampled = 0
                    for (y in inset until bitmap.height - inset) for (x in inset until bitmap.width - inset) {
                        val pixel = pixels[y * bitmap.width + x]
                        sampled++
                        if (Color.alpha(pixel) >= 240) {
                            opaque++
                            val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                                Color.blue(pixel) * 114) / 1000
                            darkest = minOf(darkest, luma)
                            brightest = maxOf(brightest, luma)
                        }
                    }
                    contrast = brightest - darkest
                    // A blank card (or the previous solid-color video) cannot satisfy this.
                    if (sampled > 0 && opaque * 10 >= sampled * 9 && contrast >= 64) return
                } else if (result != PixelCopy.ERROR_SOURCE_NO_DATA) {
                    assertEquals("PiP window copy failed", PixelCopy.SUCCESS, result)
                }
                compose.mainClock.advanceTimeByFrame()
                SystemClock.sleep(20)
            } while (SystemClock.elapsedRealtime() < deadline)
            fail("The failure card must really render visible text: copy=$result, contrast=$contrast, bounds=$bounds")
        } finally {
            bitmap.recycle()
        }
    }

    private fun semanticsNodes(root: SemanticsNode): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun collect(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::collect)
        }
        collect(root)
        return nodes
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findComposeRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo, output: MutableSet<String>) {
        if (node.isVisibleToUser) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectVisibleText(it, output) }
        }
    }

    private fun dismissImmersiveConfirmation(): Boolean {
        val resourceOwners = listOf("android", "com.android.systemui")
        for (window in instrumentation.uiAutomation.windows) {
            val root = window.root ?: continue
            // The education moved from framework resources to SystemUI on newer images.
            // Restrict the window and IDs rather than matching a localized "Got it" label.
            val education = window.title?.toString() == "ImmersiveModeConfirmation" ||
                resourceOwners.any {
                    root.findAccessibilityNodeInfosByViewId("$it:id/immersive_cling_description").isNotEmpty()
                }
            if (!education) continue
            val confirm = resourceOwners.flatMap { root.findAccessibilityNodeInfosByViewId("$it:id/ok") }
                .firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable } ?: continue
            assertTrue("Android's fullscreen education must be dismissed, not bypassed",
                confirm.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return true
        }
        return false
    }

    private fun readImmersiveConfirmation(): String? =
        shellOutput("settings --user $userId get secure immersive_mode_confirmations")
            .trim().takeUnless { it.isEmpty() || it == "null" }

    private fun shellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun openTools() {
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

    private fun awaitMain(
        message: String,
        diagnostics: (() -> String)? = null,
        beforeCheck: () -> Unit = { dismissImmersiveConfirmation() },
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean,
    ) {
        try {
            // Main-thread polling alone does not advance Compose's test frame clock. The
            // Connected state then never composes its AndroidView, so no Surface can be created.
            // waitUntil drives those frames while yielding for real window/surface callbacks.
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                beforeCheck()
                var ready = false
                main { ready = condition() }
                ready
            }
        } catch (error: ComposeTimeoutException) {
            var detail: String? = null
            if (diagnostics != null) main { detail = diagnostics() }
            val windows = instrumentation.uiAutomation.windows.joinToString { window ->
                "${window.title}: package=${window.root?.packageName}, focused=${window.isFocused}, " +
                    "pip=${window.isInPictureInPictureMode}"
            }
            throw AssertionError(listOfNotNull(message, detail, "windows=[$windows]").joinToString("; "), error)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun text(id: Int) = context.getString(id)

    private fun fixtureStatus(fixture: Fixture): String =
        "state=${fixture.model.state.value}; endpoint=${fixture.model.endpointLabel()}; " +
            "lifecycle=${fixture.activity.lifecycle.currentState}; pip=${fixture.activity.isInPictureInPictureMode}; " +
            "focus=${fixture.activity.hasWindowFocus()}; finishing=${fixture.activity.isFinishing}; " +
            "destroyed=${fixture.activity.isDestroyed}; notice=${fixture.model.notice.value}"

    private fun parcelBytes(params: PictureInPictureParams): ByteArray {
        val parcel = Parcel.obtain()
        return try {
            params.writeToParcel(parcel, 0)
            parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T

    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> = field(owner, name)

    private fun cameraSettings() = MirrorSettings(
        camera = CameraCapture(id = "0", size = "720x1280", fps = 30),
        audioEnabled = false,
        backgroundAudio = false,
        autoReconnect = false,
    )

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
    }
}
