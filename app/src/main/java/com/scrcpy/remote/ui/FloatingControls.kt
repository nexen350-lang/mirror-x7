package com.scrcpy.remote.ui

import com.scrcpy.remote.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Everything the floating control panel can trigger on the mirrored device. */
data class MirrorActions(
    val onBack: () -> Unit,
    val onHome: () -> Unit,
    val onRecents: () -> Unit,
    val onNotifications: () -> Unit,
    val onRotate: () -> Unit,
    val onPower: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onCopyFromDevice: () -> Unit,
    val onPasteToDevice: () -> Unit,
    val onToggleMute: () -> Unit,
    /** Local queue acceptance, not a remote acknowledgement; false keeps the unsent draft. */
    val onText: (String) -> Boolean,
    /** Live keyboard: edits applied to the device as they are typed; false if any was not queued. */
    val onLiveEdits: (List<com.scrcpy.remote.session.LiveTyping.Edit>) -> Boolean = { false },
    /** A key from the keyboard panel's key row. */
    val onRemoteKey: (RemoteKey) -> Unit = {},
    /** The sound source this session runs, and those it may switch to; empty hides the switch. */
    val audioSource: com.scrcpy.remote.scrcpy.AudioSource? = null,
    val audioSources: List<com.scrcpy.remote.scrcpy.AudioSource> = emptyList(),
    val onAudioSource: (com.scrcpy.remote.scrcpy.AudioSource) -> Unit = {},
    /** Live stats line: shown, the toggle, and the line itself when there are numbers. */
    val statsChipShown: Boolean = false,
    val onToggleStatsChip: () -> Unit = {},
    val onBackspace: () -> Unit,
    val onEnter: () -> Unit,
    val onDisconnect: () -> Unit,
    /** Blanks the mirrored device's own display while the stream keeps running. */
    val onToggleDeviceScreen: () -> Unit = {},
    val deviceScreenOff: Boolean = false,
    /** Opens the device's quick settings shade. */
    val onQuickSettings: () -> Unit = {},
    /** Closes the device's notification shade or quick settings, the counterpart to opening them. */
    val onCollapsePanels: () -> Unit = {},
    /** Opens the live report of what this session is actually doing. */
    val onConnectionDetails: () -> Unit = {},
    /** Opens the device's app list so one can be launched remotely. */
    val onOpenApps: () -> Unit = {},
    /** Live touch calibration, applied and persisted immediately. */
    val touchOffsetX: Int = 0,
    val touchOffsetY: Int = 0,
    val onTouchOffsetChange: (x: Int, y: Int) -> Unit = { _, _ -> },
    val diagnosticsVisible: Boolean = false,
    val onToggleDiagnostics: () -> Unit = {},
    val audioError: String? = null,
    val onRetryAudio: () -> Unit = {},
    val calibrationScope: String? = null,
    val calibrationOverridesDefaults: Boolean = false,
    val onUseGlobalCalibration: () -> Unit = {},
    val screenshotBusy: Boolean = false,
    val onScreenshot: () -> Unit = {},
    /** Whether the session's video is currently being written to a file. */
    val recording: Boolean = false,
    val onToggleRecording: () -> Unit = {},
    val onDeviceSettings: (() -> Unit)? = null,
    val pendingSettings: Boolean = false,
    val onApplySettings: () -> Unit = {},
    /** Opens the document picker to copy a file onto the device. */
    val onSendFile: () -> Unit = {},
    val sendFileBusy: Boolean = false,
    /** Opens the device's video encoder list so a different one can be chosen. */
    val onOpenEncoders: () -> Unit = {},
    /** Opens the device's display list, for a device with more than its own screen. */
    val onOpenDisplays: () -> Unit = {},
    /**
     * Leaves the session from the compact bar.
     *
     * Separate from [onDisconnect] so it can ask first. The compact bar sits next to Back, Home
     * and Recents and is always on screen, so a mis-tap there is far likelier than on the panel
     * button, which is only reachable once the panel has been opened deliberately.
     */
    val onExit: () -> Unit = {},
    /** True while the session is showing a camera rather than the device's screen. */
    val cameraSession: Boolean = false,
    val cameraTorchOn: Boolean = false,
    val onOpenCameras: () -> Unit = {},
    val onCameraTorch: () -> Unit = {},
    val onCameraZoomIn: () -> Unit = {},
    val onCameraZoomOut: () -> Unit = {},
    /**
     * Cuts the device's selection and brings it here, rather than only copying it.
     *
     * Declared last, with the other defaulted actions: this class is built positionally in
     * places, so a new parameter in the middle silently shifts every argument after it onto the
     * wrong field — and every field here has a sibling of the same type.
     */
    val onCutFromDevice: () -> Unit = {},
    /**
     * Magnification of the local picture, and the controls that change it.
     *
     * This zooms *this* screen, not the remote device — nothing is sent for it. Declared here at
     * the end for the reason stated above: fields are appended, never inserted.
     */
    val viewZoom: Float = 1f,
    val onViewZoomIn: () -> Unit = {},
    val onViewZoomOut: () -> Unit = {},
    val onViewZoomReset: () -> Unit = {},
    /** Captures and hands the shot straight to another app, rather than to the picker. */
    val onShareScreenshot: () -> Unit = {},
    /** Only supplied for a live camera on a device that supports native PiP. */
    val onCameraPip: (() -> Unit)? = null,
    val cameraPipBlocked: Boolean = false,
    val inputLocked: Boolean = false,
    val onInputLockedChange: (Boolean) -> Unit = {},
    /** Precision pad mode for this session, and the switch for it; never offered for a camera. */
    val precisionPad: Boolean = false,
    val onPrecisionPadChange: (Boolean) -> Unit = {},
    /**
     * Raised by one each time the full panel should drop back to the compact bar, such as after an
     * app is opened from a dialog over it: what happens next is on the device's screen.
     */
    val compactRequests: Int = 0,
    /** The device refused to send sound it keeps playing; [onHearSoundHere] then replaces a retry. */
    val audioDupRefused: Boolean = false,
    val onHearSoundHere: () -> Unit = {},
    /** Opens Picture & sound: this session's codecs and server, applied with one reconnect. */
    val onStreamChoices: () -> Unit = {},
)

/** Screen mirroring: back, home, recents, exit, more. Cameras only have exit and more. */
private const val COMPACT_BUTTON_COUNT = 5
private const val CAMERA_COMPACT_BUTTON_COUNT = 2

/** Stop recording joins them while a recording runs, and nothing else ever does. */
private const val COMPACT_BUTTON_COUNT_MAX = 6
private val COMPACT_BUTTON_SIZE = 48.dp
private val COMPACT_BAR_PADDING = 4.dp

/**
 * Space the compact bar needs along its length, for a given number of buttons.
 *
 * Taken from the count actually being drawn rather than from a constant. A fixed length sized for
 * five left the sixth button laid out past the edge of a `requiredSize` box while recording, where
 * it was not merely clipped but undisplayable.
 */
internal fun compactBarLength(buttonCount: Int) =
    COMPACT_BUTTON_SIZE * buttonCount + COMPACT_BAR_PADDING * 2

/**
 * Space the compact bar needs along its length and across it.
 *
 * Shared with the docking search so it can never reserve a letterbox that fits a different number
 * of buttons than the bar actually draws.
 */
internal val compactBarLongSide = compactBarLength(COMPACT_BUTTON_COUNT)

/** The most the bar can ever need, so a docked letterbox does not shrink under a recording. */
internal val compactBarLongSideMax = compactBarLength(COMPACT_BUTTON_COUNT_MAX)
internal val compactBarShortSide = COMPACT_BUTTON_SIZE + COMPACT_BAR_PADDING * 2
internal val ATTACHED_BAR_GAP = 8.dp

internal fun attachedCompactButtonCount(cameraSession: Boolean, recording: Boolean): Int =
    (if (cameraSession) CAMERA_COMPACT_BUTTON_COUNT else COMPACT_BUTTON_COUNT) + (if (recording) 1 else 0)

/** Columns used when the window is too small for a single row or column. */
private const val COMPACT_GRID_COLUMNS = 3

/**
 * Floating, draggable "dots" handle that shows and hides the control panel.
 *
 * Keeping the controls in an overlay lets the mirrored video surface use the entire display.
 */
@Composable
fun FloatingControlsOverlay(
    actions: MirrorActions,
    muted: Boolean,
    modifier: Modifier = Modifier,
    videoLayout: VideoLayout? = null,
    onLocalTypingChanged: (Boolean) -> Unit = {},
    inputLockBanner: InputLockBannerPlacement? = null,
    onPanelModalChanged: (Boolean) -> Unit = {},
    attachedWindow: ControlBounds? = null,
) {
    ScrcpyRemoteTheme(darkTheme = true) {
        FloatingControlsContent(actions, muted, modifier, videoLayout, onLocalTypingChanged, inputLockBanner,
            onPanelModalChanged, attachedWindow)
    }
}

@Composable
private fun FloatingControlsContent(
    actions: MirrorActions,
    muted: Boolean,
    modifier: Modifier,
    videoLayout: VideoLayout?,
    onLocalTypingChanged: (Boolean) -> Unit,
    inputLockBanner: InputLockBannerPlacement?,
    onPanelModalChanged: (Boolean) -> Unit,
    attachedWindow: ControlBounds?,
) {
    BoxWithConstraints(modifier, contentAlignment = AbsoluteAlignment.TopLeft) {
        val density = LocalDensity.current
        val handleSizePx = with(density) { HANDLE_SIZE.toPx() }
        val marginPx = with(density) { HANDLE_MARGIN.toPx() }
        val layoutDirection = LocalLayoutDirection.current
        val safeInsets = WindowInsets.safeDrawing.union(WindowInsets.systemGestures)
        // The handle's range ignores the IME, so opening and closing it never moves the handle.
        val handleInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.systemGestures)
        val maxX = (constraints.maxWidth - handleInsets.getRight(density, layoutDirection) -
            handleSizePx - marginPx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - handleInsets.getBottom(density) -
            handleSizePx - marginPx).coerceAtLeast(0f)
        val minX = (handleInsets.getLeft(density, layoutDirection) + marginPx).coerceAtMost(maxX)
        val minY = (handleInsets.getTop(density) + marginPx).coerceAtMost(maxY)

        val hasAttachedWindow = attachedWindow != null
        var positionX by rememberSaveable { mutableFloatStateOf(1f) }
        var positionY by rememberSaveable { mutableFloatStateOf(0.5f) }
        var expanded by rememberSaveable(hasAttachedWindow) { mutableStateOf(hasAttachedWindow) }
        var toolsExpanded by rememberSaveable { mutableStateOf(false) }
        var keyboardVisible by rememberSaveable { mutableStateOf(false) }
        // The draft field only exists while this is true and takes focus as soon as it appears,
        // so it is the authority on whether typing is local. A physical keyboard must compose the
        // draft rather than also being injected into the device.
        LaunchedEffect(keyboardVisible, actions.inputLocked) { onLocalTypingChanged(keyboardVisible && !actions.inputLocked) }
        DisposableEffect(Unit) { onDispose { onLocalTypingChanged(false) } }
        var calibrationVisible by rememberSaveable { mutableStateOf(false) }
        // Typed text can be a password: kept while this panel lives, so closing and reopening the
        // keyboard keeps it, but never in the saved-state Bundle. The mode is not sensitive and
        // is kept with it, so a Draft stays a Draft when the keyboard reopens.
        var textDraft by remember { mutableStateOf(TextFieldValue()) }
        var liveTyping by rememberSaveable { mutableStateOf(true) }
        var interactionCount by remember { mutableIntStateOf(0) }
        var idle by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        // pointerInput restarts (bounds change mid-drag) cancel without onDragCancel.
        DisposableEffect(minX, minY, maxX, maxY) { onDispose { dragging = false } }
        val handleInteractions = remember { MutableInteractionSource() }
        val pressed by handleInteractions.collectIsPressedAsState()
        val keyboardController = LocalSoftwareKeyboardController.current

        fun resolvedX(): Float = minX + positionX * (maxX - minX)
        fun resolvedY(): Float = minY + positionY * (maxY - minY)
        val attachedSafeInsets = WindowInsets.safeDrawing
        val docking = if (attachedWindow != null) {
            val buttonCount = attachedCompactButtonCount(actions.cameraSession, actions.recording)
            val attachedSafe = ControlBounds(
                attachedSafeInsets.getLeft(density, layoutDirection) + marginPx.roundToInt(),
                attachedSafeInsets.getTop(density) + marginPx.roundToInt(),
                constraints.maxWidth - attachedSafeInsets.getRight(density, layoutDirection) - marginPx.roundToInt(),
                constraints.maxHeight - attachedSafeInsets.getBottom(density) - marginPx.roundToInt(),
            )
            attachCompactNavigationToWindow(
                window = attachedWindow,
                safe = attachedSafe,
                barWidth = with(density) { compactBarShortSide.roundToPx() },
                barHeight = with(density) { compactBarLength(buttonCount).roundToPx() },
                gap = with(density) { ATTACHED_BAR_GAP.roundToPx() },
            )
        } else {
            dockCompactNavigation(
                content = videoLayout,
                viewWidth = constraints.maxWidth,
                viewHeight = constraints.maxHeight,
                safe = ControlBounds(
                    safeInsets.getLeft(density, layoutDirection) + marginPx.roundToInt(),
                    safeInsets.getTop(density) + marginPx.roundToInt(),
                    constraints.maxWidth - safeInsets.getRight(density, layoutDirection) - marginPx.roundToInt(),
                    constraints.maxHeight - safeInsets.getBottom(density) - marginPx.roundToInt(),
                ),
                longSide = with(density) {
                    (if (actions.cameraSession) compactBarLength(CAMERA_COMPACT_BUTTON_COUNT + 1)
                        else compactBarLongSideMax).roundToPx()
                },
                shortSide = with(density) { compactBarShortSide.roundToPx() },
                gap = marginPx.roundToInt(),
                handle = ControlBounds(resolvedX().roundToInt(), resolvedY().roundToInt(),
                    (resolvedX() + handleSizePx).roundToInt(), (resolvedY() + handleSizePx).roundToInt()),
            )
        }
        fun compact() {
            toolsExpanded = false
            keyboardVisible = false
            calibrationVisible = false
            keyboardController?.hide()
        }
        fun collapse() {
            expanded = false
            compact()
        }
        LaunchedEffect(actions.cameraSession) {
            if (actions.cameraSession) {
                keyboardVisible = false
                calibrationVisible = false
            }
        }
        // Answered once each: a panel opened after the request is the user's to close.
        var answeredCompact by remember { mutableIntStateOf(actions.compactRequests) }
        LaunchedEffect(actions.compactRequests) {
            if (actions.compactRequests != answeredCompact) {
                answeredCompact = actions.compactRequests
                compact()
            }
        }
        LaunchedEffect(actions.inputLocked, hasAttachedWindow) {
            if (actions.inputLocked) {
                if (hasAttachedWindow) compact() else collapse()
            }
        }
        BackHandler(enabled = if (hasAttachedWindow) toolsExpanded else expanded) {
            if (hasAttachedWindow) compact() else collapse()
        }

        // A cancelled click must not end an active drag's idle suppression.
        LaunchedEffect(interactionCount, expanded, dragging, pressed) {
            idle = false
            if (!expanded && !dragging && !pressed) {
                delay(IDLE_DELAY_MS)
                idle = true
            }
        }
        val handleAlpha by animateFloatAsState(
            // Fades to recede over the video, but not so far that it stops being legible. The
            // handle is the only way back to the controls, and it is drawn over arbitrary video,
            // so at a low alpha its contrast is whatever the frame underneath happens to be.
            targetValue = if (idle) 0.7f else 1f,
            label = "handleAlpha",
        )

        val handleLabel = stringResource(R.string.handle_description)
        val handleState = stringResource(if (expanded) R.string.handle_state_expanded else R.string.handle_state_collapsed)
        val handleClickLabel = stringResource(if (expanded) R.string.handle_action_close else R.string.handle_action_open)

        // The full panel is a menu. While it is open, a touch anywhere around it minimizes it to
        // the compact bar instead of reaching the device, and the screen dims to say so. Under
        // the handle and the panel, so both stay usable, and the lock banner and notices stay on
        // top. Calibration is the exception: it is for trying touches on the screen while the
        // panel shows the offsets. A camera takes no touches, so it has nothing to calibrate.
        val calibrating = calibrationVisible && !actions.cameraSession
        val panelModal = expanded && toolsExpanded && !calibrating
        val panelModalChanged by rememberUpdatedState(onPanelModalChanged)
        LaunchedEffect(panelModal) { panelModalChanged(panelModal) }
        DisposableEffect(Unit) { onDispose { panelModalChanged(false) } }
        val scrimAlpha by animateFloatAsState(if (panelModal) 1f else 0f, label = "panelScrim")
        if (panelModal || scrimAlpha > 0f) {
            val scrim = MaterialTheme.colorScheme.scrim
            Box(
                Modifier
                    .matchParentSize()
                    .testTag("panel-scrim")
                    .drawBehind { drawRect(scrim, alpha = PANEL_SCRIM_ALPHA * scrimAlpha) }
                    .then(if (panelModal) Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            // The whole gesture stays behind the barrier, extra fingers included:
                            // the device never saw it begin. Minimized only once the last finger
                            // lifts, so the compact bar's keys cannot appear under one still down.
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            compact()
                            interactionCount++
                        }
                    } else Modifier),
            )
        }
        Box(
            modifier = Modifier
                .absoluteOffset { IntOffset(resolvedX().roundToInt(), resolvedY().roundToInt()) }
                .size(HANDLE_SIZE)
                .alpha(handleAlpha)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .semantics { contentDescription = handleLabel; stateDescription = handleState }
                .pointerInput(minX, minY, maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false; interactionCount++ },
                        onDragCancel = { dragging = false; interactionCount++ },
                    ) { change, dragAmount ->
                        change.consume()
                        positionX = normalizedHandlePosition(resolvedX() + dragAmount.x, minX, maxX)
                        positionY = normalizedHandlePosition(resolvedY() + dragAmount.y, minY, maxY)
                    }
                }
                .clickable(
                    interactionSource = handleInteractions,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = handleClickLabel,
                ) {
                    if (expanded) collapse() else expanded = true
                    interactionCount++
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        // Draw above the handle so a saved handle position can never obscure Exit or Hide.
        val controlsVisible = expanded && !(hasAttachedWindow && actions.inputLocked)
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = if (toolsExpanded || docking == null) Modifier.align(Alignment.BottomCenter)
                else Modifier.absoluteOffset { IntOffset(docking.bounds.left, docking.bounds.top) },
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            if (toolsExpanded) ControlPanel(
                actions = actions,
                muted = muted,
                keyboardVisible = keyboardVisible,
                calibrationVisible = calibrating,
                textDraft = textDraft,
                onDraftChange = { textDraft = it },
                liveTyping = liveTyping,
                onLiveTypingChange = { liveTyping = it },
                onToggleKeyboard = {
                    keyboardVisible = !keyboardVisible
                    if (!keyboardVisible) keyboardController?.hide()
                    interactionCount++
                },
                onToggleCalibration = {
                    calibrationVisible = !calibrationVisible
                    // Offsets apply to direct touch; through the pad every try would ignore them.
                    if (calibrationVisible && actions.precisionPad) actions.onPrecisionPadChange(false)
                },
                onCollapse = ::collapse,
                onCompact = ::compact,
                onInteraction = { interactionCount++ },
            )
            else CompactNavigationBar(
                actions = actions,
                onMore = { toolsExpanded = true; interactionCount++ },
                onInteraction = { interactionCount++ },
                docking = docking,
            )
        }
        if (actions.inputLocked) {
            InputLockBanner(onUnlock = { actions.onInputLockedChange(false) },
                modifier = Modifier.align(Alignment.TopCenter), placement = inputLockBanner)
        }
    }
}

@Composable
private fun CompactNavigationBar(
    actions: MirrorActions,
    onMore: () -> Unit,
    onInteraction: () -> Unit,
    docking: CompactNavigationPlacement?,
) {
    val notice = when {
        actions.audioError != null -> stringResource(R.string.compact_audio_notice)
        actions.pendingSettings -> stringResource(R.string.compact_settings_notice)
        else -> null
    }
    val buttons = buildList {
        if (!actions.cameraSession) {
            add(CompactNavigationAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.control_back),
                { onInteraction(); actions.onBack() }, enabled = !actions.inputLocked))
            add(CompactNavigationAction(Icons.Default.Circle, stringResource(R.string.control_home),
                { onInteraction(); actions.onHome() }, enabled = !actions.inputLocked))
            add(CompactNavigationAction(Icons.Default.Square, stringResource(R.string.control_recents),
                { onInteraction(); actions.onRecents() }, enabled = !actions.inputLocked))
        }
        // Only while it is running. Stopping a recording is the one action that is urgent — it
        // decides where the video ends — and it otherwise sat three levels down, behind the
        // handle, More, and a scroll. The bar stays as short as it was the rest of the time.
        if (actions.recording) {
            add(CompactNavigationAction(Icons.Default.StopCircle,
                stringResource(R.string.recording_action_stop),
                { onInteraction(); actions.onToggleRecording() }))
        }
        add(CompactNavigationAction(Icons.Default.Close, stringResource(R.string.control_exit),
            { onInteraction(); actions.onExit() }))
        add(CompactNavigationAction(Icons.Default.MoreHoriz, stringResource(R.string.compact_more_controls), onMore, notice))
    }
    BoxWithConstraints(
        if (docking == null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemGestures))
            .imePadding().padding(8.dp) else Modifier,
    ) {
        val longSide = compactBarLength(buttons.size)
        val horizontal = docking?.let { !it.vertical } ?: (maxWidth >= longSide)
        val vertical = docking?.vertical ?: (!horizontal && maxHeight >= longSide)
        val shape = if (horizontal || vertical) CircleShape else MaterialTheme.shapes.medium
        // Required, not preferred: a window too small for the chosen layout must overflow rather
        // than squeeze the buttons below the 48 dp minimum touch target.
        val size = when {
            horizontal -> DpSize(longSide, compactBarShortSide)
            vertical -> DpSize(compactBarShortSide, longSide)
            else -> DpSize(
                COMPACT_BUTTON_SIZE * COMPACT_GRID_COLUMNS + COMPACT_BAR_PADDING * 2,
                COMPACT_BUTTON_SIZE * 2 + COMPACT_BAR_PADDING * 2,
            )
        }
        Box(
            Modifier.requiredSize(size).shadow(4.dp, shape).clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .testTag("compact-navigation"),
        ) {
            Box(
                Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .controlBackgroundInput(),
            )
            when {
                horizontal -> Row(Modifier.padding(COMPACT_BAR_PADDING), verticalAlignment = Alignment.CenterVertically) {
                    buttons.forEach { CompactNavigationButton(it) }
                }
                vertical -> Column(Modifier.padding(COMPACT_BAR_PADDING), horizontalAlignment = Alignment.CenterHorizontally) {
                    buttons.forEach { CompactNavigationButton(it) }
                }
                else -> Column(Modifier.padding(COMPACT_BAR_PADDING)) {
                    Row { buttons.take(COMPACT_GRID_COLUMNS).forEach { CompactNavigationButton(it) } }
                    Row { buttons.drop(COMPACT_GRID_COLUMNS).forEach { CompactNavigationButton(it) } }
                }
            }
        }
    }
}

private data class CompactNavigationAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val notice: String? = null,
    val enabled: Boolean = true,
)

@Composable
private fun CompactNavigationButton(action: CompactNavigationAction) {
    IconButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.size(48.dp).semantics {
        if (action.notice != null) stateDescription = action.notice
    }) {
        Box {
            Icon(action.icon, action.label, Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.enabled) 1f else 0.38f))
            if (action.notice != null) Box(
                Modifier.align(Alignment.TopEnd).size(6.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
    }
}

/**
 * Where the persistent Input Lock banner ends, so the session's transient notices can sit below it
 * instead of over it. Zero while no banner is shown.
 */
@Stable
class InputLockBannerPlacement {
    /** Bottom edge, in root coordinates. */
    var bottom by mutableFloatStateOf(0f)
        internal set
}

@Composable
internal fun InputLockBanner(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
    placement: InputLockBannerPlacement? = null,
) {
    if (placement != null) DisposableEffect(placement) { onDispose { placement.bottom = 0f } }
    val locked = stringResource(R.string.input_locked)
    Button(
        onClick = onUnlock,
        modifier = modifier
            .onGloballyPositioned { placement?.bottom = it.boundsInRoot().bottom }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp).widthIn(max = 360.dp).heightIn(min = 48.dp)
            .testTag("input-lock-banner").semantics { stateDescription = locked },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(locked, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.input_unlock_action), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The session's transient notices. While input is locked they sit below the persistent Unlock
 * banner: drawn over it, a long notice hid the one control that restores input, and blocked taps
 * on it, for as long as the notice was shown.
 */
@Composable
internal fun SessionSnackbarHost(
    state: SnackbarHostState,
    inputLocked: Boolean,
    lockBanner: InputLockBannerPlacement,
    modifier: Modifier = Modifier,
) {
    var parentTop by remember { mutableFloatStateOf(0f) }
    SnackbarHost(
        state,
        modifier
            .onPlaced { parentTop = it.parentLayoutCoordinates?.positionInRoot()?.y ?: 0f }
            .offset {
                val below = if (inputLocked) (lockBanner.bottom - parentTop).roundToInt() else 0
                IntOffset(0, below.coerceAtLeast(0))
            }
            .testTag("session-notices"),
    )
}

/** Only the painted controls block input; the remaining video stays interactive. */
private fun Modifier.controlBackgroundInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPanel(
    actions: MirrorActions,
    muted: Boolean,
    keyboardVisible: Boolean,
    calibrationVisible: Boolean,
    textDraft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    liveTyping: Boolean,
    onLiveTypingChange: (Boolean) -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleCalibration: () -> Unit,
    onCollapse: () -> Unit,
    onCompact: () -> Unit,
    onInteraction: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            // Safe drawing handles bars/cutouts; back-gesture side insets can starve a narrow panel.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = minOf(PANEL_MAX_HEIGHT, if (maxHeight < 400.dp) maxHeight else maxHeight * 0.8f))
                .clip(MaterialTheme.shapes.large)
                .testTag("expanded-controls")
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        ) {
            // Only the painted panel consumes background input, never its surrounding insets.
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .controlBackgroundInput(),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                // With the IME up the panel is only ~140dp tall: the compact bar has these keys, so
                // the scrolling body keeps the room instead.
                val imeVisible = WindowInsets.isImeVisible
                if (!actions.cameraSession && !imeVisible) {
                    Row(
                        Modifier.fillMaxWidth().testTag("expanded-navigation"),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactNavigationButton(CompactNavigationAction(Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.control_back),
                            { onInteraction(); actions.onBack() }, enabled = !actions.inputLocked))
                        CompactNavigationButton(CompactNavigationAction(Icons.Default.Circle,
                            stringResource(R.string.control_home),
                            { onInteraction(); actions.onHome() }, enabled = !actions.inputLocked))
                        CompactNavigationButton(CompactNavigationAction(Icons.Default.Square,
                            stringResource(R.string.control_recents),
                            { onInteraction(); actions.onRecents() }, enabled = !actions.inputLocked))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(Modifier.weight(1f, fill = false).testTag("expanded-controls-body")
                    .verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    // What needs attention comes first. The compact bar's More button carries a
                    // badge for these, so they are what the panel has to open on, not something
                    // found by scrolling to the end.
                    if (actions.pendingSettings) {
                        PanelNotice(
                            message = stringResource(R.string.session_settings_pending),
                            action = stringResource(R.string.session_apply_settings),
                            onAction = { onInteraction(); actions.onApplySettings() },
                        )
                    }
                    actions.audioError?.let { warning ->
                        if (actions.audioDupRefused) {
                            // A retry would ask for the capture the device has just refused.
                            PanelNotice(
                                message = warning,
                                detail = stringResource(R.string.audio_dup_refused_help),
                                action = stringResource(R.string.audio_hear_here),
                                onAction = { onInteraction(); actions.onHearSoundHere() },
                                error = true,
                            )
                        } else {
                            PanelNotice(
                                message = warning,
                                detail = stringResource(R.string.ui_retry_audio_help),
                                action = stringResource(R.string.ui_retry_audio),
                                onAction = { onInteraction(); actions.onRetryAudio() },
                                error = true,
                            )
                        }
                    }

                    // Ordered by use: the device's own buttons, then input, capture, this screen's
                    // view, and the session's settings and information last. A camera has no
                    // screen to press or type into, so its own controls replace the first two.
                    if (actions.cameraSession) {
                        CameraControls(actions, muted, onInteraction)
                    } else {
                        DeviceControls(actions, muted, onInteraction)
                        InputControls(actions, keyboardVisible, onToggleKeyboard, onCollapse, onInteraction)
                        if (keyboardVisible && !actions.inputLocked) {
                            DeviceTextField(
                                value = textDraft,
                                onValueChange = onDraftChange,
                                live = liveTyping,
                                onLiveChange = onLiveTypingChange,
                                onText = actions.onText,
                                onLiveEdits = actions.onLiveEdits,
                                onKey = actions.onRemoteKey,
                                onBackspace = actions.onBackspace,
                                onEnter = actions.onEnter,
                                onInteraction = onInteraction,
                            )
                        }
                    }
                    CaptureControls(actions, onInteraction)
                    ViewControls(actions, calibrationVisible, onToggleCalibration, onInteraction)
                    if (calibrationVisible) {
                        CalibrationControls(actions = actions, onInteraction = onInteraction)
                    }
                    SessionControls(actions, onInteraction)
                    Spacer(Modifier.height(8.dp))
                }

                // Measured before the weighted scrolling body, even when the IME shrinks it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCompact, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.KeyboardDoubleArrowDown, stringResource(R.string.compact_navigation_only))
                    }
                    // Neutral, like the tiles: hiding the controls is not this panel's main action,
                    // and a coloured fill made it the brightest thing on the panel.
                    FilledTonalButton(
                        onClick = onCollapse,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(stringResource(R.string.hide)) }
                    OutlinedButton(
                        onClick = actions.onDisconnect,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    ) { Text(stringResource(R.string.exit)) }
                }
            }
        }
    }
}

/**
 * A heading and its tiles, on a grid of whole columns spanning the panel from under the heading.
 *
 * Tiles used to keep a fixed width and centre each row, so a short last row floated in the middle
 * while its heading sat at the edge, and full rows stopped short of either side.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelSection(title: String, content: @Composable FlowRowScope.() -> Unit) {
    PanelSectionLabel(title)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val tile = remember(maxWidth, density) { panelTileWidth(maxWidth, density) }
        CompositionLocalProvider(LocalPanelTileWidth provides tile) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PANEL_TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(PANEL_TILE_GAP),
                content = content,
            )
        }
    }
}

/**
 * The widest tile that fits whole columns, each at least [PANEL_TILE_MIN_WIDTH], across [available].
 * Rounded down to a whole pixel, so a full row never spills its last tile onto the next line.
 *
 * The gap is planned rounded up, as FlowRow measures it when it decides where a row ends, though it
 * then places tiles with the gap rounded to the nearest pixel. Where 8 dp is not a whole pixel and
 * rounds down, as at 2.55 where it is 20.4 px, a row planned on the nearest pixel came out a pixel
 * per gap too wide for FlowRow, which moved its last tile down and left an empty column.
 */
internal fun panelTileWidth(available: Dp, density: Density): Dp = with(density) {
    val availablePx = available.toPx()
    val gapPx = ceil(PANEL_TILE_GAP.toPx())
    val columns = maxOf(1, floor((availablePx + gapPx) / (panelTileMinWidth(fontScale).toPx() + gapPx)).toInt())
    floor((availablePx - gapPx * (columns - 1)) / columns).toDp()
}

/**
 * The narrowest a tile may be: [PANEL_TILE_MIN_WIDTH] up to a 1.3 font scale, then growing with the
 * text (capped) so a two-line label such as "Notifications" is not clipped at large scales.
 */
internal fun panelTileMinWidth(fontScale: Float): Dp =
    if (fontScale <= 1.3f) PANEL_TILE_MIN_WIDTH
    else minOf(PANEL_TILE_MIN_WIDTH * (fontScale / 1.3f), PANEL_TILE_MAX_WIDTH)

/** The width of a panel tile: set by its section's grid, and the minimum anywhere else. */
private val LocalPanelTileWidth = compositionLocalOf { PANEL_TILE_MIN_WIDTH }

/** A [PanelButton] in a section, as tall as the tallest tile in its row so the row lines up. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.PanelTile(
    icon: ImageVector,
    description: String,
    label: String = description,
    active: Boolean = false,
    enabled: Boolean = true,
    state: String? = null,
    onClick: () -> Unit,
) = PanelButton(icon, description, Modifier.fillMaxRowHeight(), label, active, enabled, state, onClick)

/** Something that needs the user, with the action that settles it, and at most one alternative. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelNotice(
    message: String,
    action: String,
    onAction: () -> Unit,
    detail: String? = null,
    error: Boolean = false,
    /** A second way out, shown before [action], which stays the last and main one. */
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Text(message, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
            detail?.let { Text(it, Modifier.padding(end = 8.dp, top = 2.dp), style = MaterialTheme.typography.bodySmall) }
            // Wraps rather than squeezing two labels into one line on a narrow panel or large text.
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.End) {
                secondaryAction?.let { label ->
                    TextButton(
                        onClick = onSecondaryAction,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                    ) { Text(label) }
                }
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) { Text(action) }
            }
        }
    }
}

/** Locks or unlocks remote input. Offered while locked too: it is one way back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.InputLockTile(actions: MirrorActions, onInteraction: () -> Unit) {
    val locked = actions.inputLocked
    PanelTile(
        icon = if (locked) Icons.Default.LockOpen else Icons.Default.Lock,
        description = stringResource(if (locked) R.string.input_unlock_action else R.string.input_lock_action),
        label = stringResource(if (locked) R.string.input_unlock_label else R.string.input_lock_label),
        active = locked,
        state = if (locked) stringResource(R.string.input_locked) else null,
    ) { onInteraction(); actions.onInputLockedChange(!locked) }
}

/** Plays or mutes the device's sound on this phone. Nothing is sent for it, so it ignores the lock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.AudioTile(actions: MirrorActions, muted: Boolean, onInteraction: () -> Unit) {
    PanelTile(
        // Headphones rather than a speaker: the volume buttons beside it already show speakers.
        icon = if (muted) Icons.Default.HeadsetOff else Icons.Default.Headphones,
        description = stringResource(R.string.control_toggle_audio),
        label = stringResource(if (muted) R.string.release_audio_muted else R.string.release_control_audio),
        active = muted,
        state = stringResource(if (muted) R.string.toggle_state_on else R.string.toggle_state_off),
    ) { onInteraction(); actions.onToggleMute() }
}

/**
 * Switches where the device's sound comes from, for this session, without leaving the mirror.
 * The session reconnects with the new source; the saved device keeps its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.AudioSourceTile(actions: MirrorActions, onInteraction: () -> Unit) {
    val current = actions.audioSource ?: return
    if (actions.audioSources.size < 2) return
    var open by remember { mutableStateOf(false) }
    PanelTile(
        icon = Icons.Default.Mic,
        description = stringResource(R.string.control_audio_source, stringResource(current.labelRes)),
        label = stringResource(R.string.release_control_audio_source),
    ) { onInteraction(); open = true }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.setting_audio_source)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(actions.audioSources.size) { index ->
                        val source = actions.audioSources[index]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.RadioButton) {
                                    open = false
                                    if (source != current) actions.onAudioSource(source)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = source == current, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(stringResource(source.labelRes))
                                source.detailRes?.let {
                                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { open = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** The device's own buttons and panels: what a remote control is used for most. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceControls(actions: MirrorActions, muted: Boolean, onInteraction: () -> Unit) {
    val enabled = !actions.inputLocked
    PanelSection(stringResource(R.string.release_controls_device)) {
        PanelTile(Icons.AutoMirrored.Filled.VolumeDown, stringResource(R.string.control_volume_down),
            label = stringResource(R.string.release_control_less), enabled = enabled) {
            onInteraction(); actions.onVolumeDown()
        }
        PanelTile(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.control_volume_up),
            label = stringResource(R.string.release_control_more), enabled = enabled) {
            onInteraction(); actions.onVolumeUp()
        }
        AudioTile(actions, muted, onInteraction)
        AudioSourceTile(actions, onInteraction)
        PanelTile(Icons.Default.Speed, stringResource(R.string.control_stats_chip),
            label = stringResource(R.string.release_control_stats), active = actions.statsChipShown,
            state = stringResource(if (actions.statsChipShown) R.string.toggle_state_on else R.string.toggle_state_off)) {
            onInteraction(); actions.onToggleStatsChip()
        }
        PanelTile(Icons.Default.PowerSettingsNew, stringResource(R.string.control_power), enabled = enabled) {
            onInteraction(); actions.onPower()
        }
        PanelTile(Icons.Default.Notifications, stringResource(R.string.control_notifications),
            label = stringResource(R.string.release_control_notifications), enabled = enabled) {
            onInteraction(); actions.onNotifications()
        }
        PanelTile(Icons.Default.Tune, stringResource(R.string.control_quick_settings),
            label = stringResource(R.string.release_control_quick_settings), enabled = enabled) {
            onInteraction(); actions.onQuickSettings()
        }
        PanelTile(Icons.Default.UnfoldLess, stringResource(R.string.control_collapse_panels),
            label = stringResource(R.string.release_control_collapse_panels), enabled = enabled) {
            onInteraction(); actions.onCollapsePanels()
        }
        PanelTile(Icons.Default.Apps, stringResource(R.string.control_apps),
            label = stringResource(R.string.release_control_apps), enabled = enabled) {
            onInteraction(); actions.onOpenApps()
        }
        PanelTile(Icons.Default.ScreenRotation, stringResource(R.string.control_rotate), enabled = enabled) {
            onInteraction(); actions.onRotate()
        }
        PanelTile(
            icon = if (actions.deviceScreenOff) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            description = stringResource(
                if (actions.deviceScreenOff) R.string.control_device_screen_on
                else R.string.control_device_screen_off),
            label = stringResource(
                if (actions.deviceScreenOff) R.string.release_control_screen_on
                else R.string.release_control_screen_off),
            active = actions.deviceScreenOff,
            enabled = enabled,
            // The label names the action ("Screen on"); the state is the opposite.
            state = if (actions.deviceScreenOff) stringResource(R.string.release_control_screen_off) else null,
        ) { onInteraction(); actions.onToggleDeviceScreen() }
    }
}

/**
 * Typing, the clipboard and how touches are made: all of it reaches the device as injected
 * input, so the lock that stops it sits with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputControls(
    actions: MirrorActions,
    keyboardVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onCollapse: () -> Unit,
    onInteraction: () -> Unit,
) {
    val enabled = !actions.inputLocked
    PanelSection(stringResource(R.string.panel_section_input)) {
        PanelTile(Icons.Default.Keyboard, stringResource(R.string.control_keyboard), active = keyboardVisible,
            state = stringResource(if (keyboardVisible) R.string.toggle_state_on else R.string.toggle_state_off),
            enabled = enabled, onClick = onToggleKeyboard)
        PanelTile(Icons.Default.ContentPaste, stringResource(R.string.control_paste),
            label = stringResource(R.string.release_control_paste), enabled = enabled) {
            onInteraction(); actions.onPasteToDevice()
        }
        PanelTile(Icons.Default.ContentCopy, stringResource(R.string.control_copy),
            label = stringResource(R.string.release_control_copy), enabled = enabled) {
            onInteraction(); actions.onCopyFromDevice()
        }
        PanelTile(Icons.Default.ContentCut, stringResource(R.string.control_cut),
            label = stringResource(R.string.release_control_cut), enabled = enabled) {
            onInteraction(); actions.onCutFromDevice()
        }
        // Points at small targets without covering them with a finger.
        PanelTile(
            icon = Icons.Default.Mouse,
            description = stringResource(R.string.precision_pad_toggle),
            label = stringResource(R.string.precision_pad_toggle_label),
            active = actions.precisionPad,
            enabled = enabled,
            state = stringResource(if (actions.precisionPad) R.string.precision_pad_state_on
                else R.string.precision_pad_state_off),
        ) {
            onInteraction()
            val enable = !actions.precisionPad
            actions.onPrecisionPadChange(enable)
            // Straight to the pad: the open panel would cover the cursor.
            if (enable) onCollapse()
        }
        InputLockTile(actions, onInteraction)
    }
}

/** A camera's own controls in place of the device's, which a lens does not have. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraControls(actions: MirrorActions, muted: Boolean, onInteraction: () -> Unit) {
    val enabled = !actions.inputLocked
    PanelSection(stringResource(R.string.cameras_title)) {
        PanelTile(
            icon = if (actions.cameraTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
            description = stringResource(if (actions.cameraTorchOn) R.string.camera_torch_off else R.string.camera_torch_on),
            label = stringResource(R.string.release_camera_light),
            active = actions.cameraTorchOn,
            state = stringResource(if (actions.cameraTorchOn) R.string.toggle_state_on else R.string.toggle_state_off),
            enabled = enabled,
        ) { onInteraction(); actions.onCameraTorch() }
        PanelTile(Icons.Default.ZoomIn, stringResource(R.string.camera_zoom_in),
            label = stringResource(R.string.release_camera_in), enabled = enabled) {
            onInteraction(); actions.onCameraZoomIn()
        }
        PanelTile(Icons.Default.ZoomOut, stringResource(R.string.camera_zoom_out),
            label = stringResource(R.string.release_camera_out), enabled = enabled) {
            onInteraction(); actions.onCameraZoomOut()
        }
        actions.onCameraPip?.let { enter ->
            PanelTile(
                icon = Icons.Default.PictureInPictureAlt,
                description = stringResource(R.string.camera_pip_action),
                label = stringResource(R.string.camera_pip_label),
                enabled = !actions.cameraPipBlocked,
            ) { onInteraction(); enter() }
        }
        AudioTile(actions, muted, onInteraction)
        InputLockTile(actions, onInteraction)
    }
    if (actions.onCameraPip != null && actions.cameraPipBlocked) {
        Text(
            stringResource(R.string.camera_pip_busy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Screenshots, recording and files: these keep working while input is locked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureControls(actions: MirrorActions, onInteraction: () -> Unit) {
    PanelSection(stringResource(R.string.panel_section_capture)) {
        PanelTile(
            icon = Icons.Default.PhotoCamera,
            description = stringResource(if (actions.screenshotBusy) R.string.capture_busy else R.string.capture_screenshot),
            label = stringResource(R.string.release_control_screenshot),
            enabled = !actions.screenshotBusy,
        ) { onInteraction(); actions.onScreenshot() }
        // Beside the screenshot rather than after it: a screenshot of a remote device is usually
        // taken to send to somebody, and saving first means finding the file again elsewhere.
        PanelTile(
            icon = Icons.Default.Share,
            description = stringResource(R.string.capture_share_screenshot),
            label = stringResource(R.string.artefact_share),
            enabled = !actions.screenshotBusy,
        ) { onInteraction(); actions.onShareScreenshot() }
        PanelTile(
            icon = if (actions.recording) Icons.Default.StopCircle else Icons.Default.Videocam,
            description = stringResource(
                if (actions.recording) R.string.recording_action_stop else R.string.recording_action_start),
            label = stringResource(
                if (actions.recording) R.string.recording_in_progress else R.string.release_control_record),
        ) { onInteraction(); actions.onToggleRecording() }
        PanelTile(
            icon = Icons.Default.DriveFolderUpload,
            description = stringResource(if (actions.sendFileBusy) R.string.send_file_busy else R.string.control_send_file),
            label = stringResource(R.string.release_control_send_file),
            enabled = !actions.sendFileBusy,
        ) { onInteraction(); actions.onSendFile() }
    }
}

/**
 * Magnifies this screen rather than the remote device, so it belongs in every session, and
 * nothing is sent for it. Explicit buttons rather than a pinch: a pinch on the mirror is
 * forwarded to the device as genuine multi-touch. While magnified, two fingers move the picture
 * and one still reaches the device. Calibration sits here too: it adjusts how this screen's
 * touches map onto the picture.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewControls(
    actions: MirrorActions,
    calibrationVisible: Boolean,
    onToggleCalibration: () -> Unit,
    onInteraction: () -> Unit,
) {
    PanelSection(stringResource(R.string.view_zoom_section)) {
        PanelTile(
            icon = Icons.Default.ZoomOut,
            description = stringResource(R.string.view_zoom_out),
            label = stringResource(R.string.view_zoom_out_label),
            enabled = actions.viewZoom > 1f,
        ) { onInteraction(); actions.onViewZoomOut() }
        PanelTile(
            icon = Icons.Default.ZoomIn,
            description = stringResource(R.string.view_zoom_in),
            label = stringResource(R.string.view_zoom_in_label),
            enabled = actions.viewZoom < VideoLayout.MAX_ZOOM,
        ) { onInteraction(); actions.onViewZoomIn() }
        PanelTile(
            icon = Icons.Default.FitScreen,
            description = stringResource(R.string.view_zoom_reset),
            label = stringResource(R.string.view_zoom_reset_label),
            active = actions.viewZoom > 1f,
            enabled = actions.viewZoom > 1f,
        ) { onInteraction(); actions.onViewZoomReset() }
        if (actions.cameraSession) {
            // A camera takes no touches, so it has no offsets to calibrate: just the overlay.
            PanelTile(
                icon = Icons.Default.Layers,
                description = stringResource(R.string.panel_overlay_description),
                label = stringResource(R.string.panel_overlay_label),
                active = actions.diagnosticsVisible,
                state = stringResource(if (actions.diagnosticsVisible) R.string.toggle_state_on
                    else R.string.toggle_state_off),
            ) { onInteraction(); actions.onToggleDiagnostics() }
        } else {
            PanelTile(
                icon = Icons.Outlined.TouchApp,
                description = stringResource(R.string.calibrate),
                active = calibrationVisible,
                state = stringResource(if (calibrationVisible) R.string.toggle_state_on else R.string.toggle_state_off),
            ) { onInteraction(); onToggleCalibration() }
        }
    }
    if (actions.viewZoom > 1f) {
        Text(
            stringResource(R.string.view_zoom_active, actions.viewZoom),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** What this session is running and the settings behind it: looked at rather than used often. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionControls(actions: MirrorActions, onInteraction: () -> Unit) {
    PanelSection(stringResource(R.string.panel_section_session)) {
        PanelTile(Icons.Default.Info, stringResource(R.string.details_action),
            label = stringResource(R.string.release_control_details)) {
            onInteraction(); actions.onConnectionDetails()
        }
        actions.onDeviceSettings?.let { open ->
            PanelTile(Icons.Default.Settings, stringResource(R.string.session_device_settings),
                label = stringResource(R.string.release_control_settings)) {
                onInteraction(); open()
            }
        }
        PanelTile(Icons.Default.Tune, stringResource(R.string.stream_choices_title),
            label = stringResource(R.string.stream_choices_label)) {
            onInteraction(); actions.onStreamChoices()
        }
        PanelTile(Icons.Default.Memory, stringResource(R.string.control_encoders),
            label = stringResource(R.string.release_control_encoders)) {
            onInteraction(); actions.onOpenEncoders()
        }
        PanelTile(Icons.Default.Monitor, stringResource(R.string.control_displays),
            label = stringResource(R.string.release_control_displays)) {
            onInteraction(); actions.onOpenDisplays()
        }
        // Not the photo camera: Screenshot, a few tiles up, already uses that one.
        PanelTile(Icons.Default.Cameraswitch, stringResource(R.string.cameras_action),
            label = stringResource(R.string.cameras_title)) {
            onInteraction(); actions.onOpenCameras()
        }
    }
}

/**
 * Touch calibration where it is actually usable: adjusting while the mirrored pointer is visible,
 * instead of leaving the session to reach the settings screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalibrationControls(actions: MirrorActions, onInteraction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        actions.calibrationScope?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(if (actions.calibrationOverridesDefaults) R.string.profile_custom else R.string.profile_inherited),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            stringResource(R.string.calibration_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        CalibrationRow(
            label = stringResource(R.string.setting_vertical_offset),
            value = actions.touchOffsetY,
            onChange = { onInteraction(); actions.onTouchOffsetChange(actions.touchOffsetX, it) },
        )
        CalibrationRow(
            label = stringResource(R.string.setting_horizontal_offset),
            value = actions.touchOffsetX,
            onChange = { onInteraction(); actions.onTouchOffsetChange(it, actions.touchOffsetY) },
        )
        FlowRow(Modifier.fillMaxWidth()) {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                onInteraction()
                actions.onTouchOffsetChange(0, 0)
            }) { Text(stringResource(R.string.reset)) }
            if (actions.calibrationScope != null) {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = actions.calibrationOverridesDefaults,
                    onClick = { onInteraction(); actions.onUseGlobalCalibration() },
                ) { Text(stringResource(R.string.profile_use_defaults)) }
            }
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { onInteraction(); actions.onToggleDiagnostics() }) {
                Text(stringResource(if (actions.diagnosticsVisible) R.string.hide_overlay else R.string.show_overlay))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalibrationRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.setting_offset_value, label, value),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { onChange((value - 1).coerceAtLeast(-TOUCH_OFFSET_LIMIT_DP)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.control_minus_one, label),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { onChange((value + 1).coerceAtMost(TOUCH_OFFSET_LIMIT_DP)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.control_plus_one, label),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { onChange((value - 10).coerceAtLeast(-TOUCH_OFFSET_LIMIT_DP)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowDown,
                    contentDescription = stringResource(R.string.control_minus_ten, label),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { onChange((value + 10).coerceAtMost(TOUCH_OFFSET_LIMIT_DP)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardDoubleArrowUp,
                    contentDescription = stringResource(R.string.control_plus_ten, label),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    live: Boolean,
    onLiveChange: (Boolean) -> Unit,
    onText: (String) -> Boolean,
    onLiveEdits: (List<com.scrcpy.remote.session.LiveTyping.Edit>) -> Boolean,
    onKey: (RemoteKey) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onInteraction: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    // Live is the default: each keystroke reaches the device as it is typed. Draft keeps the old
    // compose-then-send behaviour for long or sensitive text.
    // What the device has been sent of this field's text, so each change sends only its difference.
    var sent by remember { mutableStateOf("") }

    // Focus first, then raise the IME: showing it without focus is a no-op.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun sendDraft() {
        if (value.text.isNotEmpty()) {
            onInteraction()
            onValueChange(commitTextDraft(value, onText))
        }
    }

    fun liveChange(updated: TextFieldValue) {
        // The IME's unfinished composition stays local until it commits.
        val committed = com.scrcpy.remote.session.LiveTyping.committed(updated.text, updated.composition?.let { it.min until it.max })
        val edits = com.scrcpy.remote.session.LiveTyping.diff(sent, committed)
        if (edits.isEmpty() || onLiveEdits(edits)) sent = committed
        // A long field makes every correction walk the remote cursor further: start afresh once the
        // IME has nothing pending. The device keeps its text; only this mirror of it is cleared.
        if (updated.composition == null && committed.length > com.scrcpy.remote.session.LiveTyping.RESET_LENGTH) {
            sent = ""
            onValueChange(TextFieldValue())
        } else {
            onValueChange(updated)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(
                selected = live,
                onClick = { onInteraction(); onLiveChange(true); sent = ""; onValueChange(TextFieldValue()) },
                label = { Text(stringResource(R.string.ui_keyboard_live)) },
            )
            androidx.compose.material3.FilterChip(
                selected = !live,
                onClick = { onInteraction(); onLiveChange(false); sent = ""; onValueChange(TextFieldValue()) },
                label = { Text(stringResource(R.string.ui_keyboard_draft)) },
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = { updated ->
                onInteraction()
                if (live) liveChange(updated) else onValueChange(updated)
            },
            label = { Text(stringResource(if (live) R.string.ui_keyboard_live_label else R.string.ui_text_draft)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = if (live) ImeAction.Go else ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = { sendDraft() },
                // Enter in live mode is the device's Enter: the line is finished there, so start a new one here.
                onGo = { onInteraction(); onEnter(); sent = ""; onValueChange(TextFieldValue()) },
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        Text(
            stringResource(if (live) R.string.ui_keyboard_live_help else R.string.ui_text_draft_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        // Keys a phone keyboard lacks. Each goes straight to the device; anything that moves its
        // cursor or selection elsewhere also starts this field afresh, so later edits stay aligned.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (key in RemoteKey.entries) {
                androidx.compose.material3.AssistChip(
                    onClick = {
                        onInteraction()
                        onKey(key)
                        if (key.movesCursor) { sent = ""; onValueChange(TextFieldValue()) }
                    },
                    label = { Text(stringResource(key.labelRes)) },
                )
            }
            if (live) {
                androidx.compose.material3.AssistChip(
                    onClick = { onInteraction(); sent = ""; onValueChange(TextFieldValue()) },
                    label = { Text(stringResource(R.string.ui_keyboard_resync)) },
                )
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!live) {
                Button(
                    onClick = ::sendDraft,
                    enabled = value.text.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ui_send_text)) }
            }
            IconButton(
                // In live mode an empty field has nothing to delete here, so Backspace reaches the device.
                onClick = {
                    onInteraction()
                    if (value.text.isNotEmpty()) {
                        val trimmed = backspaced(value)
                        if (live) liveChange(trimmed) else onValueChange(trimmed)
                    } else onBackspace()
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.ui_remote_backspace),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = { onInteraction(); onEnter(); if (live) { sent = ""; onValueChange(TextFieldValue()) } },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardReturn,
                    contentDescription = stringResource(R.string.ui_remote_enter),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun PanelButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    label: String = description,
    active: Boolean = false,
    enabled: Boolean = true,
    /** Current state for accessibility; none unless given, so the label is not read twice. */
    state: String? = null,
    onClick: () -> Unit,
) {
    val contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.width(LocalPanelTileWidth.current).heightIn(min = 76.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; if (state != null) stateDescription = state }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = contentColor.copy(alpha = if (enabled) 1f else 0.38f))
        // Two lines, so a longer name ("Quick settings", "Close panels") reads whole rather than
        // being cut to "Close pan…"; tiles in a section's row share the tallest one's height.
        Text(label, Modifier.clearAndSetSemantics {}, color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun PanelSectionLabel(text: String) {
    Text(text, Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun normalizedHandlePosition(position: Float, minimum: Float, maximum: Float): Float =
    if (maximum <= minimum) 0.5f else ((position - minimum) / (maximum - minimum)).coerceIn(0f, 1f)

/** Deletes the char (or surrogate pair) before the selection end; keeps the cursor after the edit. */
internal fun backspaced(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val end = value.selection.max.coerceIn(0, text.length)
    val start = value.selection.min.coerceIn(0, text.length)
    if (start != end) {
        return TextFieldValue(text.removeRange(start, end), androidx.compose.ui.text.TextRange(start))
    }
    if (end == 0) return TextFieldValue(text, androidx.compose.ui.text.TextRange(0))
    val from = if (end >= 2 && text[end - 1].isLowSurrogate() && text[end - 2].isHighSurrogate()) end - 2 else end - 1
    return TextFieldValue(text.removeRange(from, end), androidx.compose.ui.text.TextRange(from))
}

internal fun commitTextDraft(value: TextFieldValue, onText: (String) -> Boolean): TextFieldValue {
    if (value.text.isEmpty()) return value
    return if (onText(value.text)) TextFieldValue() else value
}

private val HANDLE_SIZE = 48.dp
private val HANDLE_MARGIN = 8.dp
private val PANEL_MAX_HEIGHT = 440.dp

/** A panel tile is never narrower than this, so its icon and two-line label keep their room. */
private val PANEL_TILE_MIN_WIDTH = 72.dp
private val PANEL_TILE_GAP = 8.dp
private val PANEL_TILE_MAX_WIDTH = 120.dp
private const val IDLE_DELAY_MS = 2500L

/** Material's scrim strength: the screen stays readable behind the panel, but plainly paused. */
private const val PANEL_SCRIM_ALPHA = 0.32f

/** Matches the settings limit; generous enough to cancel a large systematic offset. */
const val TOUCH_OFFSET_LIMIT_DP = 120

/** Keys the keyboard panel offers beyond what a phone keyboard has, with the chord each sends. */
enum class RemoteKey(
    @androidx.annotation.StringRes val labelRes: Int,
    val keycode: Int,
    val metaState: Int = 0,
    /** Whether the key moves the device's cursor or selection, so the live field must start afresh. */
    val movesCursor: Boolean = true,
) {
    TAB(R.string.ui_key_tab, android.view.KeyEvent.KEYCODE_TAB),
    ESC(R.string.ui_key_esc, android.view.KeyEvent.KEYCODE_ESCAPE, movesCursor = false),
    LEFT(R.string.ui_key_left, android.view.KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(R.string.ui_key_right, android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
    UP(R.string.ui_key_up, android.view.KeyEvent.KEYCODE_DPAD_UP),
    DOWN(R.string.ui_key_down, android.view.KeyEvent.KEYCODE_DPAD_DOWN),
    HOME(R.string.ui_key_line_start, android.view.KeyEvent.KEYCODE_MOVE_HOME),
    END(R.string.ui_key_line_end, android.view.KeyEvent.KEYCODE_MOVE_END),
    SELECT_ALL(R.string.ui_key_select_all, android.view.KeyEvent.KEYCODE_A,
        android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON),
    DELETE_WORD(R.string.ui_key_delete_word, android.view.KeyEvent.KEYCODE_DEL,
        android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON),
}

/**
 * The live stats line: frames per second received, the 95th-percentile time from receipt to
 * screen, and the video bitrate. Small and translucent, and it never takes a touch from the mirror.
 */
@Composable
fun StatsChip(
    frames: com.scrcpy.remote.session.VideoFrameStats,
    latency: com.scrcpy.remote.session.LatencyStats,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(R.string.stats_chip, frames.receivedFps,
        if (latency.samples > 0) latency.p95Millis.toString() else "\u2013",
        formatKbps(frames.receivedKbps, stringResource(R.string.kbps_mega_format), stringResource(R.string.kbps_kilo_format)))
    Text(
        text,
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = text },
        color = MaterialTheme.colorScheme.inverseOnSurface,
        style = MaterialTheme.typography.labelSmall,
    )
}

internal fun formatKbps(
    kbps: Int,
    megaFormat: String = "%1\$.1f Mb/s",
    kiloFormat: String = "%1\$d kb/s",
): String =
    if (kbps >= 1000) String.format(java.util.Locale.ROOT, megaFormat, kbps / 1000f)
    else String.format(java.util.Locale.ROOT, kiloFormat, kbps)
