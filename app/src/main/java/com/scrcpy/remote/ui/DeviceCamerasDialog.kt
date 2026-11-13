package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CameraFacing
import com.scrcpy.remote.scrcpy.DeviceCamera
import com.scrcpy.remote.session.DeviceCamerasState
import com.scrcpy.remote.ui.design.InsetSelectRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Chooses which camera the remote device should capture and how, or turns camera capture off
 * again.
 *
 * Screen mirroring is offered as the first row rather than as a separate switch elsewhere: the
 * choice is "what am I looking at", and the screen is one of the answers.
 *
 * Choosing no longer applies immediately, because there is now more than one thing to choose: the
 * camera, its resolution, its frame rate and whether to use its high-speed mode. The draft is held
 * here until [onSelect], so a half-finished choice never reaches the server.
 */
@Composable
internal fun DeviceCamerasDialog(
    state: DeviceCamerasState,
    selected: CameraCapture?,
    onSelect: (CameraCapture?) -> Unit,
    createsProfile: Boolean,
    /** True when the choice applies to this session only, with no profile to keep it in. */
    sessionOnly: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the camera the caller handed us so that reopening the dialog after a change starts
    // from what is actually in force, rather than from a stale draft.
    var draft by rememberSaveable(selected, stateSaver = CameraCaptureDraftSaver) {
        mutableStateOf(selected)
    }
    val chosen = state.cameras.firstOrNull { it.id == draft?.id }
    // The camera the session is using, when the device's list does not include it: chosen by
    // which way it faces, or simply not reported. Without its own row the list showed nothing
    // selected, and Save kept a camera the user could not see.
    val unlisted = selected?.takeIf { current ->
        (current.id != null || current.facing != null) && state.cameras.none { it.id == current.id }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cameras_title)) },
        text = {
            val note = when {
                sessionOnly -> stringResource(R.string.cameras_session_only)
                createsProfile -> stringResource(R.string.cameras_creates_profile)
                else -> null
            }
            // A fixed help header can consume the entire text slot on a short, large-font window.
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).then(
                    if (!state.loading && state.error == null) Modifier.testTag("device-camera-list")
                    else Modifier,
                ),
            ) {
                item {
                    Text(
                        stringResource(R.string.cameras_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (note != null) {
                    item {
                        Text(
                            note,
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A refresh that failed keeps the list it had, with the reason above it.
                if (state.loaded && state.error != null) item {
                    Text(state.error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                when {
                    state.loading -> item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.heightIn(min = 24.dp))
                            Text(stringResource(R.string.cameras_loading),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    state.error != null && !state.loaded -> item {
                        Column(
                            Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(state.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.apps_retry))
                            }
                        }
                    }

                    else -> {
                        item {
                            InsetSelectRow(
                                title = stringResource(R.string.cameras_screen),
                                selected = draft == null,
                                onClick = { draft = null },
                                detail = stringResource(R.string.cameras_screen_detail),
                                enabled = true,
                            )
                            HorizontalDivider()
                        }
                        if (unlisted != null) {
                            item {
                                InsetSelectRow(
                                    title = stringResource(R.string.cameras_current_unlisted, captureTitle(unlisted)),
                                    selected = draft != null && draft?.id == unlisted.id && draft?.facing == unlisted.facing,
                                    onClick = { draft = unlisted },
                                    detail = stringResource(R.string.cameras_current_unlisted_detail),
                                    enabled = true,
                                )
                                HorizontalDivider()
                            }
                        }
                        items(state.cameras, key = { it.id }) { camera ->
                            InsetSelectRow(
                                title = cameraTitle(camera),
                                selected = camera.id == draft?.id,
                                onClick = {
                                    // A size or frame rate from one camera means nothing to
                                    // another, so switching cameras starts from the device's own
                                    // choice rather than carrying the last one across.
                                    if (draft?.id != camera.id) draft = CameraCapture(id = camera.id)
                                },
                                detail = cameraDetail(camera),
                                enabled = true,
                            )
                            HorizontalDivider()
                        }
                        if (state.cameras.isEmpty() && state.loaded) {
                            item {
                                Text(
                                    stringResource(R.string.cameras_empty),
                                    Modifier.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (chosen != null) {
                            item {
                                CameraCaptureOptions(
                                    camera = chosen,
                                    capture = draft ?: CameraCapture(id = chosen.id),
                                    onChange = { draft = it },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(draft?.normalized()) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("camera-save"),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Resolution, frame rate and high-speed mode for the camera now selected.
 *
 * High-speed is not a modifier on the ordinary sizes but a mode with its own list — the device
 * refuses a size from the wrong list — so turning it on clears the size and rate rather than
 * carrying over values that would fail at connect time.
 */
@Composable
private fun CameraCaptureOptions(
    camera: DeviceCamera,
    capture: CameraCapture,
    onChange: (CameraCapture) -> Unit,
) {
    val sizes = if (capture.highSpeed) camera.highSpeedSizes else camera.sizes
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("camera-capture-options"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.cameras_capture_heading).uppercase(LocalLocale.current.platformLocale),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (camera.highSpeedSizes.isNotEmpty()) {
            SwitchRow(
                stringResource(R.string.cameras_high_speed),
                capture.highSpeed,
            ) { on ->
                onChange(capture.copy(highSpeed = on, size = null, fps = 0))
            }
            Text(
                stringResource(R.string.cameras_high_speed_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (sizes.isEmpty()) {
            Text(
                stringResource(R.string.cameras_sizes_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.cameras_size_label),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = capture.size.isNullOrBlank(),
                    onClick = { onChange(capture.copy(size = null)) },
                    label = { Text(stringResource(R.string.cameras_auto)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                sizes.forEach { size ->
                    FilterChip(
                        selected = capture.size == size.serverValue,
                        onClick = {
                            // A high-speed size carries the only rates it supports, so a rate
                            // chosen for a different size is dropped rather than left to fail.
                            val keepFps = capture.fps.takeIf { it in size.frameRates } ?: 0
                            onChange(capture.copy(
                                size = size.serverValue,
                                fps = if (size.frameRates.isEmpty()) capture.fps else keepFps,
                            ))
                        },
                        label = { Text(size.serverValue) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.cameras_size_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // In high-speed mode the rates belong to the chosen size; otherwise they are the camera's.
        val rates = when {
            capture.highSpeed -> sizes.firstOrNull { it.serverValue == capture.size }
                ?.frameRates
                ?: sizes.flatMap { it.frameRates }.distinct().sorted()
            else -> camera.frameRates
        }
        if (rates.isNotEmpty()) {
            Text(stringResource(R.string.cameras_fps_label),
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = capture.fps == 0,
                    onClick = { onChange(capture.copy(fps = 0)) },
                    label = { Text(stringResource(R.string.cameras_auto)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                rates.forEach { rate ->
                    FilterChip(
                        selected = capture.fps == rate,
                        onClick = { onChange(capture.copy(fps = rate)) },
                        label = { Text(stringResource(R.string.cameras_fps_value, rate)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun cameraTitle(camera: DeviceCamera): String = when (camera.facing) {
    CameraFacing.FRONT -> stringResource(R.string.cameras_front)
    CameraFacing.BACK -> stringResource(R.string.cameras_back)
    CameraFacing.EXTERNAL -> stringResource(R.string.cameras_external)
    // A camera whose direction the device will not state is still selectable; naming it by id is
    // more use than calling it "unknown".
    null -> stringResource(R.string.cameras_numbered, camera.id)
}

/** Names a capture the device did not list: by its id when it has one, else by its facing. */
@Composable
private fun captureTitle(capture: CameraCapture): String = capture.id?.let { stringResource(R.string.cameras_numbered, it) }
    ?: when (capture.facing) {
        CameraFacing.FRONT -> stringResource(R.string.cameras_front)
        CameraFacing.BACK -> stringResource(R.string.cameras_back)
        CameraFacing.EXTERNAL, null -> stringResource(R.string.cameras_external)
    }

@Composable
private fun cameraDetail(camera: DeviceCamera): String = buildList {
    add(stringResource(R.string.cameras_id, camera.id))
    if (camera.sizeKnown) add("${camera.width}x${camera.height}")
    camera.frameRates.takeIf { it.isNotEmpty() }?.let {
        add(stringResource(R.string.cameras_fps, it.joinToString(", ")))
    }
    if (camera.canZoom) add(stringResource(R.string.cameras_zoom, camera.maxZoom))
}.joinToString(" · ")

private val CameraCaptureDraftSaver = Saver<CameraCapture?, String>(
    // Encode null explicitly so restoring "Mirror the screen" cannot revive the original camera.
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<CameraCapture?>(it) },
)
