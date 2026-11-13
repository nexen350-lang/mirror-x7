package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceDisplay
import com.scrcpy.remote.session.DeviceDisplaysState
import com.scrcpy.remote.session.canChooseSessionDisplay

/**
 * Lets the user mirror one of the device's existing displays instead of its primary screen.
 *
 * Offered in-session because only a connected device can say which displays it has, and the ids
 * mean nothing without the sizes beside them.
 */
@Composable
internal fun DeviceDisplaysDialog(
    state: DeviceDisplaysState,
    selected: Int?,
    /** Null when a display choice cannot be applied. */
    onSelect: ((Int) -> Unit)?,
    createsProfile: Boolean,
    /** True while a virtual display is configured, which the server refuses to combine with this. */
    virtualDisplayActive: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    sessionOnly: Boolean = false,
    recording: Boolean = false,
    adaptiveDesktopActive: Boolean = false,
    desktopResizing: Boolean = false,
    onAdaptiveDesktop: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.displays_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("device-display-list")) {
                item {
                    Text(
                        stringResource(R.string.displays_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onAdaptiveDesktop != null || adaptiveDesktopActive) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.desktop_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.desktop_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (adaptiveDesktopActive) {
                                Text(
                                    stringResource(when {
                                        recording -> R.string.desktop_recording_paused
                                        desktopResizing -> R.string.desktop_recording_wait
                                        else -> R.string.desktop_active
                                    }),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                enabled = !recording && onAdaptiveDesktop != null,
                                onClick = { onAdaptiveDesktop?.invoke() },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(
                                    if (adaptiveDesktopActive) R.string.desktop_restart else R.string.desktop_start))
                            }
                            HorizontalDivider()
                        }
                    }
                }
                val note = when {
                    recording -> R.string.desktop_stop_recording
                    virtualDisplayActive && sessionOnly -> R.string.desktop_return_primary
                    sessionOnly -> R.string.desktop_session_display
                    virtualDisplayActive -> R.string.displays_virtual_active
                    onSelect == null -> R.string.displays_needs_saved_device
                    createsProfile -> R.string.displays_creates_profile
                    else -> null
                }
                if (note != null) {
                    item {
                        Text(
                            stringResource(note),
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
                            Text(stringResource(R.string.displays_loading),
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

                    state.displays.isEmpty() && state.loaded -> item {
                        Text(
                            stringResource(R.string.displays_empty),
                            Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> items(state.displays, key = { it.id }) { display ->
                        DisplayRow(
                            display = display,
                            selected = display.id == selected,
                            enabled = onSelect != null && !recording && (!virtualDisplayActive || sessionOnly) &&
                                canChooseSessionDisplay(display.id, virtualDisplayActive),
                        ) { onSelect?.invoke(display.id) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun DisplayRow(
    display: DeviceDisplay,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selectedLabel = stringResource(R.string.encoders_selected)
    val contentColor = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val title = if (display.id == 0) stringResource(R.string.displays_primary, display.id)
        else stringResource(R.string.displays_secondary, display.id)
    val subtitle = if (display.sizeKnown) {
        stringResource(R.string.displays_size, display.width, display.height)
    } else {
        stringResource(R.string.displays_size_unknown)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { if (selected) stateDescription = selectedLabel }
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
