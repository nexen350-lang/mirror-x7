package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.data.BackupHistoryEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun StorageBusyDialog(title: String = stringResource(R.string.storage_saving)) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.testTag("storage-busy"),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.storage_saving_help))
            }
        },
        confirmButton = {},
    )
}

@Composable
fun BackupHistoryDialogs(
    state: BackupHistoryState,
    entries: List<BackupHistoryEntry>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String?) -> Unit,
    onRestore: (String) -> Unit,
    onAskExport: (String?) -> Unit,
    onConfirmExport: () -> Unit,
    onAskDelete: (String?) -> Unit,
    onConfirmDelete: () -> Unit,
) {
    if (!state.visible) return
    val selected = entries.firstOrNull { it.id == state.selectedId }
    AlertDialog(
        onDismissRequest = { if (!state.busy) { if (selected == null) onDismiss() else onSelect(null) } },
        title = { Text(selected?.name ?: stringResource(R.string.history_title)) },
        text = {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height * 0.55f).toDp() })
                    .testTag(if (selected == null) "history-list" else "history-details"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.notice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodyMedium) } }
                if (selected == null) {
                    item { Text(stringResource(R.string.history_help), style = MaterialTheme.typography.bodySmall) }
                    if (entries.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
                    items(entries, key = { it.id }) { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            HistorySummary(entry)
                            TextButton(onClick = { onSelect(entry.id) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth().testTag("history-entry-${entry.id}")) {
                                Text(stringResource(R.string.history_details))
                            }
                        }
                    }
                } else {
                    item {
                        HistorySummary(selected)
                        Text(selected.details, style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Column {
                            TextButton(onClick = { onRestore(selected.id) }, enabled = selected.isComplete && !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_restore)) }
                            TextButton(onClick = { onAskExport(selected.id) }, enabled = selected.isComplete && !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_export)) }
                            TextButton(onClick = { onAskDelete(selected.id) }, enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.history_delete)) }
                        }
                    }
                    items(selected.deviceNames) { name -> Text(name, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (selected == null) onDismiss() else onSelect(null) }, enabled = !state.busy) {
                Text(stringResource(if (selected == null) R.string.close else R.string.history_back))
            }
        },
        // Refresh sits in the dismiss slot only because this dialog has no destructive action to
        // cancel; keeping the affirmative slot for Close/Back matches every other dialog here.
        dismissButton = {
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.history_retry)) }
        },
    )

    entries.firstOrNull { it.id == state.deleteId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onAskDelete(null) },
            modifier = Modifier.testTag("history-confirm-delete"),
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.history_delete_message, entry.name, historyDate(entry)))
                    if (!entry.isComplete) Text(stringResource(R.string.history_delete_recovery_warning))
                    state.notice?.let { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.busy,
                    modifier = Modifier.testTag("history-delete-confirm-action")) { Text(stringResource(R.string.history_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { onAskDelete(null) }, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    entries.firstOrNull { it.id == state.exportId }?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) onAskExport(null) },
            modifier = Modifier.testTag("history-export-warning"),
            title = { Text(stringResource(R.string.profile_tools_export_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.history_entry_summary, entry.name, historyDate(entry)))
                    Text(stringResource(R.string.profile_tools_export_help))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmExport, enabled = !state.busy) {
                    Text(stringResource(R.string.profile_tools_export_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAskExport(null) }, enabled = !state.busy) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (state.busy) StorageBusyDialog(stringResource(R.string.history_busy))
}

@Composable
private fun HistorySummary(entry: BackupHistoryEntry) {
    Text(historyDate(entry), style = MaterialTheme.typography.bodySmall)
    Text(entry.deviceCount?.let { pluralStringResource(R.plurals.history_count, it, it) }
        ?: stringResource(R.string.history_count_unknown), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(if (entry.isComplete) R.string.history_complete else R.string.history_protected),
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun historyDate(entry: BackupHistoryEntry): String =
    entry.createdAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        ?: stringResource(R.string.history_date_unknown)
