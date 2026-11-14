package com.scrcpy.remote.ui

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.effectiveSettings
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.session.AudioState
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.session.RecordingSound
import com.scrcpy.remote.session.SessionInputState
import com.scrcpy.remote.session.SessionOverrides
import com.scrcpy.remote.session.requiresReconnectFrom
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal enum class OverlayDialogRequest {
    APPS,
    ENCODERS,
    DISPLAYS,
    CAMERAS,
    DEVICE_SETTINGS,
    DETAILS,
    STREAM_CHOICES,
    APPLY_SETTINGS,
    SEND_FILE,
    SCREENSHOT,
    SHARE_SCREENSHOT,
    RECORD_SOUND_CHOICE,
    FINISHED_RECORDING,
}

/**
 * Manages the independent `TYPE_APPLICATION_OVERLAY` floating phone window, attached left control
 * bar, floating handle, and optional modal panel inside the active [MirrorSessionService].
 */
internal class MirrorOverlayController(
    context: Context,
    private val viewModel: MirrorViewModel,
    private val repository: DeviceRepository,
    private val savedDeviceId: String?,
    private val windowDeviceKey: String,
    private val linkRequest: LinkRequest,
    private val screenshots: ScreenshotViewModel,
    private val onAttached: () -> Unit = {},
    private val onExitRequested: () -> Unit,
    private val onDialogRequested: (OverlayDialogRequest) -> Unit,
    private val onUpdateTemporaryCalibration: (Boolean, TouchCalibration?) -> Unit,
    private val temporaryCalibrationProvider: (Boolean) -> TouchCalibration?,
    private val onOverlayUnavailable: () -> Unit,
) : MirrorSessionService.OverlayController {

    private val appContext = context.applicationContext
    private var attached = false
    private var serviceContext: Service? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null

    private var mirrorRoot: FrameLayout? = null
    private var mirrorParams: WindowManager.LayoutParams? = null
    private var mirrorView: ScrcpyMirrorView? = null
    private var dragBarView: OverlayTopDragView? = null
    private var bezelStrokeView: OverlayBezelView? = null
    private var resizeHandleView: OverlayCornerResizeView? = null

    private var leftBarView: ComposeView? = null
    private var leftBarParams: WindowManager.LayoutParams? = null

    private var handleView: FrameLayout? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var panelView: FrameLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var lockBannerView: ComposeView? = null
    private var lockBannerParams: WindowManager.LayoutParams? = null

    private var padBarView: ComposeView? = null
    private var padBarParams: WindowManager.LayoutParams? = null

    private var ownsSurface = false

    // Shared reactive state observed by Compose views across overlay windows.
    private var connectedState by mutableStateOf(viewModel.state.value as? MirrorUiState.Connected)
    private var activeSettingsState by mutableStateOf(viewModel.activeSettings.value)
    private var sessionOverridesState by mutableStateOf(viewModel.sessionOverrides.value)
    private var audioState by mutableStateOf(viewModel.audioState.value)
    private var inputState by mutableStateOf(viewModel.inputState.value)
    private var deviceScreenOff by mutableStateOf(viewModel.deviceScreenOff.value)
    private var cameraTorchOn by mutableStateOf(viewModel.cameraTorch.value)
    private var recording by mutableStateOf(viewModel.recording.value)
    private var statsChipShown by mutableStateOf(viewModel.statsChip.value)
    private var screenshotBusy by mutableStateOf(screenshots.state.value.busy)
    private var transferRunning by mutableStateOf(viewModel.fileTransfer.value.running)
    private var defaults by mutableStateOf(repository.settings.value)
    private var devices by mutableStateOf(repository.devices.value)

    private var panelOpen by mutableStateOf(false)
    private var precisionPad by mutableStateOf(false)
    private var padSpeed by mutableFloatStateOf(PrecisionPadPreferences.speed(appContext))
    private val padHandle = PrecisionPadHandle()
    private var viewZoom by mutableFloatStateOf(1f)
    private var compactRequests by mutableIntStateOf(0)

    private var currentRect: MirrorWindowRect? = null
    private var safeBounds: ControlBounds = ControlBounds(0, 0, 1080, 1920)
    private var interactionActive = false
    private var pendingDisconnect = false

    private val density get() = appContext.resources.displayMetrics.density
    private fun dpToPx(dp: Float): Int = (dp * density).roundToInt()

    private val outerMarginPx get() = dpToPx(4f)
    private val topDragReservePx get() = dpToPx(26f)
    private val resizeOverhangPx get() = dpToPx(12f)
    private val minShortSidePx get() = dpToPx(140f)
    private val barGapPx get() = with(appContext.resources.displayMetrics) { (8f * density).roundToInt() }

    val activeTextureView get() = mirrorView?.screenshotTexture

    @Synchronized
    override fun attach(service: Service): Boolean {
        if (attached) return true
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        serviceContext = service
        windowManager = wm

        val owner = OverlayLifecycleOwner().apply { moveToResumed() }
        lifecycleOwner = owner
        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = overlayScope

        safeBounds = computeSafeBounds(wm)
        val initialConnected = viewModel.state.value as? MirrorUiState.Connected
        connectedState = initialConnected
        val rect = computeRectFor(initialConnected, loadFractions(initialConnected))
        currentRect = rect

        val added = runCatching {
            createMirrorWindow(service, wm, rect)
            createLeftBarWindow(service, wm, owner, rect)
            createHandleWindow(service, wm, owner)
            syncOptionalWindows()
        }.onFailure {
            detach()
            onOverlayUnavailable()
        }.isSuccess

        if (!added) return false

        attached = true
        syncMirrorViewProps()
        startCollectors(overlayScope)
        onAttached()
        return true
    }

    @Synchronized
    override fun detach() {
        persistCurrentWindow()
        attached = false
        val wm = windowManager

        mirrorView?.releaseAllPointers()
        mirrorView?.onGeometryChanged = null
        mirrorView?.onPrecisionPadChanged = null
        mirrorView?.onTextureSurfaceAvailable = null
        mirrorView?.onTextureSurfaceDestroyed = null
        if (padHandle.view === mirrorView) padHandle.view = null

        if (ownsSurface) {
            ownsSurface = false
            viewModel.releaseSurface()
        }
        viewModel.onLocalTypingChanged(false)
        viewModel.releaseHeldKeys()

        removeViewSafely(wm, panelView)
        removeViewSafely(wm, lockBannerView)
        removeViewSafely(wm, padBarView)
        removeViewSafely(wm, handleView)
        removeViewSafely(wm, leftBarView)
        removeViewSafely(wm, mirrorRoot)

        panelView = null
        panelParams = null
        lockBannerView = null
        lockBannerParams = null
        padBarView = null
        padBarParams = null
        handleView = null
        handleParams = null
        leftBarView = null
        leftBarParams = null
        mirrorRoot = null
        mirrorParams = null
        mirrorView = null
        dragBarView = null
        bezelStrokeView = null
        resizeHandleView = null

        panelOpen = false
        scope?.cancel()
        scope = null
        lifecycleOwner?.destroy()
        lifecycleOwner = null
        windowManager = null
        serviceContext = null
    }

    override fun onConfigurationChanged() {
        if (!attached) return
        val wm = windowManager ?: return
        safeBounds = computeSafeBounds(wm)
        recomputeSettledRect()
        clampFloatingHandle()
        syncOptionalWindows()
    }

    fun releaseActiveSurfaceOwnership() {
        if (ownsSurface) {
            ownsSurface = false
            viewModel.releaseSurface()
        }
    }

    private fun startCollectors(overlayScope: CoroutineScope) {
        overlayScope.launch {
            viewModel.state.collect { st ->
                val next = st as? MirrorUiState.Connected
                val prev = connectedState
                connectedState = next
                if (next == null) {
                    if (!pendingDisconnect) {
                        detach()
                        onOverlayUnavailable()
                    }
                    return@collect
                }
                if (prev == null || prev.videoWidth != next.videoWidth || prev.videoHeight != next.videoHeight) {
                    recomputeSettledRect()
                }
                if (prev?.sessionGeneration != next.sessionGeneration || prev.captureGeneration != next.captureGeneration) {
                    viewZoom = 1f
                }
                syncMirrorViewProps()
            }
        }
        overlayScope.launch { viewModel.activeSettings.collect { activeSettingsState = it } }
        overlayScope.launch { viewModel.sessionOverrides.collect { sessionOverridesState = it } }
        overlayScope.launch { viewModel.audioState.collect { audioState = it } }
        overlayScope.launch {
            viewModel.inputState.collect { st ->
                inputState = st
                if (st.locked) {
                    panelOpen = false
                }
                syncMirrorViewProps()
                syncOptionalWindows()
                updateBarVisibilityAndBounds()
            }
        }
        overlayScope.launch { viewModel.deviceScreenOff.collect { deviceScreenOff = it } }
        overlayScope.launch { viewModel.cameraTorch.collect { cameraTorchOn = it } }
        overlayScope.launch {
            viewModel.recording.collect { rec ->
                if (recording != rec) {
                    recording = rec
                    updateBarVisibilityAndBounds()
                }
            }
        }
        overlayScope.launch { viewModel.statsChip.collect { statsChipShown = it } }
        overlayScope.launch {
            viewModel.fileTransfer.collect { transferRunning = it.running }
        }
        overlayScope.launch {
            screenshots.state.collect { screenshotBusy = it.busy }
        }
        overlayScope.launch {
            combine(repository.settings, repository.devices) { d, list -> d to list }.collect { (d, list) ->
                defaults = d
                devices = list
                syncMirrorViewProps()
            }
        }
        overlayScope.launch {
            viewModel.finishedRecording.collect { file ->
                if (file != null && attached) {
                    panelOpen = false
                    syncOptionalWindows()
                    onDialogRequested(OverlayDialogRequest.FINISHED_RECORDING)
                }
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun overlayLayoutParams(
        width: Int,
        height: Int,
        x: Int = 0,
        y: Int = 0,
        gravity: Int = Gravity.TOP or Gravity.LEFT,
        focusable: Boolean = false,
        hardwareAccelerated: Boolean = false,
    ): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (hardwareAccelerated) {
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            if (focusable) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }
    }

    private fun computeSafeBounds(wm: WindowManager): ControlBounds {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
        val m = outerMarginPx
        return ControlBounds(
            left = m,
            top = m,
            right = (w - m).coerceAtLeast(m + 1),
            bottom = (h - m).coerceAtLeast(m + 1),
        )
    }

    private fun isLocalLandscape(): Boolean = safeBounds.width > safeBounds.height

    private fun isRemoteLandscape(connected: MirrorUiState.Connected?): Boolean =
        connected != null && connected.videoWidth > connected.videoHeight

    private fun loadFractions(connected: MirrorUiState.Connected?): MirrorWindowFractions? =
        MirrorWindowPreferences.load(
            context = appContext,
            deviceKey = windowDeviceKey,
            localLandscape = isLocalLandscape(),
            remoteLandscape = isRemoteLandscape(connected),
        )

    private fun computeRectFor(
        connected: MirrorUiState.Connected?,
        saved: MirrorWindowFractions?,
    ): MirrorWindowRect {
        val barWidthPx = with(DensityFromMetrics()) { compactBarShortSide.roundToPx() }
        val sideFootprint = barWidthPx + barGapPx
        return computeMirrorWindowRect(
            safe = safeBounds,
            videoWidth = connected?.videoWidth ?: 1080,
            videoHeight = connected?.videoHeight ?: 2400,
            topDragReservePx = topDragReservePx,
            sideBarFootprintPx = sideFootprint,
            minShortSidePx = minShortSidePx,
            saved = saved,
        )
    }

    private fun DensityFromMetrics() = androidx.compose.ui.unit.Density(appContext.resources.displayMetrics.density)

    private fun cornerRadiusFor(rect: MirrorWindowRect): Float {
        val minSide = minOf(rect.width, rect.height).toFloat()
        return (minSide * 0.095f).coerceIn(dpToPx(18f).toFloat(), dpToPx(32f).toFloat())
    }

    private fun recomputeSettledRect() {
        val wm = windowManager ?: return
        val next = computeRectFor(connectedState, loadFractions(connectedState))
        currentRect = next
        applyWindowGeometry(wm, next)
    }

    private fun persistCurrentWindow() {
        val rect = currentRect ?: return
        val connected = connectedState ?: return
        val fractions = mirrorWindowToFractions(
            rect = rect,
            safe = safeBounds,
            videoWidth = connected.videoWidth,
            videoHeight = connected.videoHeight,
            topDragReservePx = topDragReservePx,
        )
        MirrorWindowPreferences.save(
            context = appContext,
            deviceKey = windowDeviceKey,
            localLandscape = isLocalLandscape(),
            remoteLandscape = isRemoteLandscape(connected),
            fractions = fractions,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMirrorWindow(service: Service, wm: WindowManager, rect: MirrorWindowRect) {
        val root = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val mirror = ScrcpyMirrorView(service, useTextureView = true).apply {
            padHandle.view = this
            onPrecisionPadChanged = { dragging, _, cursorY -> padHandle.update(dragging, cursorY) }
            onTouch = { action, pointerId, x, y, pressure, srcW, srcH, rawX, rawY ->
                val state = connectedState
                if (state != null) {
                    viewModel.sendTouch(
                        action, pointerId, x, y, pressure, srcW, srcH, rawX, rawY,
                        state.sessionGeneration, captureGeneration, this.inputState.generation,
                    )
                }
            }
            onScroll = { x, y, horizontal, vertical, srcW, srcH ->
                val state = connectedState
                if (state != null) {
                    viewModel.sendScroll(
                        x, y, horizontal, vertical, srcW, srcH,
                        state.sessionGeneration, captureGeneration, this.inputState.generation,
                    )
                }
            }
            onMouseButton = { keycode ->
                val state = connectedState
                if (state != null) {
                    viewModel.sendMouseButton(
                        keycode, state.sessionGeneration, captureGeneration, this.inputState.generation,
                    )
                }
            }
            onTextureSurfaceAvailable = { surface ->
                ownsSurface = true
                viewModel.setSurface(surface)
            }
            onTextureSurfaceDestroyed = {
                if (ownsSurface) {
                    ownsSurface = false
                    viewModel.releaseSurface()
                }
            }
        }

        val bezel = OverlayBezelView(service)

        val dragBar = OverlayTopDragView(service).apply {
            contentDescription = service.getString(R.string.mirror_window_drag_handle)
            var startRawX = 0f
            var startRawY = 0f
            var startRect: MirrorWindowRect? = null
            setOnTouchListener { _, event ->
                if (inputState.locked) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startRect = currentRect
                        interactionActive = true
                        bezel.active = true
                        this.active = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val origin = startRect ?: return@setOnTouchListener true
                        val moved = dragMirrorWindow(
                            current = origin,
                            deltaX = event.rawX - startRawX,
                            deltaY = event.rawY - startRawY,
                            safe = safeBounds,
                            topDragReservePx = topDragReservePx,
                        )
                        currentRect = moved
                        applyWindowGeometry(wm, moved)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        startRect = null
                        interactionActive = false
                        bezel.active = false
                        this.active = false
                        persistCurrentWindow()
                        true
                    }
                    else -> false
                }
            }
        }

        val resizeHandle = OverlayCornerResizeView(service).apply {
            contentDescription = service.getString(R.string.mirror_window_resize_handle)
            var startRawX = 0f
            var startRawY = 0f
            var startRect: MirrorWindowRect? = null
            setOnTouchListener { _, event ->
                if (inputState.locked) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startRect = currentRect
                        interactionActive = true
                        bezel.active = true
                        this.active = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val origin = startRect ?: return@setOnTouchListener true
                        val connected = connectedState
                        val resized = resizeMirrorWindowFromCorner(
                            start = origin,
                            totalDeltaX = event.rawX - startRawX,
                            totalDeltaY = event.rawY - startRawY,
                            safe = safeBounds,
                            videoWidth = connected?.videoWidth ?: 1080,
                            videoHeight = connected?.videoHeight ?: 2400,
                            topDragReservePx = topDragReservePx,
                            minShortSidePx = minShortSidePx,
                        )
                        currentRect = resized
                        applyWindowGeometry(wm, resized)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        startRect = null
                        interactionActive = false
                        bezel.active = false
                        this.active = false
                        persistCurrentWindow()
                        true
                    }
                    else -> false
                }
            }
        }

        mirrorView = mirror
        bezelStrokeView = bezel
        dragBarView = dragBar
        resizeHandleView = resizeHandle

        root.addView(mirror)
        root.addView(bezel)
        root.addView(dragBar)
        root.addView(resizeHandle)

        val params = overlayLayoutParams(
            width = rect.width + resizeOverhangPx,
            height = rect.height + topDragReservePx + resizeOverhangPx,
            x = rect.left,
            y = (rect.top - topDragReservePx).coerceAtLeast(0),
            focusable = false,
            hardwareAccelerated = true,
        )
        mirrorRoot = root
        mirrorParams = params
        applyChildrenLayout(rect)
        wm.addView(root, params)
    }

    private fun applyChildrenLayout(rect: MirrorWindowRect) {
        val params = mirrorParams ?: return
        val topOffset = (rect.top - params.y).coerceAtLeast(0)
        val cornerPx = cornerRadiusFor(rect)

        mirrorView?.apply {
            cornerRadiusPx = cornerPx
            layoutParams = FrameLayout.LayoutParams(rect.width, rect.height).apply {
                leftMargin = 0
                topMargin = topOffset
            }
        }
        bezelStrokeView?.apply {
            cornerRadiusPx = cornerPx
            layoutParams = FrameLayout.LayoutParams(rect.width, rect.height).apply {
                leftMargin = 0
                topMargin = topOffset
            }
        }

        val dragW = (rect.width * 0.5f).roundToInt()
            .coerceIn(minOf(rect.width, dpToPx(84f)), maxOf(rect.width.coerceAtLeast(1), dpToPx(160f)))
            .coerceAtMost(rect.width.coerceAtLeast(1))
        dragBarView?.apply {
            visibility = if (inputState.locked) View.GONE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(dragW, topDragReservePx).apply {
                leftMargin = ((rect.width - dragW) / 2).coerceAtLeast(0)
                topMargin = 0
            }
        }

        val hitSize = dpToPx(38f)
        resizeHandleView?.apply {
            visibility = if (inputState.locked) View.GONE else View.VISIBLE
            this.cornerRadiusPx = cornerPx
            layoutParams = FrameLayout.LayoutParams(hitSize, hitSize).apply {
                leftMargin = (rect.width + resizeOverhangPx - hitSize).coerceAtLeast(0)
                topMargin = (topOffset + rect.height + resizeOverhangPx - hitSize).coerceAtLeast(0)
            }
        }
    }

    private fun applyWindowGeometry(wm: WindowManager, rect: MirrorWindowRect) {
        val root = mirrorRoot ?: return
        val params = mirrorParams ?: return
        val winX = rect.left.coerceAtLeast(0)
        val winY = (rect.top - topDragReservePx).coerceAtLeast(0)
        val topOffset = (rect.top - winY).coerceAtLeast(0)
        params.x = winX
        params.y = winY
        params.width = rect.width + resizeOverhangPx
        params.height = rect.height + topOffset + resizeOverhangPx
        applyChildrenLayout(rect)
        wm.updateViewLayout(root, params)
        updateBarPlacement(wm, rect)
    }

    private fun computeBarPlacement(rect: MirrorWindowRect): CompactNavigationPlacement {
        val cameraSession = activeSettingsState?.camera != null
        val count = attachedCompactButtonCount(cameraSession, recording)
        val d = DensityFromMetrics()
        val barW = with(d) { compactBarShortSide.roundToPx() }
        val barH = with(d) { compactBarLength(count).roundToPx() }
        return attachCompactNavigationToWindow(
            window = rect.toControlBounds(),
            safe = safeBounds,
            barWidth = barW,
            barHeight = barH,
            gap = barGapPx,
        )
    }

    private fun createLeftBarWindow(
        service: Service,
        wm: WindowManager,
        owner: OverlayLifecycleOwner,
        rect: MirrorWindowRect,
    ) {
        val placement = computeBarPlacement(rect)
        val params = overlayLayoutParams(
            width = placement.bounds.width,
            height = placement.bounds.height,
            x = placement.bounds.left,
            y = placement.bounds.top,
            focusable = false,
        )
        val barCompose = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            attachLifecycleOwners(this, owner)
            setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    val actions = buildOverlayActions()
                    CompactNavigationBar(
                        actions = actions,
                        onMore = { panelOpen = true; syncOptionalWindows() },
                        onInteraction = {},
                        docking = CompactNavigationPlacement(
                            bounds = ControlBounds(0, 0, placement.bounds.width, placement.bounds.height),
                            vertical = true,
                        ),
                    )
                }
            }
        }
        leftBarView = barCompose
        leftBarParams = params
        wm.addView(barCompose, params)
    }

    private fun updateBarPlacement(wm: WindowManager, rect: MirrorWindowRect) {
        val view = leftBarView ?: return
        val params = leftBarParams ?: return
        val placement = computeBarPlacement(rect)
        params.x = placement.bounds.left
        params.y = placement.bounds.top
        params.width = placement.bounds.width
        params.height = placement.bounds.height
        view.visibility = if (inputState.locked) View.GONE else View.VISIBLE
        wm.updateViewLayout(view, params)
    }

    private fun updateBarVisibilityAndBounds() {
        val wm = windowManager ?: return
        val rect = currentRect ?: return
        updateBarPlacement(wm, rect)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHandleWindow(
        service: Service,
        wm: WindowManager,
        owner: OverlayLifecycleOwner,
    ) {
        val containerSize = dpToPx(56f)
        val initialX = (safeBounds.right - containerSize).coerceAtLeast(safeBounds.left)
        val initialY = (safeBounds.top + (safeBounds.height - containerSize) / 2).coerceAtLeast(safeBounds.top)
        val params = overlayLayoutParams(
            width = containerSize,
            height = containerSize,
            x = initialX,
            y = initialY,
            focusable = false,
        )
        val container = FrameLayout(service).apply { setBackgroundColor(Color.TRANSPARENT) }
        val compose = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            attachLifecycleOwners(this, owner)
            setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(HANDLE_SIZE)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = stringResource(R.string.handle_description),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        val touchLayer = View(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            moved = true
                        }
                        if (moved) {
                            val maxX = (safeBounds.right - containerSize).coerceAtLeast(safeBounds.left)
                            val maxY = (safeBounds.bottom - containerSize).coerceAtLeast(safeBounds.top)
                            params.x = (startX + dx.roundToInt()).coerceIn(safeBounds.left, maxX)
                            params.y = (startY + dy.roundToInt()).coerceIn(safeBounds.top, maxY)
                            wm.updateViewLayout(container, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            panelOpen = !panelOpen
                            syncOptionalWindows()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        container.addView(compose, FrameLayout.LayoutParams(containerSize, containerSize))
        container.addView(touchLayer, FrameLayout.LayoutParams(containerSize, containerSize))
        handleView = container
        handleParams = params
        wm.addView(container, params)
    }

    private fun clampFloatingHandle() {
        val wm = windowManager ?: return
        val container = handleView ?: return
        val params = handleParams ?: return
        val maxX = (safeBounds.right - params.width).coerceAtLeast(safeBounds.left)
        val maxY = (safeBounds.bottom - params.height).coerceAtLeast(safeBounds.top)
        params.x = params.x.coerceIn(safeBounds.left, maxX)
        params.y = params.y.coerceIn(safeBounds.top, maxY)
        wm.updateViewLayout(container, params)
    }

    private fun syncOptionalWindows() {
        val wm = windowManager ?: return
        val service = serviceContext ?: return
        val owner = lifecycleOwner ?: return

        // ControlPanel modal window
        if (panelOpen && !inputState.locked) {
            if (panelView == null) {
                val params = overlayLayoutParams(
                    width = WindowManager.LayoutParams.MATCH_PARENT,
                    height = WindowManager.LayoutParams.MATCH_PARENT,
                    focusable = true,
                )
                val compose = ComposeView(service).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    attachLifecycleOwners(this, owner)
                    setContent {
                        ScrcpyRemoteTheme(darkTheme = true) {
                            OverlayControlPanelContent(
                                actions = buildOverlayActions(),
                                muted = audioState.muted,
                                onClosePanel = { closePanel() },
                                onLocalTypingChanged = { typing ->
                                    if (typing) padHandle.releaseDrag()
                                    viewModel.onLocalTypingChanged(typing)
                                },
                                onPrecisionPadTurnedOff = {
                                    if (precisionPad) {
                                        precisionPad = false
                                        syncMirrorViewProps()
                                        syncOptionalWindows()
                                    }
                                },
                            )
                        }
                    }
                }
                val panelContainer = object : FrameLayout(service) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                                closePanel()
                            }
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    isFocusableInTouchMode = true
                    addView(
                        compose,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                panelView = panelContainer
                panelParams = params
                wm.addView(panelContainer, params)
                panelContainer.requestFocus()
            }
        } else {
            panelView?.let { removeViewSafely(wm, it) }
            panelView = null
            panelParams = null
            viewModel.onLocalTypingChanged(false)
        }

        // InputLockBanner window
        if (inputState.locked) {
            if (lockBannerView == null) {
                val params = overlayLayoutParams(
                    width = WindowManager.LayoutParams.WRAP_CONTENT,
                    height = WindowManager.LayoutParams.WRAP_CONTENT,
                    x = 0,
                    y = outerMarginPx,
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    focusable = false,
                )
                val compose = ComposeView(service).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    attachLifecycleOwners(this, owner)
                    setContent {
                        ScrcpyRemoteTheme(darkTheme = true) {
                            InputLockBanner(onUnlock = { viewModel.setInputLocked(false) })
                        }
                    }
                }
                lockBannerView = compose
                lockBannerParams = params
                wm.addView(compose, params)
            }
        } else {
            lockBannerView?.let { removeViewSafely(wm, it) }
            lockBannerView = null
            lockBannerParams = null
        }

        // PrecisionPadBar window (when precision pad mode enabled while in overlay)
        if (precisionPad && !inputState.locked && activeSettingsState?.camera == null) {
            if (padBarView == null) {
                val params = overlayLayoutParams(
                    width = minOf(safeBounds.width, dpToPx(520f)),
                    height = dpToPx(168f),
                    x = 0,
                    y = outerMarginPx,
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                    focusable = false,
                )
                val compose = ComposeView(service).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    attachLifecycleOwners(this, owner)
                    setContent {
                        ScrcpyRemoteTheme(darkTheme = true) {
                            PrecisionPadBar(
                                dragging = padHandle.dragging,
                                speed = padSpeed,
                                cursorY = { padHandle.cursorY },
                                onToggleDrag = padHandle::toggleDrag,
                                onSpeedChange = {
                                    padSpeed = it
                                    PrecisionPadPreferences.saveSpeed(appContext, it)
                                },
                                onExit = {
                                    precisionPad = false
                                    syncMirrorViewProps()
                                    syncOptionalWindows()
                                },
                            )
                        }
                    }
                }
                padBarView = compose
                padBarParams = params
                wm.addView(compose, params)
            }
        } else {
            padBarView?.let { removeViewSafely(wm, it) }
            padBarView = null
            padBarParams = null
        }
    }

    private fun closePanel() {
        panelOpen = false
        syncOptionalWindows()
    }

    private fun currentSavedDevice() = devices.firstOrNull { it.id == savedDeviceId }

    private fun currentCalibration(landscape: Boolean): TouchCalibration {
        val saved = currentSavedDevice()
        val persisted = if (saved != null) {
            if (landscape) saved.landscapeCalibration else saved.portraitCalibration
        } else {
            temporaryCalibrationProvider(landscape)
        }
        return persisted ?: TouchCalibration(defaults.touchOffsetXDp, defaults.touchOffsetYDp)
    }

    private fun updateCalibration(landscape: Boolean, value: TouchCalibration?) {
        val saved = currentSavedDevice()
        if (saved != null) {
            val id = saved.id
            scope?.launch {
                val ok = repository.mutate { setCalibration(id, landscape, value) }
                if (!ok) viewModel.showNotice(appContext.getString(R.string.calibration_save_failed))
            }
        } else {
            onUpdateTemporaryCalibration(landscape, value)
        }
        syncMirrorViewProps()
    }

    private fun syncMirrorViewProps() {
        val view = mirrorView ?: return
        val connected = connectedState ?: return
        val landscape = connected.videoWidth > connected.videoHeight
        val cal = currentCalibration(landscape)
        val saved = currentSavedDevice()
        val configured = saved.effectiveSettings(defaults)

        view.setInputState(inputState)
        view.applyTouchCalibration(cal.xDp * density, cal.yDp * density)
        view.captureEdgeGestures = configured.captureEdgeGestures
        view.setVideoSize(connected.videoWidth, connected.videoHeight, connected.captureGeneration)
        view.setZoom(viewZoom)
        view.precisionPadSpeed = padSpeed
        view.precisionPad = precisionPad && activeSettingsState?.camera == null
    }

    @Composable
    private fun buildOverlayActions(): MirrorActions {
        val connected = connectedState
        val landscape = isRemoteLandscape(connected)
        val saved = currentSavedDevice()
        val configured = saved.effectiveSettings(defaults)
        val calibration = currentCalibration(landscape)
        val settings = configured.copy(touchOffsetXDp = calibration.xDp, touchOffsetYDp = calibration.yDp)
        val orientationLabel = stringResource(if (landscape) R.string.profile_landscape else R.string.profile_portrait)
        val calibrationScope = if (saved != null) {
            stringResource(R.string.profile_saved_scope, saved.name, orientationLabel)
        } else stringResource(R.string.profile_temporary_scope, orientationLabel)
        val pendingSettings = connected != null && activeSettingsState?.let {
            sessionOverridesState.applyTo(linkRequest.applyTo(configured)).requiresReconnectFrom(it)
        } == true
        val recordingWithSound = stringResource(R.string.recording_started_with_sound)
        val recordingSoundOff = stringResource(R.string.recording_started_sound_off)
        val recordingNoSound = stringResource(R.string.recording_started_no_sound)
        val hasCustomCalibration = if (saved != null) {
            (if (landscape) saved.landscapeCalibration else saved.portraitCalibration) != null
        } else {
            temporaryCalibrationProvider(landscape) != null
        }

        return rememberMirrorActions(
            viewModel = viewModel,
            settings = settings,
            deviceScreenOff = deviceScreenOff,
            onOpenApps = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.APPS)
            },
            onDisconnect = {
                pendingDisconnect = true
                closePanel()
                onExitRequested()
            },
            onExit = {
                pendingDisconnect = true
                closePanel()
                onExitRequested()
            },
            onSettingsChanged = { updated ->
                updateCalibration(landscape, TouchCalibration(updated.touchOffsetXDp, updated.touchOffsetYDp))
            },
            diagnosticsVisible = false,
            onToggleDiagnostics = {},
            audioError = audioState.error,
            audioDupRefused = audioState.dupRefused,
            calibrationScope = calibrationScope,
            calibrationOverridesDefaults = hasCustomCalibration,
            onUseGlobalCalibration = { updateCalibration(landscape, null) },
            screenshotBusy = screenshotBusy,
            onScreenshot = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.SCREENSHOT)
            },
            recording = recording,
            onConnectionDetails = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.DETAILS)
            },
            onToggleRecording = {
                if (viewModel.recording.value) {
                    viewModel.stopRecording()
                } else when (val sound = viewModel.recordingSound()) {
                    RecordingSound.WithSound ->
                        if (viewModel.startRecording(withSound = true)) viewModel.showNotice(recordingWithSound)
                    is RecordingSound.NeedsAac -> {
                        closePanel()
                        onDialogRequested(OverlayDialogRequest.RECORD_SOUND_CHOICE)
                    }
                    is RecordingSound.PictureOnly -> if (viewModel.startRecording()) {
                        viewModel.showNotice(if (sound.soundOff) recordingSoundOff else recordingNoSound)
                    }
                }
            },
            onDeviceSettings = if (saved != null) ({
                closePanel()
                onDialogRequested(OverlayDialogRequest.DEVICE_SETTINGS)
            }) else null,
            pendingSettings = pendingSettings,
            onApplySettings = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.APPLY_SETTINGS)
            },
            onSendFile = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.SEND_FILE)
            },
            sendFileBusy = transferRunning,
            onOpenEncoders = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.ENCODERS)
            },
            onOpenDisplays = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.DISPLAYS)
            },
            onOpenCameras = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.CAMERAS)
            },
            onStreamChoices = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.STREAM_CHOICES)
            },
            cameraSession = activeSettingsState?.camera != null,
            cameraTorchOn = cameraTorchOn,
            onCameraTorch = { viewModel.setCameraTorch(!cameraTorchOn) },
            onCameraZoomIn = { viewModel.zoomCamera(inward = true) },
            onCameraZoomOut = { viewModel.zoomCamera(inward = false) },
            viewZoom = viewZoom,
            onViewZoomIn = {
                viewZoom = (viewZoom * 2f).coerceAtMost(VideoLayout.MAX_ZOOM)
                syncMirrorViewProps()
            },
            onViewZoomOut = {
                viewZoom = (viewZoom / 2f).coerceAtLeast(1f)
                syncMirrorViewProps()
            },
            onViewZoomReset = {
                viewZoom = 1f
                syncMirrorViewProps()
            },
            onShareScreenshot = {
                closePanel()
                onDialogRequested(OverlayDialogRequest.SHARE_SCREENSHOT)
            },
            onCameraPip = null,
            cameraPipBlocked = false,
            inputLocked = inputState.locked,
            onInputLockedChange = { viewModel.setInputLocked(it) },
            precisionPad = precisionPad,
            onPrecisionPadChange = { next ->
                precisionPad = next
                syncMirrorViewProps()
                syncOptionalWindows()
            },
            compactRequests = compactRequests,
        )
    }

    private fun attachLifecycleOwners(view: View, owner: OverlayLifecycleOwner) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun removeViewSafely(wm: WindowManager?, view: View?) {
        if (wm == null || view == null) return
        runCatching {
            if (view.isAttachedToWindow) wm.removeViewImmediate(view)
        }
    }
}

@Composable
private fun OverlayControlPanelContent(
    actions: MirrorActions,
    muted: Boolean,
    onClosePanel: () -> Unit,
    onLocalTypingChanged: (Boolean) -> Unit,
    onPrecisionPadTurnedOff: () -> Unit,
) {
    var keyboardVisible by remember { mutableStateOf(false) }
    var calibrationVisible by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf(TextFieldValue()) }
    var liveTyping by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(keyboardVisible) {
        onLocalTypingChanged(keyboardVisible)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onLocalTypingChanged(false) }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.34f)
        Box(
            Modifier
                .fillMaxSize()
                .background(scrimColor)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        onClosePanel()
                    }
                },
        )
        Box(Modifier.padding(bottom = 12.dp)) {
            ControlPanel(
                actions = actions,
                muted = muted,
                keyboardVisible = keyboardVisible,
                calibrationVisible = calibrationVisible && !actions.cameraSession,
                textDraft = textDraft,
                onDraftChange = { textDraft = it },
                liveTyping = liveTyping,
                onLiveTypingChange = { liveTyping = it },
                onToggleKeyboard = { keyboardVisible = !keyboardVisible },
                onToggleCalibration = {
                    calibrationVisible = !calibrationVisible
                    if (calibrationVisible) onPrecisionPadTurnedOff()
                },
                onCollapse = onClosePanel,
                onCompact = onClosePanel,
                onInteraction = {},
            )
        }
    }
}

private class OverlayLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun moveToResumed() {
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/** Draws the slim pill handle above the floating mirror window. */
private class OverlayTopDragView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 28, 28, 32)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val grabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private val grabRect = RectF()

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val pillW = (width * 0.85f).coerceAtLeast(56f * d).coerceAtMost(width.toFloat())
        val pillH = 16f * d
        val left = (width - pillW) / 2f
        val top = (height - pillH) / 2f
        rect.set(left, top, left + pillW, top + pillH)
        val r = pillH / 2f
        canvas.drawRoundRect(rect, r, r, pillPaint)

        borderPaint.color = Color.argb(if (active) 108 else 46, 255, 255, 255)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        val grabW = (pillW * 0.42f).coerceIn(24f * d, 44f * d)
        val grabH = 3.5f * d
        val grabLeft = (width - grabW) / 2f
        val grabTop = (height - grabH) / 2f
        grabRect.set(grabLeft, grabTop, grabLeft + grabW, grabTop + grabH)
        grabPaint.color = Color.argb(if (active) 224 else 140, 255, 255, 255)
        canvas.drawRoundRect(grabRect, grabH / 2f, grabH / 2f, grabPaint)
    }
}

/** Draws only the hairline rounded frame border over the video without any black corner mask. */
private class OverlayBezelView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val rect = RectF()

    var cornerRadiusPx: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val half = strokePaint.strokeWidth / 2f
        rect.set(half, half, (width - half).coerceAtLeast(half), (height - half).coerceAtLeast(half))
        val r = (cornerRadiusPx - half).coerceAtLeast(0f)
        strokePaint.color = Color.argb(if (active) 110 else 62, 255, 255, 255)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }
}

/** Draws the curved corner resize indicator outside the bottom-right rounded bezel. */
private class OverlayCornerResizeView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()

    var cornerRadiusPx: Float = 24f * context.resources.displayMetrics.density
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val arcR = (cornerRadiusPx + 3f * d).coerceIn(14f * d, 34f * d)
        val overhang = 12f * d
        val cx = width - overhang - cornerRadiusPx
        val cy = height - overhang - cornerRadiusPx
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        arcPaint.color = Color.argb(if (active) 235 else 148, 255, 255, 255)
        canvas.drawArc(arcRect, 12f, 66f, false, arcPaint)
    }
}
