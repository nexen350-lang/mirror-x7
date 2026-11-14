package com.scrcpy.remote.ui

import com.scrcpy.remote.R

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.util.Rational
import android.view.InputDevice
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.sameEndpoint
import com.scrcpy.remote.data.canonicalHost
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.effectiveSettings
import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.ui.screenshot.ArtefactSharing
import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.DeviceAppPages
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.KeyForwardingPolicy
import com.scrcpy.remote.session.FileTransfers
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.session.RecordingSound
import com.scrcpy.remote.session.SessionOverrides
import com.scrcpy.remote.session.SessionDisplay
import com.scrcpy.remote.session.StreamChoice
import com.scrcpy.remote.session.SessionHold
import com.scrcpy.remote.session.requiresReconnectFrom

class MirrorActivity : ComponentActivity() {

    private val viewModel: MirrorViewModel by viewModels()
    private val screenshots: ScreenshotViewModel by viewModels()
    private var screenshotSurface: SurfaceView? = null
    private var pipMode by mutableStateOf(false)
    private var pipEntryRequested by mutableStateOf(false)
    private var resumed by mutableStateOf(false)
    private var documentPickerOpen by mutableStateOf(false)
    private var shareChooserOpen by mutableStateOf(false)
    private var pendingMirrorIntent by mutableStateOf<Intent?>(null)
    private var pipCloseArmed = false
    private var pipReturnRequested = false
    private var pipReturnWatchdog: Job? = null
    private var pipDialogOpen = false
    private val hideSessionTools: Boolean get() = pipMode || pipEntryRequested
    private val supportsCameraPip by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private val saveScreenshot = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        documentPickerOpen = false
        screenshots.saveTo(it)
    }

    /** Set while leaving is deferred until a recording or screenshot has been kept or dropped. */
    private var exitAfterRecording = false

    /**
     * Ends the session and leaves, unless a recording or screenshot is still being kept.
     *
     * Ending the session finishes any recording and offers it. Leaving at the same moment took the
     * screen away before that offer could be seen and then deleted the file on teardown, so a
     * recording ended by exiting — or by the notification's Disconnect, which is how a background
     * recording is usually stopped — was lost without a word. A screenshot has the same problem:
     * its export runs in this screen's view model, so leaving mid-export cancelled the copy and
     * left the chosen document empty or partial. The screen stays until that work has settled.
     */
    private fun leaveSession() {
        viewModel.disconnect()
        if (artefactWorkPending()) exitAfterRecording = true else finish()
    }

    /** A recording offer or its save, or a screenshot being captured, chosen for, saved or shared. */
    private fun artefactWorkPending(): Boolean {
        val capture = screenshots.state.value
        return viewModel.finishedRecording.value != null || viewModel.recordingSaveInProgress.value ||
            capture.busy || capture.pendingToken != null || capture.shareFile != null
    }

    private val saveRecording = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        documentPickerOpen = false
        // Handed to the view model rather than copied here: this screen can be destroyed mid-copy,
        // which truncated the chosen document and took the cached original with it.
        viewModel.saveFinishedRecording(uri)
    }

    /** Any type: the device stores whatever arrives, and a document provider may report none. */
    private val pickFileToSend = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        documentPickerOpen = false
        if (uri == null) viewModel.onFilePickerCancelled() else viewModel.sendFile(uri)
    }

    /** An app package; the view model checks what was actually picked before offering it. */
    private val pickAppToInstall = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        documentPickerOpen = false
        if (uri == null) viewModel.onFilePickerCancelled() else viewModel.installPickedApp(uri)
    }

    /** Opens [launcher] with the hold claimed while this app is still in front. */
    private fun openDocumentPicker(launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>, types: Array<String>) {
        if (!canUseSessionTools()) return
        viewModel.onFilePickerOpening()
        documentPickerOpen = true
        try {
            launcher.launch(types)
        } catch (_: ActivityNotFoundException) {
            documentPickerOpen = false
            viewModel.onFilePickerCancelled()
            viewModel.showNotice(getString(R.string.transfer_picker_unavailable))
        }
    }

    private fun canUseSessionTools(): Boolean =
        resumed && !hideSessionTools && !isInPictureInPictureMode && !isFinishing

    private fun pipWorkPending(): Boolean {
        val capture = screenshots.state.value
        return viewModel.recording.value || viewModel.finishedRecording.value != null ||
            viewModel.recordingSaveInProgress.value || viewModel.fileTransfer.value.running ||
            viewModel.transferHoldsProcess.value || capture.busy || capture.pendingToken != null ||
            capture.shareFile != null || documentPickerOpen || shareChooserOpen
    }

    private fun pipBlocked(): CameraPipPolicy.Blocked? {
        val live = viewModel.state.value as? MirrorUiState.Connected
        return CameraPipPolicy.blocked(
            supported = supportsCameraPip,
            camera = viewModel.activeSettings.value?.camera != null,
            connected = live != null,
            videoWidth = live?.videoWidth ?: 0,
            videoHeight = live?.videoHeight ?: 0,
            surfaceReady = screenshotSurface?.holder?.surface?.isValid == true,
            busy = pipWorkPending() || pipDialogOpen,
            foreground = canUseSessionTools() && hasWindowFocus(),
        )
    }

    private fun reportPipBlocked(reason: CameraPipPolicy.Blocked) {
        Log.i(PIP_TAG, "Camera PiP entry blocked: $reason")
        viewModel.showNotice(getString(when (reason) {
            CameraPipPolicy.Blocked.UNAVAILABLE -> R.string.camera_pip_unavailable
            CameraPipPolicy.Blocked.NOT_CAMERA -> R.string.camera_pip_camera_only
            CameraPipPolicy.Blocked.NOT_LIVE -> R.string.camera_pip_not_live
            CameraPipPolicy.Blocked.BUSY -> R.string.camera_pip_busy
            CameraPipPolicy.Blocked.NOT_FOREGROUND -> R.string.camera_pip_not_foreground
        }))
    }

    private fun cameraPipParams(): PictureInPictureParams? {
        val live = viewModel.state.value as? MirrorUiState.Connected ?: return null
        val aspect = CameraPipPolicy.aspectRatio(live.videoWidth, live.videoHeight) ?: return null
        return PictureInPictureParams.Builder().apply {
            setAspectRatio(Rational(aspect.numerator, aspect.denominator))
            // The SurfaceView already occupies the fitted/cropped video, unlike the window,
            // which also includes letterboxes. Global visibility clips a magnified surface.
            if (!isInPictureInPictureMode) {
                val bounds = Rect()
                if (screenshotSurface?.getGlobalVisibleRect(bounds) == true && !bounds.isEmpty) {
                    setSourceRectHint(bounds)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(false)
        }.build()
    }

    /** Only the explicit camera button calls this; Home keeps its existing background behavior. */
    internal fun requestCameraPictureInPicture(
        enterMode: (PictureInPictureParams) -> Boolean = { enterPictureInPictureMode(it) },
    ): Boolean {
        pipBlocked()?.let {
            reportPipBlocked(it)
            return false
        }
        val params = cameraPipParams() ?: return false
        pipEntryRequested = true
        val entered = try {
            enterMode(params)
        } catch (error: RuntimeException) {
            // OEMs may throw IllegalStateException/SecurityException instead of returning false.
            Log.w(PIP_TAG, "Android refused camera PiP", error)
            false
        }
        if (!entered) {
            pipEntryRequested = false
            Log.w(PIP_TAG, "Android did not enter camera PiP")
            viewModel.showNotice(getString(R.string.camera_pip_refused))
            if (resumed && !isInPictureInPictureMode) restoreFullscreenTools()
        } else {
            pipCloseArmed = true
            reconcileCameraPip()
        }
        return entered
    }

    /**
     * A picker/result or a replacement session can race the platform's entry animation.
     * Keep the work owned and return to its full-sized UI rather than displaying a dialog in PiP.
     */
    private fun reconcileCameraPip() {
        // Android 15+ reports the window's PiP mode before its enter animation finishes. The
        // mode callback is the completion boundary; reversing the task earlier can stop it
        // halfway through the transition instead of returning it to fullscreen.
        if (!pipMode || !isInPictureInPictureMode) return
        val notCamera = viewModel.activeSettings.value?.camera == null
        if (notCamera && viewModel.state.value !is MirrorUiState.Disconnected) {
            // Even if Android refuses to expand this window, a replacement screen stream must
            // never remain running behind the camera-only PiP UI.
            Log.i(PIP_TAG, "Stopping non-camera session encountered in camera PiP")
            viewModel.disconnect()
            viewModel.showNotice(getString(R.string.camera_pip_camera_only))
        }
        if (pipWorkPending() || pendingMirrorIntent != null || notCamera) {
            if (pipReturnRequested) return
            pipReturnRequested = true
            watchCameraPipReturn()
            try {
                val target = requireNotNull(MirrorTarget.from(intent))
                // singleTask can update the task's base intent. Preserve its destination without
                // replaying file extras, and let the manifest choose the reuse/fullscreen route.
                startActivity(newIntent(this, target.host, target.port, target.deviceId,
                    linkOptions = target.linkOptions).setAction(ACTION_RETURN_FROM_PIP))
            } catch (error: RuntimeException) {
                pipReturnWatchdog?.cancel()
                pipReturnWatchdog = null
                pipReturnRequested = false
                Log.w(PIP_TAG, "Could not expand camera PiP; work remains pending", error)
                viewModel.showNotice(getString(if (pendingMirrorIntent != null)
                    R.string.camera_pip_connection_waiting else R.string.camera_pip_pending))
            }
            return
        }
        cameraPipParams()?.let { params ->
            try {
                setPictureInPictureParams(params)
            } catch (error: RuntimeException) {
                Log.w(PIP_TAG, "Could not update camera PiP dimensions", error)
                viewModel.showNotice(getString(R.string.camera_pip_refused))
            }
        }
    }

    private fun watchCameraPipReturn() {
        if (pipReturnWatchdog?.isActive == true) return
        pipReturnWatchdog = lifecycleScope.launch {
            delay(PIP_RETURN_TIMEOUT_MS)
            if (pipReturnRequested || pipCloseArmed) {
                pipReturnRequested = false
                Log.w(PIP_TAG, "Camera PiP fullscreen return did not complete")
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    finishStoppedCameraPip(R.string.camera_pip_return_failed)
                } else {
                    viewModel.showNotice(getString(R.string.camera_pip_return_failed))
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
        pipEntryRequested = false
        if (isInPictureInPictureMode) {
            pipCloseArmed = true
            reconcileCameraPip()
        } else if (resumed) {
            restoreFullscreenTools()
        }
    }

    private fun restoreFullscreenTools() {
        pipReturnWatchdog?.cancel()
        pipReturnWatchdog = null
        pipMode = false
        pipEntryRequested = false
        pipCloseArmed = false
        pipReturnRequested = false
        pendingMirrorIntent?.let {
            pendingMirrorIntent = null
            acceptMirrorIntent(it)
        }
    }

    private fun launchArtefactShare(chooser: Intent): Boolean {
        if (!canUseSessionTools()) return false
        shareChooserOpen = true
        return try {
            shareArtefact.launch(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            shareChooserOpen = false
            false
        }
    }

    /** The service runs either way; without the grant its notification is simply not shown. */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Appended after the existing launchers so restored picker results keep their registry keys.
    private val shareArtefact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        shareChooserOpen = false
    }
    private var notificationsRequested = false

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsRequested) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationsRequested = true
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Honours a second request to mirror something.
     *
     * The activity is reused rather than stacked, so this is where a new destination arrives while
     * an old one is still connected. A materially different request tears the running session down
     * before the screen is rebuilt against the new intent; without that the old connection simply
     * stayed, and the screen relabelled itself as a device it was not showing.
     *
     * A repeat of what is already running is deliberately not restarted. It still takes any file
     * that came with it, which is the one thing such a request can be asking for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RETURN_FROM_PIP) {
            if (pipCloseArmed || isInPictureInPictureMode) watchCameraPipReturn()
            return
        }
        if (MirrorTarget.from(intent) == null) return
        if (pipCloseArmed || isInPictureInPictureMode || hideSessionTools) watchCameraPipReturn()
        if (isInPictureInPictureMode || hideSessionTools) {
            // A second link may select a physical/virtual screen or carry a file. Keep the old
            // camera and the incoming request owned until the full-sized UI can handle it.
            pendingMirrorIntent = Intent(intent)
            reconcileCameraPip()
            return
        }
        acceptMirrorIntent(intent)
    }

    private fun acceptMirrorIntent(intent: Intent) {
        val next = MirrorTarget.from(intent) ?: return
        val current = MirrorTarget.from(getIntent())
        if (current == null || !sameEndpoint(current.host, current.port, next.host, next.port)) {
            viewModel.setInputLocked(false)
        }
        exitAfterRecording = false
        setIntent(intent)
        val sessionState = viewModel.state.value
        if (current != null && next.sameSessionAs(current) &&
            (sessionState is MirrorUiState.Connected || sessionState is MirrorUiState.Connecting)
        ) {
            consumeSharedFile(intent)
            return
        }
        // Matching a failed or disconnected target is a new request to connect, not a live session.
        viewModel.disconnect()
        recreate()
    }

    /**
     * Takes a file the intent is carrying, once.
     *
     * Consumed from the intent rather than guarded on a fresh start: a relaunch is rebuilt with
     * saved state, so keying on that skipped the new intent's file entirely. Removing the extra is
     * what stops a rotation resending one that already went.
     */
    private fun consumeSharedFile(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_SHARE_PATH) ?: return
        val name = intent.getStringExtra(EXTRA_SHARE_NAME)
        intent.removeExtra(EXTRA_SHARE_PATH)
        intent.removeExtra(EXTRA_SHARE_NAME)
        viewModel.queueSharedFile(path, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android rebuilds a killed or discarded activity from the intent that first launched it.
        // A device switched to since, and a file already taken from the intent, existed only in
        // memory: the rebuilt screen reconnected to the first device and queued a sent file again.
        savedInstanceState?.let { BundleCompat.getParcelable(it, STATE_TARGET, Intent::class.java) }?.let(::setIntent)
        exitAfterRecording = savedInstanceState?.getBoolean(STATE_EXIT_AFTER_RECORDING) == true
        pipMode = isInPictureInPictureMode
        pipCloseArmed = pipMode
        documentPickerOpen = savedInstanceState?.getBoolean(STATE_DOCUMENT_PICKER) == true
        shareChooserOpen = savedInstanceState?.getBoolean(STATE_SHARE_CHOOSER) == true
        pendingMirrorIntent = savedInstanceState?.let {
            BundleCompat.getParcelable(it, STATE_PENDING_TARGET, Intent::class.java)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT)
        if (host.isEmpty()) {
            finish()
            return
        }

        val repository = DeviceRepository.get(this)
        val savedDeviceId = intent.getStringExtra(EXTRA_DEVICE_ID)?.takeIf { id ->
            // Matched the same way the address was recognised in the first place. Comparing the
            // raw spelling instead meant a link that differed only in case or a trailing dot was
            // paired with its saved device by the device list and then rejected here, so the
            // session quietly ran on global settings with no calibration and no port discovery.
            repository.getDevices().any {
                it.id == id && sameEndpoint(it.host, it.port, host, port)
            }
        }
        val initialSettings = repository.connectionSettings(savedDeviceId)
        // A link may ask for a different codec, sound or screen behaviour. Applied on top of
        // whatever this device would otherwise use, and only for this session: nothing here is
        // written back, so the saved profile is untouched when the session ends.
        val linkRequest = LinkRequest.from(intent)
        val settingsFor = { base: MirrorSettings -> linkRequest.applyTo(base) }
        // A recreated recording offer must not revive a session the user explicitly ended.
        if (!exitAfterRecording) {
            viewModel.connect(host, port, settingsFor(initialSettings), allowPortDiscovery = savedDeviceId != null,
                historyDeviceId = savedDeviceId, linkOptions = !linkRequest.isEmpty) {
                settingsFor(repository.connectionSettings(savedDeviceId))
            }
        }
        // Consumed from the intent rather than guarded on a fresh start: a relaunch arrives
        // through onNewIntent and is rebuilt with saved state, so keying on that skipped the new
        // intent's file entirely. Clearing the extra is what stops a rotation resending one that
        // already went.
        consumeSharedFile(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                screenshots.state.collect {
                    if (!canUseSessionTools()) return@collect
                    screenshots.markPickerLaunched()?.let { name ->
                        documentPickerOpen = true
                        try { saveScreenshot.launch(name) } catch (_: ActivityNotFoundException) {
                            documentPickerOpen = false
                            screenshots.pickerUnavailable()
                        }
                    }
                }
            }
        }
        // Started while the Activity is in the foreground: Android 12 and newer reject a
        // foreground-service start once the process has already been backgrounded.
        val deviceLabel = repository.getDevices().firstOrNull { it.id == savedDeviceId }?.name
        // One derived reason drives the service, so the two things that need the process alive —
        // a live session and a pending transfer — can never tear down each other's hold.
        lifecycleScope.launch {
            viewModel.sessionHold.collect { hold ->
                if (hold == SessionHold.NONE) {
                    MirrorSessionService.stop(this@MirrorActivity)
                } else {
                    ensureNotificationPermission()
                    // The result is not inspected here: a refused start now counts itself, so the
                    // failure collector below is the single place that tells the user. Reporting
                    // in both would say it twice, and reporting only here said it only for audio.
                    MirrorSessionService.start(this@MirrorActivity, deviceLabel, hold)
                }
            }
        }
        lifecycleScope.launch {
            val failureBaseline = MirrorSessionService.startFailures.value
            MirrorSessionService.startFailures.collect { failures ->
                // The session keeps running; only the guarantee that it survives being hidden is
                // lost, and the user should hear that from the app rather than by losing it.
                if (failures != failureBaseline) viewModel.reportBackgroundSessionUnavailable()
            }
        }
        lifecycleScope.launch {
            val baseline = MirrorSessionService.stopRequests.value
            MirrorSessionService.stopRequests.collect { requests ->
                if (requests != baseline) leaveSession()
            }
        }
        lifecycleScope.launch {
            // Finishing was deferred because a recording or screenshot was still being kept; once
            // nothing the user asked to keep is in flight there is nothing left to stay for.
            combine(viewModel.finishedRecording, viewModel.recordingSaveInProgress, screenshots.state) { _, _, _ ->
                artefactWorkPending()
            }.collect { pending ->
                if (!pending && exitAfterRecording) {
                    exitAfterRecording = false
                    finish()
                }
            }
        }

        val uiTarget = requireNotNull(MirrorTarget.from(intent))
        setContent {
            ScrcpyRemoteTheme(darkTheme = true) {
                TargetScopedMirrorUi(uiTarget) {
                    MirrorScreen(
                        viewModel = viewModel,
                        repository = repository,
                        savedDeviceId = savedDeviceId,
                        windowDeviceKey = mirrorWindowDeviceKey(uiTarget.deviceId, uiTarget.host, uiTarget.port),
                        screenshots = screenshots,
                        linkRequest = linkRequest,
                        pipMode = pipMode,
                        hideSessionTools = hideSessionTools,
                        pipSupported = supportsCameraPip,
                        externalActivityOpen = documentPickerOpen || shareChooserOpen,
                        pendingConnection = pendingMirrorIntent != null,
                        fullscreenToolsAvailable = canUseSessionTools(),
                        canUseSessionTools = ::canUseSessionTools,
                        onPipDialogChanged = { pipDialogOpen = it },
                        onRequestCameraPip = { requestCameraPictureInPicture() },
                        onPipStateChanged = ::reconcileCameraPip,
                        onShareArtefact = ::launchArtefactShare,
                        onRequestScreenshot = {
                            if (canUseSessionTools()) screenshots.capture(screenshotSurface)
                        },
                        onRequestShareScreenshot = {
                            if (canUseSessionTools()) screenshots.shareCapture(screenshotSurface)
                        },
                        onSaveRecording = { name ->
                            if (canUseSessionTools()) {
                                documentPickerOpen = true
                                try { saveRecording.launch(name) } catch (_: ActivityNotFoundException) {
                                    documentPickerOpen = false
                                    viewModel.showNotice(getString(R.string.transfer_picker_unavailable))
                                }
                            }
                        },
                        onPickFileToSend = { openDocumentPicker(pickFileToSend, arrayOf("*/*")) },
                        // Some providers label an app package only as generic binary data.
                        onPickAppToInstall = {
                            openDocumentPicker(pickAppToInstall, APP_PICKER_TYPES)
                        },
                        onSurfaceBound = { view, attached ->
                            if (attached) screenshotSurface = view
                            else if (screenshotSurface === view) screenshotSurface = null
                        },
                        onLocalTypingChanged = {
                            typingLocally = it
                            viewModel.onLocalTypingChanged(it)
                        },
                        onDisconnect = ::leaveSession,
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system bars reappear after a swipe or an IME interaction; hide them again.
        if (hasFocus && !isInPictureInPictureMode) enterImmersiveMode()
        // Another window (split-screen, overlay, dialog) took focus: key UPs will go there, so
        // lift anything held now. Harmless for this app's own dialogs; they never forward keys.
        if (!hasFocus) viewModel.releaseHeldKeys()
    }

    /** Draws the mirrored screen edge to edge, including behind a display cutout. */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Reassigning is required: mutating the returned params in place does not apply them.
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The on-screen draft field owns the keyboard while it is open. */
    private var typingLocally = false

    /**
     * Sends physical key presses to the mirrored device.
     *
     * These run after the focused view has had its chance, so a Compose text field that is
     * consuming keystrokes keeps them, and [typingLocally] is a second guard for the draft field
     * that requests focus asynchronously. Anything the policy does not claim falls through to the
     * default handling, which keeps local Back, volume and shortcuts behaving exactly as before.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        consumedAsMouseButton(keyCode, event) ||
            forwardKey(MirrorViewModel.ACTION_DOWN, keyCode, event) ||
            super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        consumedAsMouseButton(keyCode, event) ||
            forwardKey(MirrorViewModel.ACTION_UP, keyCode, event) ||
            super.onKeyUp(keyCode, event)

    /**
     * A mouse's Back and Forward buttons arrive twice: once as a button press, which the mirrored
     * view already turns into remote navigation, and once as a synthesized key. Letting the key
     * through as well makes one click both navigate the device and open this app's leave-session
     * dialog. The button press is the authoritative one, so the key is dropped.
     *
     * Only while connected: with no session the view forwards nothing, and a mouse back button
     * should still do its ordinary local thing rather than silently nothing.
     *
     * The key only reaches here because the manifest keeps this screen on the older Back:
     * predictive back hands a Back key to the back handling before any view or [onKeyDown].
     */
    private fun consumedAsMouseButton(keyCode: Int, event: KeyEvent): Boolean =
        (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_FORWARD) &&
            event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            viewModel.forwardsMouseButtons

    private fun forwardKey(action: Int, keyCode: Int, event: KeyEvent): Boolean {
        val connected = viewModel.state.value is MirrorUiState.Connected
        if (!KeyForwardingPolicy.shouldForward(keyCode, connected, typingLocally)) return false
        // The device applies its own layout to the keycode and modifiers, which is what scrcpy's
        // default keyboard mode does. Repeats are forwarded so a held key repeats remotely too.
        return viewModel.sendKeyEvent(action, keyCode, event.repeatCount, event.metaState)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onVisibilityChanged(true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!isInPictureInPictureMode) restoreFullscreenTools()
    }

    override fun onPause() {
        resumed = false
        viewModel.releaseHeldKeys()
        super.onPause()
        // PiP is still STARTED and visible: onStop, not onPause, owns video/audio visibility.
    }

    override fun onStop() {
        super.onStop()
        viewModel.onVisibilityChanged(false)
        if (pipCloseArmed && !isChangingConfigurations) {
            if (pipReturnWatchdog?.isActive == true && !isFinishing) {
                // A task return can temporarily stop the pinned Activity. Its watchdog still
                // disconnects it if it never becomes visible again, so this cannot strand a
                // hidden live session or turn a return into native-close cleanup.
                Log.i(PIP_TAG, "Deferring PiP stop cleanup during fullscreen return")
                return
            }
            // Native PiP close need not finish the Activity. Do not leave its socket/service
            // running invisibly, and never mistake ordinary Home (which never arms this) for it.
            finishStoppedCameraPip()
        }
    }

    private fun finishStoppedCameraPip(@StringRes pendingNotice: Int = R.string.camera_pip_pending) {
        if (!isFinishing && (
                !getSystemService(PowerManager::class.java).isInteractive ||
                    getSystemService(KeyguardManager::class.java).isKeyguardLocked
                )
        ) {
            Log.i(PIP_TAG, "Camera PiP hidden by screen lock; preserving the background session")
            return
        }
        pipCloseArmed = false
        pipEntryRequested = false
        viewModel.disconnect()
        if (viewModel.finishedRecording.value != null) {
            exitAfterRecording = true
        } else if (pendingMirrorIntent == null &&
            !screenshots.state.value.busy && screenshots.state.value.pendingToken == null &&
            screenshots.state.value.shareFile == null && !viewModel.recordingSaveInProgress.value
        ) {
            finish()
        } else {
            viewModel.showNotice(getString(pendingNotice))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_TARGET, Intent(intent))
        outState.putBoolean(STATE_EXIT_AFTER_RECORDING, exitAfterRecording)
        outState.putBoolean(STATE_DOCUMENT_PICKER, documentPickerOpen)
        outState.putBoolean(STATE_SHARE_CHOOSER, shareChooserOpen)
        outState.putParcelable(STATE_PENDING_TARGET, pendingMirrorIntent)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.disconnect()
            MirrorSessionService.stop(this)
        }
    }

    companion object {
        /**
         * What the app picker shows: single apps, and app bundles, which document providers label
         * inconsistently, often as zip archives or as no particular type at all.
         */
        private val APP_PICKER_TYPES = arrayOf(ApkInstall.MIME_TYPE, "application/octet-stream", "application/zip",
            "application/x-zip-compressed", "application/vnd.apks", "application/vnd.apkm", "application/xapk-package-archive")
        private const val PIP_TAG = "CameraPiP"
        private const val ACTION_RETURN_FROM_PIP = "com.scrcpy.remote.RETURN_FROM_CAMERA_PIP"
        private const val PIP_RETURN_TIMEOUT_MS = 5_000L
        private const val STATE_EXIT_AFTER_RECORDING = "mirror.exitAfterRecording"
        private const val STATE_DOCUMENT_PICKER = "mirror.documentPicker"
        private const val STATE_SHARE_CHOOSER = "mirror.shareChooser"
        private const val STATE_PENDING_TARGET = "mirror.pendingTarget"
        private const val STATE_TARGET = "mirror.target"
        internal const val EXTRA_HOST = "host"
        internal const val EXTRA_PORT = "port"
        internal const val EXTRA_DEVICE_ID = "deviceId"
        internal const val EXTRA_SHARE_PATH = "sharePath"
        internal const val EXTRA_SHARE_NAME = "shareName"

        /**
         * Per-session requests from a connection link, carried as one query fragment.
         *
         * A single extra parsed by [LinkRequest] keeps the set of options in one place, so the URL
         * parser and the session cannot come to disagree about what a link may ask for.
         */
        internal const val EXTRA_LINK_OPTIONS = "linkOptions"
        internal const val DEFAULT_PORT = 5555

        fun newIntent(
            context: Context,
            host: String,
            port: Int,
            deviceId: String? = null,
            sharePath: String? = null,
            shareName: String? = null,
            linkOptions: String? = null,
        ): Intent =
            Intent(context, MirrorActivity::class.java)
                // The manifest's singleTask mode restores the existing PiP owner to fullscreen
                // for new requests instead of creating competing ViewModels/service owners.
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_SHARE_PATH, sharePath)
                .putExtra(EXTRA_SHARE_NAME, shareName)
                .putExtra(EXTRA_LINK_OPTIONS, linkOptions)
    }
}

internal fun MirrorTarget.uiStateKey(): List<String?> =
    arrayListOf(canonicalHost(host), port.toString(), deviceId, linkOptions.orEmpty())

@Composable
internal fun TargetScopedMirrorUi(target: MirrorTarget, content: @Composable () -> Unit) {
    val currentKey = target.uiStateKey()
    val holder = rememberSaveableStateHolder()
    var previousKey by rememberSaveable { mutableStateOf(currentKey) }
    if (previousKey != currentKey) {
        // Retain one target only, including after recreation; switching back starts fresh.
        holder.removeState(previousKey)
        previousKey = currentKey
    }
    holder.SaveableStateProvider(currentKey, content)
}

@Composable
private fun MirrorScreen(
    viewModel: MirrorViewModel,
    repository: DeviceRepository,
    savedDeviceId: String?,
    windowDeviceKey: String,
    screenshots: ScreenshotViewModel,
    linkRequest: LinkRequest,
    pipMode: Boolean,
    hideSessionTools: Boolean,
    pipSupported: Boolean,
    externalActivityOpen: Boolean,
    pendingConnection: Boolean,
    fullscreenToolsAvailable: Boolean,
    canUseSessionTools: () -> Boolean,
    onPipDialogChanged: (Boolean) -> Unit,
    onRequestCameraPip: () -> Unit,
    onPipStateChanged: () -> Unit,
    onShareArtefact: (Intent) -> Boolean,
    onRequestScreenshot: () -> Unit,
    onRequestShareScreenshot: () -> Unit,
    onSaveRecording: (String) -> Unit,
    onPickFileToSend: () -> Unit,
    onPickAppToInstall: () -> Unit,
    onSurfaceBound: (SurfaceView, Boolean) -> Unit,
    onLocalTypingChanged: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val inputState by viewModel.inputState.collectAsState()
    val audio by viewModel.audioState.collectAsState()
    val deviceScreenOff by viewModel.deviceScreenOff.collectAsState()
    val deviceApps by viewModel.apps.collectAsState()
    val transfer by viewModel.fileTransfer.collectAsState()
    val appOffer by viewModel.appOffer.collectAsState()
    val installOutcome by viewModel.installOutcome.collectAsState()
    val transferHold by viewModel.transferHoldsProcess.collectAsState()
    var showApps by rememberSaveable { mutableStateOf(false) }
    // Not saved: a request answered before a rotation must not be answered again after it.
    var compactRequests by remember { mutableIntStateOf(0) }
    var showEncoders by rememberSaveable { mutableStateOf(false) }
    val deviceEncoders by viewModel.encoders.collectAsState()
    var showAudioEncoders by rememberSaveable { mutableStateOf(false) }
    val deviceAudioEncoders by viewModel.audioEncoders.collectAsState()
    var showDisplays by rememberSaveable { mutableStateOf(false) }
    var showCameras by rememberSaveable { mutableStateOf(false) }
    var showStreamChoices by rememberSaveable { mutableStateOf(false) }
    val revertibleChoice by viewModel.revertibleChoice.collectAsState()
    val deviceDisplays by viewModel.displays.collectAsState()
    val deviceCameras by viewModel.cameras.collectAsState()
    val cameraTorchOn by viewModel.cameraTorch.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val checkup by viewModel.checkup.collectAsState()
    val failureHistory by viewModel.failureHistory.collectAsState()
    val failureReport by viewModel.failureReport.collectAsState()
    var showDiagnosis by rememberSaveable { mutableStateOf(false) }
    val recording by viewModel.recording.collectAsState()
    val recordingStatus by viewModel.recordingStatus.collectAsState()
    val recordingSaveInProgress by viewModel.recordingSaveInProgress.collectAsState()
    val desktopResizing by viewModel.desktopResizing.collectAsState()
    val finishedRecording by viewModel.finishedRecording.collectAsState()
    val recordingStarted = stringResource(R.string.recording_started)
    val recordingWithSound = stringResource(R.string.recording_started_with_sound)
    val recordingSoundOff = stringResource(R.string.recording_started_sound_off)
    val recordingNoSound = stringResource(R.string.recording_started_no_sound)
    // The codec of a session whose sound an MP4 cannot hold, while the user decides what to do.
    var soundChoice by rememberSaveable { mutableStateOf<AudioCodec?>(null) }
    val shareUnavailable = stringResource(R.string.artefact_share_failed)
    val capture by screenshots.state.collectAsState()
    val defaults by repository.settings.collectAsState()
    val devices by repository.devices.collectAsState()
    val savedDevice = devices.firstOrNull { it.id == savedDeviceId }
    val configured = savedDevice.effectiveSettings(defaults)
    val activeSettings by viewModel.activeSettings.collectAsState()
    val sessionTiming by viewModel.mediaTiming.collectAsState()
    val sessionFrames by viewModel.videoFrames.collectAsState()
    val sessionLatency by viewModel.videoLatency.collectAsState()
    val sessionOverrides by viewModel.sessionOverrides.collectAsState()
    val connected = state as? MirrorUiState.Connected
    val landscape = connected != null && connected.videoWidth > connected.videoHeight
    val lockBanner = remember { InputLockBannerPlacement() }
    val saveScope = rememberCoroutineScope()
    val context = LocalContext.current
    // Resources, not the context: string lookups must follow configuration and locale changes.
    val resources = LocalResources.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val viewportDensity = resources.displayMetrics.densityDpi
    LaunchedEffect(viewportSize, viewportDensity) {
        viewModel.updateDesktopViewport(viewportSize.width, viewportSize.height, viewportDensity)
    }
    val windowSafeInsets = WindowInsets.safeDrawing
    val outerMarginPx = with(density) { MIRROR_WINDOW_MARGIN.roundToPx() }
    val topDragReservePx = with(density) { MIRROR_WINDOW_TOP_RESERVE.roundToPx() }
    val sideBarFootprintPx = with(density) { (compactBarShortSide + ATTACHED_BAR_GAP).roundToPx() }
    val minShortSidePx = with(density) { MIRROR_WINDOW_MIN_SHORT_SIDE.roundToPx() }
    val safeLeft = windowSafeInsets.getLeft(density, layoutDirection) + outerMarginPx
    val safeTop = windowSafeInsets.getTop(density) + outerMarginPx
    val safeWindowBounds = ControlBounds(
        left = safeLeft,
        top = safeTop,
        right = (viewportSize.width - windowSafeInsets.getRight(density, layoutDirection) - outerMarginPx)
            .coerceAtLeast(safeLeft + 1),
        bottom = (viewportSize.height - windowSafeInsets.getBottom(density) - outerMarginPx)
            .coerceAtLeast(safeTop + 1),
    )
    val localLandscape = viewportSize.width > viewportSize.height
    val remoteLandscape = landscape
    var savedWindowFractions by remember(windowDeviceKey, localLandscape, remoteLandscape) {
        mutableStateOf(
            MirrorWindowPreferences.load(context, windowDeviceKey, localLandscape, remoteLandscape),
        )
    }
    var liveWindowRect by remember { mutableStateOf<MirrorWindowRect?>(null) }
    var draggingWindow by remember { mutableStateOf(false) }
    var resizingWindow by remember { mutableStateOf(false) }
    val settledWindowRect = if (connected != null && !hideSessionTools &&
        viewportSize.width > 0 && viewportSize.height > 0
    ) {
        computeMirrorWindowRect(
            safe = safeWindowBounds,
            videoWidth = connected.videoWidth,
            videoHeight = connected.videoHeight,
            topDragReservePx = topDragReservePx,
            sideBarFootprintPx = sideBarFootprintPx,
            minShortSidePx = minShortSidePx,
            saved = savedWindowFractions,
        )
    } else null
    val activeWindowRect = if (connected != null && !hideSessionTools) {
        liveWindowRect ?: settledWindowRect
    } else null
    var calibrationRevision by remember { mutableLongStateOf(0L) }
    var pendingCalibration by remember(savedDeviceId) {
        mutableStateOf<Map<Boolean, Pair<Long, TouchCalibration?>>>(emptyMap())
    }
    var temporaryPortraitX by rememberSaveable { mutableStateOf<Int?>(null) }
    var temporaryPortraitY by rememberSaveable { mutableStateOf<Int?>(null) }
    var temporaryLandscapeX by rememberSaveable { mutableStateOf<Int?>(null) }
    var temporaryLandscapeY by rememberSaveable { mutableStateOf<Int?>(null) }
    val temporary = if (landscape) temporaryLandscapeX?.let { TouchCalibration(it, temporaryLandscapeY ?: 0) }
        else temporaryPortraitX?.let { TouchCalibration(it, temporaryPortraitY ?: 0) }
    val persistedCalibration = if (savedDevice != null) {
        if (landscape) savedDevice.landscapeCalibration else savedDevice.portraitCalibration
    } else temporary
    val calibrationOverride = if (landscape in pendingCalibration) pendingCalibration[landscape]?.second else persistedCalibration
    val calibration = calibrationOverride ?: TouchCalibration(defaults.touchOffsetXDp, defaults.touchOffsetYDp)
    val settings = configured.copy(touchOffsetXDp = calibration.xDp, touchOffsetYDp = calibration.yDp)
    val orientationLabel = stringResource(if (landscape) R.string.profile_landscape else R.string.profile_portrait)
    val calibrationScope = if (savedDevice != null) {
        stringResource(R.string.profile_saved_scope, savedDevice.name, orientationLabel)
    } else stringResource(R.string.profile_temporary_scope, orientationLabel)
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var geometry by remember { mutableStateOf<VideoLayout?>(null) }
    // Magnification of the local view. Deliberately not saved across configuration changes: a
    // rotation reflows the picture completely, and coming back magnified to a spot that no longer
    // exists is more disorienting than starting fitted.
    var viewZoom by remember { mutableFloatStateOf(1f) }
    // Precision pad mode for this target, kept across recreation like the session's other
    // controls. Its speed is about this phone's user rather than the device, so it outlives the
    // session; see PrecisionPadPreferences.
    var precisionPad by rememberSaveable { mutableStateOf(false) }
    var padSpeed by rememberSaveable { mutableFloatStateOf(PrecisionPadPreferences.speed(context)) }
    val padHandle = remember { PrecisionPadHandle() }
    // Never for a camera, which takes no input, nor while the session's tools are hidden.
    val padActive = precisionPad && connected != null && activeSettings?.camera == null && !hideSessionTools
    // While More controls is open it is a menu over the session; TalkBack must not reach past it.
    var controlsModal by remember { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showDeviceSettings by rememberSaveable { mutableStateOf(false) }
    var savingDeviceSettings by remember { mutableStateOf(false) }
    var confirmApplySettings by rememberSaveable { mutableStateOf(false) }
    val dialogOpen = showApps || showEncoders || showAudioEncoders || showDisplays ||
        showCameras || showDetails || confirmLeave || showDeviceSettings ||
        savingDeviceSettings || confirmApplySettings || soundChoice != null ||
        appOffer != null || installOutcome != null
    val pipWorkPending = recording || finishedRecording != null || recordingSaveInProgress ||
        transfer.running || transferHold || capture.busy || capture.pendingToken != null ||
        capture.shareFile != null || externalActivityOpen
    SideEffect { onPipDialogChanged(dialogOpen) }
    LaunchedEffect(pipMode, state, activeSettings, pipWorkPending, pendingConnection) {
        if (pipMode) onPipStateChanged()
    }
    val pendingSettings = connected != null &&
        // Compared against what this session was actually asked to run, link overrides and any
        // session-only encoder included. Leaving either out left the saved profile looking
        // permanently unapplied: the banner never cleared, and applying it reconnected with the
        // same override and the same mismatch.
        activeSettings?.let {
            sessionOverrides.applyTo(linkRequest.applyTo(configured)).requiresReconnectFrom(it)
        } == true
    val snackbar = remember { SnackbarHostState() }
    // The question is about this session's settings. Once nothing is waiting — the session
    // dropped or reconnected, or the process was restored onto a new one — it goes rather than
    // offering a reconnect that would apply nothing.
    LaunchedEffect(pendingSettings) {
        if (!pendingSettings) confirmApplySettings = false
    }
    // Background audio never restarts the stream, so the running session has to be told about a
    // change directly. Keyed on the effective value so every route that can change it — this
    // device's profile or the global default — reaches the session the same way.
    LaunchedEffect(configured.backgroundAudio) {
        viewModel.onBackgroundAudioChanged(configured.backgroundAudio)
    }
    fun updateCalibration(value: TouchCalibration?) {
        if (savedDevice != null) {
            val orientation = landscape
            val id = savedDevice.id
            val revision = ++calibrationRevision
            pendingCalibration = pendingCalibration + (orientation to (revision to value))
            saveScope.launch {
                val saved = repository.mutate { setCalibration(id, orientation, value) }
                if (pendingCalibration[orientation]?.first == revision) {
                    pendingCalibration = pendingCalibration - orientation
                    if (!saved) snackbar.showSnackbar(resources.getString(R.string.calibration_save_failed))
                }
            }
        }
        else if (landscape) {
            temporaryLandscapeX = value?.xDp
            temporaryLandscapeY = value?.yDp
        } else {
            temporaryPortraitX = value?.xDp
            temporaryPortraitY = value?.yDp
        }
    }

    LaunchedEffect(showDiagnostics, hideSessionTools) {
        viewModel.diagnosticsEnabled = showDiagnostics && !hideSessionTools
    }
    // A new capture — a reconnect, a rotation, or a switch to a camera — reflows the picture
    // completely, and a magnified corner of the old one lands nowhere meaningful on the new.
    // Found in live testing: switching to a camera while zoomed left a 4x crop of a feed that
    // had nothing to do with what had been magnified.
    LaunchedEffect(connected?.sessionGeneration, connected?.captureGeneration) {
        viewZoom = 1f
    }
    LaunchedEffect(notice, hideSessionTools) {
        if (!hideSessionTools) notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() }
    }
    LaunchedEffect(capture.notice, hideSessionTools) {
        if (!hideSessionTools) capture.notice?.let { snackbar.showSnackbar(it); screenshots.clearNotice() }
    }
    // A capture taken for sharing goes straight to the chooser. Consumed either way, so a
    // chooser that cannot be shown does not leave the screenshot button permanently busy.
    LaunchedEffect(capture.shareFile, fullscreenToolsAvailable) {
        val file = capture.shareFile ?: return@LaunchedEffect
        if (!canUseSessionTools()) return@LaunchedEffect
        val chooser = ArtefactSharing.chooserFor(context, file, ArtefactSharing.MIME_SCREENSHOT)
        if (chooser == null || !onShareArtefact(chooser)) {
            snackbar.showSnackbar(shareUnavailable)
        }
        screenshots.consumeShare()
    }
    // Each storage problem once, newest first: one counts as seen once its message has had its
    // time, and the next waiting is shown after it. Left in a list as long as the process, the last
    // one used to open every session after it. Not keyed on the list, so that acknowledging does
    // not cut off the message being shown; one cut off by picture-in-picture is shown again on return.
    LaunchedEffect(hideSessionTools) {
        if (hideSessionTools) return@LaunchedEffect
        repository.errors.collect { issues ->
            val newest = issues.lastOrNull() ?: return@collect
            snackbar.showSnackbar(newest.message)
            repository.acknowledge(listOf(newest))
        }
    }
    BackHandler(enabled = !hideSessionTools) {
        if (state is MirrorUiState.Connected) confirmLeave = true else onDisconnect()
    }
    if (showDiagnosis && state is MirrorUiState.Failed) failureReport?.let { report ->
        ConnectionDiagnosisDialog(
            report = report,
            onRetry = viewModel::reconnect,
            onRepair = {
                // The pairing dialog lives on the device list.
                context.startActivity(Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_PAIRING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                onDisconnect()
            },
            onDismiss = { showDiagnosis = false },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewportSize = it }
            .testTag("mirror-content"),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            is MirrorUiState.Connecting -> if (hideSessionTools) {
                CameraPipStatus(R.string.camera_pip_connecting)
            } else {
                ConnectionStatus(current.status, connecting = true, onDismiss = onDisconnect, checkup = checkup)
            }

            is MirrorUiState.Failed -> if (hideSessionTools) {
                CameraPipStatus(R.string.camera_pip_failed)
            } else ConnectionStatus(
                message = current.message,
                connecting = false,
                onRetry = viewModel::reconnect,
                onDismiss = onDisconnect,
                showGenericHint = !current.guided,
                checkup = checkup,
                history = failureHistory,
                revertLabel = revertibleChoice?.let {
                    stringResource(R.string.stream_choices_revert, streamChoiceSummary(it, activeSettings?.audioEnabled != false))
                },
                onRevert = viewModel::revertStreamChoice,
                onDiagnose = failureReport?.let { { showDiagnosis = true } },
                technicalDetails = current.details,
            )

            MirrorUiState.Disconnected -> if (hideSessionTools) {
                CameraPipStatus(R.string.camera_pip_disconnected)
            } else Text(stringResource(R.string.disconnected), color = Color.White)

            is MirrorUiState.Connected -> if (hideSessionTools && activeSettings?.camera == null) {
                CameraPipStatus(R.string.camera_pip_failed)
            } else {
                val windowCornerRadiusPx = if (activeWindowRect != null) {
                    val minSide = minOf(activeWindowRect.width, activeWindowRect.height).toFloat()
                    (minSide * 0.095f).coerceIn(
                        with(density) { MIRROR_WINDOW_MIN_CORNER_RADIUS.toPx() },
                        with(density) { MIRROR_WINDOW_MAX_CORNER_RADIUS.toPx() },
                    )
                } else 0f
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = if (activeWindowRect != null) AbsoluteAlignment.TopLeft else Alignment.Center,
                ) {
                    val surfaceContainerModifier = if (activeWindowRect != null) {
                        Modifier
                            .absoluteOffset { IntOffset(activeWindowRect.left, activeWindowRect.top) }
                            .requiredSize(
                                with(density) { activeWindowRect.width.toDp() },
                                with(density) { activeWindowRect.height.toDp() },
                            )
                    } else {
                        Modifier.fillMaxSize()
                    }
                    Box(surfaceContainerModifier) {
                        key(current.sessionGeneration) {
                            MirrorSurface(
                                viewModel = viewModel,
                                state = current,
                                settings = settings,
                                cameraSession = activeSettings?.camera != null,
                                inputState = inputState,
                                zoom = viewZoom,
                                cornerRadiusPx = windowCornerRadiusPx,
                                onGeometryChanged = { geometry = it },
                                onSurfaceBound = onSurfaceBound,
                                precisionPad = padActive,
                                padSpeed = padSpeed,
                                padHandle = padHandle,
                                hiddenFromAccessibility = controlsModal,
                            )
                        }
                        if (activeWindowRect != null) {
                            MirrorWindowChrome(
                                cornerRadiusPx = windowCornerRadiusPx,
                                active = draggingWindow || resizingWindow,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                    }
                    if (activeWindowRect != null && !inputState.locked) {
                        val latestSafe by rememberUpdatedState(safeWindowBounds)
                        val latestWindow by rememberUpdatedState(activeWindowRect)
                        val latestVideoW by rememberUpdatedState(current.videoWidth)
                        val latestVideoH by rememberUpdatedState(current.videoHeight)
                        val latestLocalLand by rememberUpdatedState(localLandscape)
                        val latestRemoteLand by rememberUpdatedState(remoteLandscape)
                        fun persistWindow(rect: MirrorWindowRect) {
                            val next = mirrorWindowToFractions(
                                rect = rect,
                                safe = latestSafe,
                                videoWidth = latestVideoW,
                                videoHeight = latestVideoH,
                                topDragReservePx = topDragReservePx,
                            )
                            savedWindowFractions = next
                            MirrorWindowPreferences.save(
                                context,
                                windowDeviceKey,
                                latestLocalLand,
                                latestRemoteLand,
                                next,
                            )
                        }
                        MirrorWindowTopDragBar(
                            windowRect = activeWindowRect,
                            active = draggingWindow,
                            onDragStart = {
                                draggingWindow = true
                                liveWindowRect = latestWindow
                                latestWindow
                            },
                            onDrag = { startRect, totalDx, totalDy ->
                                liveWindowRect = dragMirrorWindow(
                                    current = startRect,
                                    deltaX = totalDx,
                                    deltaY = totalDy,
                                    safe = latestSafe,
                                    topDragReservePx = topDragReservePx,
                                )
                            },
                            onDragEnd = {
                                draggingWindow = false
                                liveWindowRect?.let(::persistWindow)
                                liveWindowRect = null
                            },
                        )
                        MirrorWindowCornerResizeHandle(
                            windowRect = activeWindowRect,
                            viewportSize = viewportSize,
                            cornerRadiusPx = windowCornerRadiusPx,
                            active = resizingWindow,
                            onResizeStart = {
                                resizingWindow = true
                                liveWindowRect = latestWindow
                                latestWindow
                            },
                            onResize = { startRect, totalDx, totalDy ->
                                liveWindowRect = resizeMirrorWindowFromCorner(
                                    start = startRect,
                                    totalDeltaX = totalDx,
                                    totalDeltaY = totalDy,
                                    safe = latestSafe,
                                    videoWidth = latestVideoW,
                                    videoHeight = latestVideoH,
                                    topDragReservePx = topDragReservePx,
                                    minShortSidePx = minShortSidePx,
                                )
                            },
                            onResizeEnd = {
                                resizingWindow = false
                                liveWindowRect?.let(::persistWindow)
                                liveWindowRect = null
                            },
                        )
                    }
                }
            }
        }

        if (connected != null && !hideSessionTools) {
            MirrorStatsChip(viewModel)
            if (showDiagnostics) {
                MirrorDiagnostics(
                    viewModel = viewModel,
                    layout = geometry,
                    videoWidth = connected.videoWidth,
                    videoHeight = connected.videoHeight,
                    videoOffsetX = (activeWindowRect?.left ?: 0).toFloat(),
                    videoOffsetY = (activeWindowRect?.top ?: 0).toFloat(),
                )
            }

            // Under the floating controls, so opening them covers it; locked, the lock banner
            // speaks for the screen instead. Gone while More controls is open, which would
            // otherwise only cover it from sight, not from TalkBack.
            if (padActive && !inputState.locked && !controlsModal) {
                ScrcpyRemoteTheme(darkTheme = true) {
                    PrecisionPadBar(
                        dragging = padHandle.dragging,
                        speed = padSpeed,
                        cursorY = { padHandle.cursorY },
                        onToggleDrag = padHandle::toggleDrag,
                        onSpeedChange = {
                            padSpeed = it
                            PrecisionPadPreferences.saveSpeed(context, it)
                        },
                        onExit = { precisionPad = false },
                    )
                }
            }

            FloatingControlsOverlay(
                actions = rememberMirrorActions(
                    viewModel = viewModel,
                    settings = settings,
                    deviceScreenOff = deviceScreenOff,
                    onOpenApps = { if (canUseSessionTools()) showApps = true },
                    onDisconnect = onDisconnect,
                    onExit = { if (canUseSessionTools()) confirmLeave = true },
                    onSettingsChanged = {
                        updateCalibration(TouchCalibration(it.touchOffsetXDp, it.touchOffsetYDp))
                    },
                    diagnosticsVisible = showDiagnostics,
                    onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                    audioError = audio.error,
                    audioDupRefused = audio.dupRefused,
                    calibrationScope = calibrationScope,
                    calibrationOverridesDefaults = calibrationOverride != null,
                    onUseGlobalCalibration = { updateCalibration(null) },
                    screenshotBusy = capture.busy,
                    onScreenshot = onRequestScreenshot,
                    recording = recording,
                    onConnectionDetails = { if (canUseSessionTools()) showDetails = true },
                    onToggleRecording = {
                        if (canUseSessionTools()) {
                            if (viewModel.recording.value) viewModel.stopRecording()
                            else when (val sound = viewModel.recordingSound()) {
                                RecordingSound.WithSound ->
                                    if (viewModel.startRecording(withSound = true)) viewModel.showNotice(recordingWithSound)
                                is RecordingSound.NeedsAac -> soundChoice = sound.codec
                                is RecordingSound.PictureOnly -> if (viewModel.startRecording()) {
                                    viewModel.showNotice(if (sound.soundOff) recordingSoundOff else recordingNoSound)
                                }
                            }
                        }
                    },
                    onDeviceSettings = if (savedDevice != null) ({
                        if (canUseSessionTools()) showDeviceSettings = true
                    }) else null,
                    pendingSettings = pendingSettings,
                    onApplySettings = { if (canUseSessionTools()) confirmApplySettings = true },
                    onSendFile = onPickFileToSend,
                    sendFileBusy = transfer.running,
                    onOpenEncoders = { if (canUseSessionTools()) showEncoders = true },
                    onOpenDisplays = { if (canUseSessionTools()) showDisplays = true },
                    onOpenCameras = { if (canUseSessionTools()) showCameras = true },
                    onStreamChoices = { if (canUseSessionTools()) showStreamChoices = true },
                    cameraSession = activeSettings?.camera != null,
                    cameraTorchOn = cameraTorchOn,
                    onCameraTorch = { viewModel.setCameraTorch(!cameraTorchOn) },
                    onCameraZoomIn = { viewModel.zoomCamera(inward = true) },
                    onCameraZoomOut = { viewModel.zoomCamera(inward = false) },
                    // Steps rather than a continuous control: the panel is a list of buttons, and
                    // a step that doubles reaches the useful range in three presses.
                    viewZoom = viewZoom,
                    onViewZoomIn = {
                        viewZoom = (viewZoom * 2f).coerceAtMost(VideoLayout.MAX_ZOOM)
                    },
                    onViewZoomOut = {
                        viewZoom = (viewZoom / 2f).coerceAtLeast(1f)
                    },
                    onViewZoomReset = { viewZoom = 1f },
                    onShareScreenshot = onRequestShareScreenshot,
                    onCameraPip = if (pipSupported && activeSettings?.camera != null)
                        onRequestCameraPip else null,
                    cameraPipBlocked = pipWorkPending || dialogOpen,
                    inputLocked = inputState.locked,
                    onInputLockedChange = { if (canUseSessionTools()) viewModel.setInputLocked(it) },
                    precisionPad = precisionPad,
                    onPrecisionPadChange = { if (canUseSessionTools()) precisionPad = it },
                    compactRequests = compactRequests,
                ),
                muted = audio.muted,
                modifier = Modifier.fillMaxSize(),
                videoLayout = geometry,
                onLocalTypingChanged = { typing ->
                    // The keyboard takes the user's attention; a press held on the device goes.
                    if (typing) padHandle.releaseDrag()
                    onLocalTypingChanged(typing)
                },
                inputLockBanner = lockBanner,
                onPanelModalChanged = { controlsModal = it },
                attachedWindow = activeWindowRect?.toControlBounds(),
            )
        }
        if (connected == null && inputState.locked && !hideSessionTools) {
            InputLockBanner(onUnlock = { viewModel.setInputLocked(false) },
                modifier = Modifier.align(Alignment.TopCenter), placement = lockBanner)
        }
        if (hideSessionTools && connected != null && (pipWorkPending || pendingConnection)) {
            CameraPipStatus(if (pendingConnection) R.string.camera_pip_connection_waiting
                else R.string.camera_pip_pending)
        }
        if (!hideSessionTools) {
            // Shown whatever the controls are doing: with them hidden there was nothing on screen
            // to say a recording was still running.
            recordingStatus?.let {
                RecordingIndicator(it, inputState.locked, lockBanner, Modifier.align(Alignment.TopStart))
            }
            TransferProgress(transfer, inputState.locked, lockBanner, onCancel = viewModel::cancelFileTransfer)
            SessionSnackbarHost(snackbar, inputState.locked, lockBanner, Modifier.align(Alignment.TopCenter))
        }
    }

    // Keep the Surface at the same composition location across both mode transitions. Only the
    // overlays below disappear; replacing the whole screen would dispose the decoder's surface.
    if (hideSessionTools) return
    // Only one transfer at a time, so an app waiting for an answer waits for the slot too.
    if (!transfer.running) {
        appOffer?.let { offer ->
            AppOfferDialog(
                offer = offer,
                onInstall = { viewModel.acceptAppOffer(install = true) },
                onCopy = { viewModel.acceptAppOffer(install = false) },
                onCancel = viewModel::dismissAppOffer,
            )
        }
    }
    installOutcome?.let { outcome ->
        AppInstallOutcomeDialog(
            outcome = outcome,
            // Only once the refreshed list shows it: an app with nothing to open has no Open.
            canOpen = outcome.packageName != null && !inputState.locked &&
                deviceApps.apps.any { it.packageName == outcome.packageName },
            onOpen = {
                compactRequests++
                if (!viewModel.openInstalledApp()) {
                    saveScope.launch { snackbar.showSnackbar(resources.getString(R.string.apps_launch_failed)) }
                }
            },
            onDone = viewModel::dismissInstallOutcome,
        )
    }
    if (showDetails) {
        val qualityHistory by viewModel.qualityHistory.collectAsState()
        ConnectionDetailsDialog(
            address = viewModel.endpointLabel(),
            deviceName = savedDevice?.name,
            // The live settings, not the profile: a link override or a session-only encoder can
            // make what is running differ from what is stored, and this exists to say which.
            settings = activeSettings,
            videoWidth = connected?.videoWidth ?: 0,
            videoHeight = connected?.videoHeight ?: 0,
            frames = sessionFrames,
            latency = sessionLatency,
            timing = sessionTiming,
            recording = recording,
            onStatsWatched = viewModel::setStatsWatched,
            onCopy = { text ->
                // The local clipboard, not the remote device's: these numbers are being collected
                // to paste somewhere on this phone, and pushing them to the device being mirrored
                // would be a surprising place for them to end up.
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        resources.getString(R.string.details_title), text))
                viewModel.showNotice(resources.getString(R.string.details_copied))
                showDetails = false
            },
            onDismiss = { showDetails = false },
            history = qualityHistory,
            audioState = audio,
        )
    }
    finishedRecording?.let { file ->
        // Asked for rather than saved automatically: a recording is often made to be thrown away,
        // and the picker is also the only way to put it somewhere the user can reach.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.recording_save_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RecordingSummary(file)
                    Text(stringResource(R.string.recording_save_message,
                        FileTransfers.formatSize(file.length())))
                    if (recordingSaveInProgress) {
                        Text(stringResource(R.string.recording_export_in_progress))
                    }
                    // Sharing is offered beside saving rather than after it: sending a clip to
                    // someone is at least as common as filing it, and going through the picker
                    // first means finding the file again in another app.
                    TextButton(enabled = !recordingSaveInProgress, onClick = {
                        if (canUseSessionTools()) {
                            val shared = viewModel.shareFinishedRecording { recording ->
                                val chooser = ArtefactSharing.chooserFor(
                                    context, recording, ArtefactSharing.MIME_RECORDING)
                                chooser != null && onShareArtefact(chooser)
                            }
                            if (!shared) {
                                viewModel.showNotice(shareUnavailable)
                            }
                        }
                    }) { Text(stringResource(R.string.artefact_share)) }
                }
            },
            confirmButton = {
                Button(enabled = !recordingSaveInProgress, onClick = { onSaveRecording(defaultRecordingName()) }) {
                    Text(stringResource(R.string.recording_save))
                }
            },
            dismissButton = {
                TextButton(enabled = !recordingSaveInProgress, onClick = { viewModel.consumeFinishedRecording(keep = false) }) {
                    Text(stringResource(R.string.recording_discard))
                }
            },
        )
    }
    if (connected == null && soundChoice != null) {
        // The question was about this session's sound; with no session it has no answer.
        LaunchedEffect(Unit) { soundChoice = null }
    }
    soundChoice?.let { codec ->
        RecordSoundChoiceDialog(
            codec = codec,
            onReconnect = {
                soundChoice = null
                if (canUseSessionTools()) viewModel.recordWithSoundAfterReconnect()
            },
            onPictureOnly = {
                soundChoice = null
                if (canUseSessionTools() && viewModel.startRecording()) viewModel.showNotice(recordingStarted)
            },
            onCancel = { soundChoice = null },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.session_leave_title)) },
            text = { Text(stringResource(R.string.session_leave_body)) },
            confirmButton = {
                // Answered: ending the session can still be waiting on a recording or screenshot,
                // and the question left up over that prompt offered a Stay that no longer stayed.
                Button(onClick = {
                    confirmLeave = false
                    onDisconnect()
                }) { Text(stringResource(R.string.exit)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.session_stay)) } },
        )
    }
    if (showDeviceSettings && savedDevice != null) {
        key(savedDevice.id) {
            DeviceSettingsDialog(
                device = savedDevice,
                defaults = defaults,
                sessionActive = connected != null,
                saving = savingDeviceSettings,
                onDismiss = { showDeviceSettings = false },
                onSave = { base, edited ->
                    if (!savingDeviceSettings) {
                        savingDeviceSettings = true
                        val id = savedDevice.id
                        saveScope.launch {
                            try {
                                if (repository.mutate { saveDeviceSettings(id, base, edited) }) showDeviceSettings = false
                            } finally {
                                savingDeviceSettings = false
                            }
                        }
                    }
                },
            )
        }
    }
    if (showApps) {
        // Owned by the dialog rather than the button: `showApps` is saved across process death,
        // so a restored dialog would otherwise reappear with an empty list and no way to load it.
        // Keyed on the session so a reconnect reloads rather than showing an empty device.
        LaunchedEffect(connected?.sessionGeneration) { viewModel.loadApps() }
        DeviceAppsDialog(
            state = deviceApps,
            onLaunch = { app, forceStop ->
                showApps = false
                compactRequests++
                if (!viewModel.launchApp(app.packageName, forceStop)) {
                    saveScope.launch { snackbar.showSnackbar(resources.getString(R.string.apps_launch_failed)) }
                }
            },
            onRetry = { viewModel.loadApps(force = true) },
            onDismiss = { showApps = false },
            onInstall = {
                showApps = false
                onPickAppToInstall()
            },
            // The page opens on the device and is answered there, through the mirror.
            onAppPage = if (DeviceAppPages.availableFor(activeSettings)) ({ app, page ->
                showApps = false
                compactRequests++
                viewModel.openAppPage(app, page)
            }) else null,
        )
    }
    if (showEncoders) {
        // Keyed on the session, not Unit: a reconnect clears the list, and a LaunchedEffect that
        // never re-ran would leave the dialog claiming the device reported no encoders.
        LaunchedEffect(connected?.sessionGeneration) { viewModel.loadEncoders() }
        // The codec the choice will really be used with. A link, or an earlier choice made for
        // this session, can run the stream on a codec the saved profile does not name, and an
        // encoder only works with the codec it was built for. Filtering by the profile's codec
        // offered encoders the running stream cannot use, and the device answers a mismatched
        // pair by refusing to start — so every reconnect failed the same way, including the
        // automatic ones, with no way back other than clearing the setting elsewhere.
        val codec = activeSettings?.videoCodec ?: configured.videoCodec
        // Writing a choice made for this codec into a profile naming another one would break that
        // profile the next time it is used by itself, so a session running on a codec of its own
        // keeps its choice to itself.
        val sessionOnly = savedDevice == null || codec != configured.videoCodec
        DeviceEncodersDialog(
            state = deviceEncoders,
            selected = if (sessionOnly) activeSettings?.videoEncoder else configured.videoEncoder,
            codec = codec,
            createsProfile = !sessionOnly && savedDevice?.mirroringSettings == null,
            // Without a saved device there is no profile to write the choice to, but the session
            // in front of the user can still use it. Applying it restarts the stream, which is
            // unavoidable: an encoder is chosen when the stream is created.
            sessionOnly = sessionOnly,
            onSelect = { encoder ->
                showEncoders = false
                val device = savedDevice
                if (sessionOnly || device == null) {
                    viewModel.useVideoEncoderForSession(encoder)
                } else {
                    val id = device.id
                    // Merged against the profile the device actually has, so a picked encoder cannot
                    // also rewrite every other setting back to whatever the dialog last saw.
                    val baseline = device.mirroringSettings
                    val edited = (baseline ?: DeviceMirroringSettings.from(defaults))
                        .copy(videoEncoder = encoder)
                    saveScope.launch {
                        val saved = repository.mutate { saveDeviceSettings(id, baseline, edited) }
                        snackbar.showSnackbar(resources.getString(
                            if (saved) R.string.encoders_saved else R.string.encoders_save_failed))
                    }
                }
            },
            onRetry = { viewModel.loadEncoders(force = true) },
            onDismiss = { showEncoders = false },
            onSwitch = { showEncoders = false; showAudioEncoders = true },
        )
    }
    if (showAudioEncoders) {
        LaunchedEffect(connected?.sessionGeneration) { viewModel.loadAudioEncoders() }
        // The same reasoning as the video encoders above: filter by the codec the session is
        // really running, and keep the choice to the session when that is not the profile's.
        val codec = activeSettings?.audioCodec ?: configured.audioCodec
        val sessionOnly = savedDevice == null || codec != configured.audioCodec
        DeviceAudioEncodersDialog(
            state = deviceAudioEncoders,
            selected = if (sessionOnly) activeSettings?.audioEncoder else configured.audioEncoder,
            codec = codec,
            createsProfile = !sessionOnly && savedDevice?.mirroringSettings == null,
            sessionOnly = sessionOnly,
            onSelect = { encoder ->
                showAudioEncoders = false
                val device = savedDevice
                if (sessionOnly || device == null) {
                    viewModel.useAudioEncoderForSession(encoder)
                } else {
                    val id = device.id
                    val baseline = device.mirroringSettings
                    val edited = (baseline ?: DeviceMirroringSettings.from(defaults))
                        .copy(audioEncoder = encoder)
                    saveScope.launch {
                        val saved = repository.mutate { saveDeviceSettings(id, baseline, edited) }
                        snackbar.showSnackbar(resources.getString(
                            if (saved) R.string.encoders_saved else R.string.encoders_save_failed))
                    }
                }
            },
            onRetry = { viewModel.loadAudioEncoders(force = true) },
            onDismiss = { showAudioEncoders = false },
            onSwitch = { showAudioEncoders = false; showEncoders = true },
        )
    }
    if (showDisplays) {
        LaunchedEffect(connected?.sessionGeneration) { viewModel.loadDisplays() }
        val current = activeSettings ?: configured
        val sessionOnly = savedDevice == null || sessionOverrides.display != null ||
            current.camera != null || current.virtualDisplay != null ||
            configured.camera != null || configured.virtualDisplay != null ||
            current.displayId != configured.displayId
        val selected = if (current.camera != null || current.virtualDisplay != null) null
            else if (sessionOnly) current.displayId else configured.displayId
        DeviceDisplaysDialog(
            state = deviceDisplays,
            selected = selected,
            createsProfile = !sessionOnly && savedDevice.mirroringSettings == null,
            virtualDisplayActive = current.virtualDisplay != null,
            sessionOnly = sessionOnly,
            recording = recording,
            adaptiveDesktopActive = sessionOverrides.display is SessionDisplay.Desktop && current.camera == null,
            desktopResizing = desktopResizing,
            onAdaptiveDesktop = if (connected == null) null else ({
                showDisplays = false
                viewModel.useAdaptiveDesktopForSession()
            }),
            onSelect = { id ->
                showDisplays = false
                val device = savedDevice
                if (sessionOnly) {
                    viewModel.useDisplayForSession(id)
                } else {
                    val deviceId = device.id
                    val baseline = device.mirroringSettings
                    val edited = (baseline ?: DeviceMirroringSettings.from(defaults))
                        .copy(displayId = id, virtualDisplay = null, camera = null)
                    saveScope.launch {
                        val saved = repository.mutate { saveDeviceSettings(deviceId, baseline, edited) }
                        snackbar.showSnackbar(resources.getString(
                            if (saved) R.string.displays_saved else R.string.displays_save_failed))
                    }
                }
            },
            onRetry = { viewModel.loadDisplays(force = true) },
            onDismiss = { showDisplays = false },
        )
    }
    if (showStreamChoices) {
        val running = activeSettings
        if (running == null || connected == null) {
            // Nothing runs to choose against: the session ended or is starting again.
            LaunchedEffect(Unit) { showStreamChoices = false }
        } else {
            LaunchedEffect(connected.sessionGeneration) {
                viewModel.loadEncoders()
                if (running.audioEnabled) viewModel.loadAudioEncoders()
            }
            StreamChoicesDialog(
                active = StreamChoice.of(running),
                audioEnabled = running.audioEnabled,
                videoEncoders = deviceEncoders,
                audioEncoders = deviceAudioEncoders,
                recording = recording,
                onApply = { choice ->
                    showStreamChoices = false
                    viewModel.applyStreamChoice(choice)
                },
                onCheckAgain = {
                    viewModel.loadEncoders(force = true)
                    if (running.audioEnabled) viewModel.loadAudioEncoders(force = true)
                },
                onDismiss = { showStreamChoices = false },
            )
        }
    }
    if (showCameras) {
        LaunchedEffect(connected?.sessionGeneration) { viewModel.loadCameras() }
        val sessionOnly = savedDevice == null || sessionOverrides.display != null || linkRequest.displayId != null
        DeviceCamerasDialog(
            state = deviceCameras,
            // The running session's camera, not the profile's: a session-only choice is exactly
            // the case this has to reflect, and the profile would still say "screen".
            selected = (activeSettings ?: configured).camera,
            createsProfile = !sessionOnly && savedDevice.mirroringSettings == null,
            sessionOnly = sessionOnly,
            onSelect = { capture ->
                showCameras = false
                // Null means "go back to the screen", so the whole group is cleared rather than
                // an empty camera being stored — a half-set camera is a session the server
                // refuses, and it would be stored as though it were a working choice.
                val chosen = capture?.takeIf { !it.id.isNullOrBlank() || it.facing != null }
                val device = savedDevice
                if (sessionOnly) {
                    viewModel.useCameraForSession(chosen)
                } else {
                    val deviceId = device.id
                    val baseline = device.mirroringSettings
                    val edited = (baseline ?: DeviceMirroringSettings.from(defaults))
                        .copy(camera = chosen)
                    saveScope.launch {
                        val saved = repository.mutate { saveDeviceSettings(deviceId, baseline, edited) }
                        snackbar.showSnackbar(resources.getString(
                            if (saved) R.string.cameras_saved else R.string.cameras_save_failed))
                    }
                }
            },
            onRetry = { viewModel.loadCameras(force = true) },
            onDismiss = { showCameras = false },
        )
    }
    if (confirmApplySettings && pendingSettings) {
        AlertDialog(
            onDismissRequest = { confirmApplySettings = false },
            title = { Text(stringResource(R.string.session_apply_settings_title)) },
            text = { Text(stringResource(R.string.session_apply_settings_help)) },
            confirmButton = {
                Button(onClick = {
                    confirmApplySettings = false
                    viewModel.reconnect()
                }) { Text(stringResource(R.string.session_apply_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmApplySettings = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CameraPipStatus(@StringRes message: Int) {
    Column(
        modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(8.dp)
            .testTag("camera-pip-status"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(message),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.camera_pip_return),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Reads the diagnostics flows inside the overlay instead of at the screen root.
 *
 * The timing and frame counters tick twice a second; observing them from [MirrorScreen] would
 * invalidate the entire mirror screen, including the controls, at that rate even when the overlay
 * is hidden.
 */
@Composable
private fun MirrorDiagnostics(
    viewModel: MirrorViewModel,
    layout: VideoLayout?,
    videoWidth: Int,
    videoHeight: Int,
    videoOffsetX: Float = 0f,
    videoOffsetY: Float = 0f,
) {
    val diagnostics by viewModel.diagnostics.collectAsState()
    val timing by viewModel.mediaTiming.collectAsState()
    val frames by viewModel.videoFrames.collectAsState()
    TouchDiagnosticsOverlay(
        diagnostics = diagnostics,
        layout = layout,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        timing = timing,
        video = frames,
        videoOffsetX = videoOffsetX,
        videoOffsetY = videoOffsetY,
    )
}

/** The live stats line, top-centre; reads its own flows so the screen root is not invalidated. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MirrorStatsChip(viewModel: MirrorViewModel) {
    val shown by viewModel.statsChip.collectAsState()
    if (!shown) return
    val frames by viewModel.videoFrames.collectAsState()
    val latency by viewModel.videoLatency.collectAsState()
    StatsChip(frames, latency,
        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 4.dp))
}

/** A dated file name for a recording, so saved files sort and read sensibly. */
private fun defaultRecordingName(): String = java.text.SimpleDateFormat(
    "yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()).let { "scrcpy-recording-$it.mp4" }

@Composable
private fun rememberMirrorActions(    viewModel: MirrorViewModel,
    settings: MirrorSettings,
    deviceScreenOff: Boolean,
    onOpenApps: () -> Unit,
    onDisconnect: () -> Unit,
    onExit: () -> Unit,
    onSettingsChanged: (MirrorSettings) -> Unit,
    diagnosticsVisible: Boolean,
    onToggleDiagnostics: () -> Unit,
    audioError: String?,
    audioDupRefused: Boolean,
    calibrationScope: String,
    calibrationOverridesDefaults: Boolean,
    onUseGlobalCalibration: () -> Unit,
    screenshotBusy: Boolean,
    onScreenshot: () -> Unit,
    recording: Boolean,
    onToggleRecording: () -> Unit,
    onConnectionDetails: () -> Unit,
    onDeviceSettings: (() -> Unit)?,
    pendingSettings: Boolean,
    onApplySettings: () -> Unit,
    onSendFile: () -> Unit,
    sendFileBusy: Boolean,
    onOpenEncoders: () -> Unit,
    onOpenDisplays: () -> Unit,
    onOpenCameras: () -> Unit,
    onStreamChoices: () -> Unit,
    cameraSession: Boolean,
    cameraTorchOn: Boolean,
    onCameraTorch: () -> Unit,
    onCameraZoomIn: () -> Unit,
    onCameraZoomOut: () -> Unit,
    viewZoom: Float,
    onViewZoomIn: () -> Unit,
    onViewZoomOut: () -> Unit,
    onViewZoomReset: () -> Unit,
    onShareScreenshot: () -> Unit,
    onCameraPip: (() -> Unit)?,
    cameraPipBlocked: Boolean,
    inputLocked: Boolean,
    onInputLockedChange: (Boolean) -> Unit,
    precisionPad: Boolean,
    onPrecisionPadChange: (Boolean) -> Unit,
    compactRequests: Int,
): MirrorActions {
    val sessionSettings by viewModel.activeSettings.collectAsState()
    val statsChipShown by viewModel.statsChip.collectAsState()
    return MirrorActions(
    onBack = {
        viewModel.send(ControlMessage.BackOrScreenOn(KeyEvent.ACTION_DOWN))
        viewModel.send(ControlMessage.BackOrScreenOn(KeyEvent.ACTION_UP))
    },
    onHome = { viewModel.sendKey(KeyEvent.KEYCODE_HOME) },
    onRecents = { viewModel.sendKey(KeyEvent.KEYCODE_APP_SWITCH) },
    onNotifications = { viewModel.send(ControlMessage.expandNotificationPanel()) },
    onQuickSettings = { viewModel.send(ControlMessage.expandSettingsPanel()) },
    onCollapsePanels = { viewModel.send(ControlMessage.collapsePanels()) },
    onConnectionDetails = onConnectionDetails,
    onOpenApps = onOpenApps,
    onRotate = { viewModel.send(ControlMessage.rotateDevice()) },
    onPower = { viewModel.sendKey(KeyEvent.KEYCODE_POWER) },
    onVolumeUp = { viewModel.sendKey(KeyEvent.KEYCODE_VOLUME_UP) },
    onVolumeDown = { viewModel.sendKey(KeyEvent.KEYCODE_VOLUME_DOWN) },
    onCopyFromDevice = { viewModel.pullClipboard() },
    onCutFromDevice = { viewModel.cutFromDevice() },
    onPasteToDevice = { viewModel.pushClipboard(paste = true) },
    onToggleMute = { viewModel.toggleAudioMute() },
    onToggleDeviceScreen = { viewModel.toggleDeviceScreen() },
    deviceScreenOff = deviceScreenOff,
    onText = viewModel::sendText,
    onLiveEdits = viewModel::applyLiveEdits,
    onRemoteKey = { viewModel.sendRemoteKey(it.keycode, it.metaState) },
    audioSource = sessionSettings?.takeIf { it.audioEnabled && it.camera == null }?.audioSource,
    audioSources = sessionSettings?.let {
        com.scrcpy.remote.scrcpy.AudioSource.entries
    }.orEmpty(),
    onAudioSource = viewModel::useAudioSourceForSession,
    statsChipShown = statsChipShown,
    onToggleStatsChip = { viewModel.setStatsChip(!statsChipShown) },
    onBackspace = { viewModel.sendRemoteKey(KeyEvent.KEYCODE_DEL, 0) },
    onEnter = { viewModel.sendRemoteKey(KeyEvent.KEYCODE_ENTER, 0) },
    onDisconnect = onDisconnect,
    onExit = onExit,
    touchOffsetX = settings.touchOffsetXDp,
    touchOffsetY = settings.touchOffsetYDp,
    onTouchOffsetChange = { x, y ->
        onSettingsChanged(settings.copy(touchOffsetXDp = x, touchOffsetYDp = y))
    },
    diagnosticsVisible = diagnosticsVisible,
    onToggleDiagnostics = onToggleDiagnostics,
    audioError = audioError,
    onRetryAudio = viewModel::retryAudio,
    calibrationScope = calibrationScope,
    calibrationOverridesDefaults = calibrationOverridesDefaults,
    onUseGlobalCalibration = onUseGlobalCalibration,
    screenshotBusy = screenshotBusy,
    onScreenshot = onScreenshot,
    recording = recording,
    onToggleRecording = onToggleRecording,
    onDeviceSettings = onDeviceSettings,
    pendingSettings = pendingSettings,
    onApplySettings = onApplySettings,
    onSendFile = onSendFile,
    sendFileBusy = sendFileBusy,
    onOpenEncoders = onOpenEncoders,
    onOpenDisplays = onOpenDisplays,
    onOpenCameras = onOpenCameras,
    onStreamChoices = onStreamChoices,
    cameraSession = cameraSession,
    cameraTorchOn = cameraTorchOn,
    onCameraTorch = onCameraTorch,
    onCameraZoomIn = onCameraZoomIn,
    onCameraZoomOut = onCameraZoomOut,
    viewZoom = viewZoom,
    onViewZoomIn = onViewZoomIn,
    onViewZoomOut = onViewZoomOut,
    onViewZoomReset = onViewZoomReset,
    onShareScreenshot = onShareScreenshot,
    onCameraPip = onCameraPip,
    cameraPipBlocked = cameraPipBlocked,
    inputLocked = inputLocked,
    onInputLockedChange = onInputLockedChange,
    precisionPad = precisionPad,
    onPrecisionPadChange = onPrecisionPadChange,
    compactRequests = compactRequests,
    audioDupRefused = audioDupRefused,
    onHearSoundHere = viewModel::hearSoundHere,
)
}

private val MIRROR_WINDOW_MARGIN = 8.dp
private val MIRROR_WINDOW_TOP_RESERVE = 26.dp
private val MIRROR_WINDOW_MIN_SHORT_SIDE = 140.dp
private val MIRROR_WINDOW_MIN_CORNER_RADIUS = 18.dp
private val MIRROR_WINDOW_MAX_CORNER_RADIUS = 32.dp
private val MIRROR_WINDOW_RESIZE_HIT_SIZE = 38.dp

/**
 * Masks outer corners over hardware surfaces on older API levels and draws the subtle hairline
 * bezel border around the rounded phone window.
 */
@Composable
private fun MirrorWindowChrome(
    cornerRadiusPx: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val maskPath = remember { Path() }
    Canvas(modifier) {
        val radius = cornerRadiusPx.coerceAtMost(minOf(size.width, size.height) / 2f)
        maskPath.reset()
        maskPath.fillType = PathFillType.EvenOdd
        maskPath.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        maskPath.addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(radius, radius),
            ),
        )
        drawPath(maskPath, Color.Black)
        val strokeWidth = 1.dp.toPx()
        val halfStroke = strokeWidth / 2f
        val borderRadius = (radius - halfStroke).coerceAtLeast(0f)
        drawRoundRect(
            color = Color.White.copy(alpha = if (active) 0.42f else 0.22f),
            topLeft = Offset(halfStroke, halfStroke),
            size = Size((size.width - strokeWidth).coerceAtLeast(0f), (size.height - strokeWidth).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(borderRadius, borderRadius),
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun MirrorWindowTopDragBar(
    windowRect: MirrorWindowRect,
    active: Boolean,
    onDragStart: () -> MirrorWindowRect,
    onDrag: (MirrorWindowRect, Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val hitHeightPx = with(density) { MIRROR_WINDOW_TOP_RESERVE.roundToPx() }
    val hitWidthPx = (windowRect.width * 0.5f)
        .roundToInt()
        .coerceIn(
            minOf(windowRect.width, with(density) { 84.dp.roundToPx() }),
            maxOf(windowRect.width.coerceAtLeast(1), with(density) { 160.dp.roundToPx() }),
        )
        .coerceAtMost(windowRect.width.coerceAtLeast(1))
    val left = windowRect.left + (windowRect.width - hitWidthPx) / 2
    val top = (windowRect.top - hitHeightPx).coerceAtLeast(0)
    val label = stringResource(R.string.mirror_window_drag_handle)
    var nodePosInRoot by remember { mutableStateOf(Offset.Zero) }
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)

    Box(
        modifier = Modifier
            .absoluteOffset { IntOffset(left, top) }
            .size(with(density) { hitWidthPx.toDp() }, MIRROR_WINDOW_TOP_RESERVE)
            .onPlaced { nodePosInRoot = it.positionInRoot() }
            .testTag("mirror-window-drag-bar")
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                var startRect: MirrorWindowRect? = null
                var startFingerRoot = Offset.Zero
                detectDragGestures(
                    onDragStart = { localOffset ->
                        startFingerRoot = nodePosInRoot + localOffset
                        startRect = latestOnDragStart()
                    },
                    onDragEnd = {
                        startRect = null
                        latestOnDragEnd()
                    },
                    onDragCancel = {
                        startRect = null
                        latestOnDragEnd()
                    },
                ) { change, _ ->
                    change.consume()
                    val origin = startRect ?: return@detectDragGestures
                    val currentFingerRoot = nodePosInRoot + change.position
                    latestOnDrag(
                        origin,
                        currentFingerRoot.x - startFingerRoot.x,
                        currentFingerRoot.y - startFingerRoot.y,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val pillWidthDp = with(density) { (hitWidthPx * 0.85f).roundToInt().toDp() }.coerceAtLeast(56.dp)
        Canvas(Modifier.size(pillWidthDp, 16.dp)) {
            drawRoundRect(
                color = Color(0xFF1C1C20).copy(alpha = 0.92f),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = if (active) 0.42f else 0.18f),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                style = Stroke(width = 1.dp.toPx()),
            )
            val grabW = (size.width * 0.42f).coerceIn(24.dp.toPx(), 44.dp.toPx())
            val grabH = 3.5.dp.toPx()
            drawRoundRect(
                color = Color.White.copy(alpha = if (active) 0.88f else 0.54f),
                topLeft = Offset((size.width - grabW) / 2f, (size.height - grabH) / 2f),
                size = Size(grabW, grabH),
                cornerRadius = CornerRadius(grabH / 2f, grabH / 2f),
            )
        }
    }
}

@Composable
private fun MirrorWindowCornerResizeHandle(
    windowRect: MirrorWindowRect,
    viewportSize: IntSize,
    cornerRadiusPx: Float,
    active: Boolean,
    onResizeStart: () -> MirrorWindowRect,
    onResize: (MirrorWindowRect, Float, Float) -> Unit,
    onResizeEnd: () -> Unit,
) {
    val density = LocalDensity.current
    val hitSizePx = with(density) { MIRROR_WINDOW_RESIZE_HIT_SIZE.roundToPx() }
    val overhangPx = with(density) { 14.dp.roundToPx() }
    val maxLeft = (viewportSize.width - hitSizePx).coerceAtLeast(0)
    val maxTop = (viewportSize.height - hitSizePx).coerceAtLeast(0)
    val left = (windowRect.right + overhangPx - hitSizePx).coerceIn(0, maxLeft)
    val top = (windowRect.bottom + overhangPx - hitSizePx).coerceIn(0, maxTop)
    val label = stringResource(R.string.mirror_window_resize_handle)
    var nodePosInRoot by remember { mutableStateOf(Offset.Zero) }
    val latestOnResizeStart by rememberUpdatedState(onResizeStart)
    val latestOnResize by rememberUpdatedState(onResize)
    val latestOnResizeEnd by rememberUpdatedState(onResizeEnd)

    Box(
        modifier = Modifier
            .absoluteOffset { IntOffset(left, top) }
            .size(MIRROR_WINDOW_RESIZE_HIT_SIZE)
            .onPlaced { nodePosInRoot = it.positionInRoot() }
            .testTag("mirror-window-resize-handle")
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                var startRect: MirrorWindowRect? = null
                var startFingerRoot = Offset.Zero
                detectDragGestures(
                    onDragStart = { localOffset ->
                        startFingerRoot = nodePosInRoot + localOffset
                        startRect = latestOnResizeStart()
                    },
                    onDragEnd = {
                        startRect = null
                        latestOnResizeEnd()
                    },
                    onDragCancel = {
                        startRect = null
                        latestOnResizeEnd()
                    },
                ) { change, _ ->
                    change.consume()
                    val origin = startRect ?: return@detectDragGestures
                    val currentFingerRoot = nodePosInRoot + change.position
                    latestOnResize(
                        origin,
                        currentFingerRoot.x - startFingerRoot.x,
                        currentFingerRoot.y - startFingerRoot.y,
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val arcRadius = (cornerRadiusPx + 3.dp.toPx()).coerceIn(14.dp.toPx(), 34.dp.toPx())
            val cornerCenterX = (windowRect.right - left) - cornerRadiusPx
            val cornerCenterY = (windowRect.bottom - top) - cornerRadiusPx
            drawArc(
                color = Color.White.copy(alpha = if (active) 0.92f else 0.56f),
                startAngle = 12f,
                sweepAngle = 66f,
                useCenter = false,
                topLeft = Offset(cornerCenterX - arcRadius, cornerCenterY - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun MirrorSurface(
    viewModel: MirrorViewModel,
    state: MirrorUiState.Connected,
    settings: MirrorSettings,
    cameraSession: Boolean,
    inputState: com.scrcpy.remote.session.SessionInputState,
    zoom: Float,
    cornerRadiusPx: Float = 0f,
    onGeometryChanged: (VideoLayout) -> Unit,
    onSurfaceBound: (SurfaceView, Boolean) -> Unit,
    precisionPad: Boolean,
    padSpeed: Float,
    padHandle: PrecisionPadHandle,
    hiddenFromAccessibility: Boolean,
) {
    val density = LocalDensity.current
    val label = stringResource(
        if (inputState.locked) R.string.input_locked_surface_description
        else if (cameraSession) R.string.camera_surface_description
        else if (precisionPad) R.string.precision_pad_surface_description
        else R.string.mirror_surface_description,
        state.deviceName,
    )
    AndroidView(
        modifier = Modifier.fillMaxSize().testTag("mirror-surface").semantics {
            contentDescription = label
            if (hiddenFromAccessibility) hideFromAccessibility()
        },
        factory = { context ->
            ScrcpyMirrorView(context).apply {
                onSurfaceBound(this.screenshotSurface, true)
                padHandle.view = this
                onPrecisionPadChanged = { dragging, _, cursorY -> padHandle.update(dragging, cursorY) }
                onTouch = { action, pointerId, x, y, pressure, srcW, srcH, rawX, rawY ->
                    viewModel.sendTouch(action, pointerId, x, y, pressure, srcW, srcH, rawX, rawY,
                        state.sessionGeneration, captureGeneration, this.inputState.generation)
                }
                onScroll = { x, y, horizontal, vertical, srcW, srcH ->
                    viewModel.sendScroll(x, y, horizontal, vertical, srcW, srcH,
                        state.sessionGeneration, captureGeneration, this.inputState.generation)
                }
                onMouseButton = { keycode ->
                    viewModel.sendMouseButton(keycode, state.sessionGeneration, captureGeneration,
                        this.inputState.generation)
                }
                this.onGeometryChanged = { rect -> post { onGeometryChanged(rect) } }
                surfaceHolder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        viewModel.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        viewModel.releaseSurface()
                    }
                })
            }
        },
        update = { view ->
            view.setInputState(inputState)
            view.applyTouchCalibration(
                with(density) { settings.touchOffsetXDp.dp.toPx() },
                with(density) { settings.touchOffsetYDp.dp.toPx() },
            )
            view.captureEdgeGestures = settings.captureEdgeGestures
            view.cornerRadiusPx = cornerRadiusPx
            view.setVideoSize(state.videoWidth, state.videoHeight, state.captureGeneration)
            view.setZoom(zoom)
            view.precisionPadSpeed = padSpeed
            view.precisionPad = precisionPad
            // The view's own node as well as the Compose one around it.
            view.importantForAccessibility = if (hiddenFromAccessibility) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        },
        onRelease = { view ->
            view.releaseAllPointers()
            view.onGeometryChanged = null
            view.onPrecisionPadChanged = null
            if (padHandle.view === view) padHandle.view = null
            onSurfaceBound(view.screenshotSurface, false)
        },
    )
}
