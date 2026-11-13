package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.data.ImportDisposition

@Composable
fun ProfileTransferDialogs(
    state: ProfileTransferState,
    onOptionsChanged: (Boolean, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = state.preview
    if (preview != null) {
        val incoming = preview.entries
        AlertDialog(
            onDismissRequest = { if (!state.busy) onDismiss() },
            title = { Text(stringResource(R.string.transfer_preview_title)) },
            text = {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.55f).toDp() })
                        .testTag("profile-import-preview"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(stringResource(R.string.transfer_preview_help), style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Text(stringResource(R.string.transfer_preview_counts, preview.added, preview.replaced, preview.skipped, preview.unchanged))
                    }
                    item {
                        SwitchRow(stringResource(R.string.transfer_replace), state.replaceExisting) {
                            if (!state.busy) onOptionsChanged(it, state.importDefaults)
                        }
                        SwitchRow(stringResource(R.string.transfer_defaults), state.importDefaults) {
                            if (!state.busy) onOptionsChanged(state.replaceExisting, it)
                        }
                        Text(stringResource(R.string.transfer_defaults_help), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.transfer_default_preview,
                            preview.after.settings.maxSize, preview.after.settings.maxFps),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(incoming, key = { it.incoming.id }) { entry ->
                        val device = entry.incoming
                        val label = when (entry.disposition) {
                            ImportDisposition.ADD -> R.string.transfer_device_add
                            ImportDisposition.UNCHANGED -> R.string.transfer_device_unchanged
                            ImportDisposition.REPLACE -> R.string.transfer_device_replace
                            ImportDisposition.KEEP -> R.string.transfer_device_keep
                        }
                        Column {
                            Text(
                                stringResource(R.string.transfer_device_entry, stringResource(label), device.name),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                            Text(deviceSettingsSummary(device.mirroringSettings), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (preview.repairsStorage) {
                        item { Text(stringResource(R.string.ui_storage_issue_title)) }
                    } else if (!preview.hasChanges) {
                        item { Text(stringResource(R.string.transfer_no_changes)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = !state.busy && preview.hasChanges) {
                    Text(stringResource(R.string.transfer_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
            },
        )
    } else if (state.busy) {
        // The system file picker covers this while active; it also communicates validation progress.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.transfer_working)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }
}
