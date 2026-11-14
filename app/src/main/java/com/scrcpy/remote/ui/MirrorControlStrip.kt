package com.scrcpy.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import kotlinx.coroutines.delay
import java.util.Locale

internal const val DEFAULT_STRIP_AUTO_HIDE_MS = 3800L

/**
 * Unobtrusive macOS-inspired dark translucent control pill anchored at the bottom center of the
 * tablet mirroring experience.
 *
 * Auto-hides after [autoHideDelayMs] of inactivity and wakes cleanly on pointer hover or tap on
 * the minimal bottom grabber pill. Blocks background taps only within the painted pill bounds so
 * missed button edge taps never leak through to the remote phone.
 */
@Composable
internal fun MirrorControlStrip(
    zoom: Float,
    localZoomMode: Boolean,
    windowMode: MirrorWindowMode,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onResetZoom: () -> Unit,
    onFitToScreen: () -> Unit,
    onToggleLocalZoomMode: () -> Unit,
    onToggleMaximize: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    initialVisible: Boolean = true,
    autoHideDelayMs: Long = DEFAULT_STRIP_AUTO_HIDE_MS,
) {
    var stripVisible by remember { mutableStateOf(initialVisible) }
    var interactionCount by remember { mutableIntStateOf(0) }
    var stripHovered by remember { mutableStateOf(false) }
    var wakeHovered by remember { mutableStateOf(false) }

    LaunchedEffect(stripVisible, interactionCount, stripHovered, wakeHovered, autoHideDelayMs) {
        if (stripVisible && !stripHovered && !wakeHovered && autoHideDelayMs > 0L) {
            delay(autoHideDelayMs)
            stripVisible = false
        }
    }

    fun noteActivity() {
        interactionCount++
        stripVisible = true
    }

    val isZoomed = zoom > 1.01f
    val canZoomIn = zoom < VideoLayout.MAX_ZOOM - 0.01f
    val isFullscreen = windowMode == MirrorWindowMode.FULLSCREEN
    val isMaximized = windowMode == MirrorWindowMode.MAXIMIZED

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Subtle bottom wake handle when the strip has auto-hidden.
        AnimatedVisibility(
            visible = !stripVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val wakeLabel = stringResource(R.string.mirror_control_wake_strip)
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .width(96.dp)
                    .testTag("mirror-control-strip-wake")
                    .semantics { contentDescription = wakeLabel }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> {
                                        wakeHovered = true
                                        noteActivity()
                                    }
                                    PointerEventType.Exit -> wakeHovered = false
                                    else -> Unit
                                }
                            }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = wakeLabel,
                        onClick = { noteActivity() },
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .padding(bottom = 4.dp)
                        .width(44.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.36f)),
                )
            }
        }

        AnimatedVisibility(
            visible = stripVisible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val stripShape = CircleShape
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .shadow(10.dp, stripShape)
                    .clip(stripShape)
                    .background(Color(0xE816171C), stripShape)
                    .border(1.dp, Color.White.copy(alpha = 0.16f), stripShape)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> {
                                        stripHovered = true
                                    }
                                    PointerEventType.Exit -> {
                                        stripHovered = false
                                    }
                                    else -> Unit
                                }
                                event.changes.forEach { change ->
                                    if (!change.isConsumed && change.pressed) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("mirror-control-strip"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StripIconButton(
                    icon = Icons.Default.ZoomOut,
                    description = stringResource(R.string.mirror_control_zoom_out),
                    enabled = isZoomed,
                    testTag = "mirror-control-zoom-out",
                    onClick = {
                        noteActivity()
                        onZoomOut()
                    },
                )

                val zoomReadout = String.format(Locale.US, "%.1f×", zoom)
                val resetDesc = stringResource(R.string.mirror_control_reset_zoom, zoom)
                TextButton(
                    onClick = {
                        noteActivity()
                        onResetZoom()
                    },
                    enabled = isZoomed,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("mirror-control-reset-zoom")
                        .semantics { contentDescription = resetDesc },
                ) {
                    Text(
                        text = zoomReadout,
                        color = Color.White.copy(alpha = if (isZoomed) 0.96f else 0.50f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                StripIconButton(
                    icon = Icons.Default.ZoomIn,
                    description = stringResource(R.string.mirror_control_zoom_in),
                    enabled = canZoomIn,
                    testTag = "mirror-control-zoom-in",
                    onClick = {
                        noteActivity()
                        onZoomIn()
                    },
                )

                StripDivider()

                StripIconButton(
                    icon = Icons.Default.FitScreen,
                    description = stringResource(R.string.mirror_control_fit_screen),
                    testTag = "mirror-control-fit-screen",
                    onClick = {
                        noteActivity()
                        onFitToScreen()
                    },
                )

                val panDesc = stringResource(R.string.mirror_control_pan_mode)
                StripIconButton(
                    icon = Icons.Default.PanTool,
                    description = panDesc,
                    selected = localZoomMode,
                    testTag = "mirror-control-pan-mode",
                    onClick = {
                        noteActivity()
                        onToggleLocalZoomMode()
                    },
                )

                StripIconButton(
                    icon = if (isMaximized) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                    description = stringResource(
                        if (isMaximized) R.string.mirror_control_restore_window else R.string.mirror_control_maximize,
                    ),
                    selected = isMaximized,
                    testTag = "mirror-control-maximize",
                    onClick = {
                        noteActivity()
                        onToggleMaximize()
                    },
                )

                StripIconButton(
                    icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    description = stringResource(
                        if (isFullscreen) R.string.mirror_control_exit_fullscreen else R.string.mirror_control_fullscreen,
                    ),
                    selected = isFullscreen,
                    testTag = "mirror-control-fullscreen",
                    onClick = {
                        noteActivity()
                        onToggleFullscreen()
                    },
                )

                StripDivider()

                StripIconButton(
                    icon = Icons.Default.PowerSettingsNew,
                    description = stringResource(R.string.mirror_control_disconnect),
                    tint = Color(0xFFFF6B6B),
                    testTag = "mirror-control-disconnect",
                    onClick = {
                        noteActivity()
                        onDisconnect()
                    },
                )
            }
        }
    }
}

@Composable
private fun StripIconButton(
    icon: ImageVector,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Color.White,
) {
    val activeBg = if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(activeBg, CircleShape)
            .testTag(testTag)
            .semantics {
                if (selected) stateDescription = description
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = tint.copy(alpha = if (enabled) 0.94f else 0.34f),
        )
    }
}

@Composable
private fun StripDivider() {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(20.dp)
            .background(Color.White.copy(alpha = 0.14f)),
    )
}
