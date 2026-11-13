package com.scrcpy.remote.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ConnectionDiagnosis
import com.scrcpy.remote.session.DiagnosisAction
import com.scrcpy.remote.session.FailureReport
import com.scrcpy.remote.session.ProbeReport
import com.scrcpy.remote.session.ProbeStatus
import com.scrcpy.remote.session.ProbeStep

/** Cause, next step and one-tap actions for a [ConnectionDiagnosis]. Retry/re-pair are the caller's. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionDiagnosisPanel(
    diagnosis: ConnectionDiagnosis,
    details: String,
    onRetry: (() -> Unit)?,
    onRepair: (() -> Unit)?,
    retryLabel: String = stringResource(R.string.reconnect),
) {
    val context = LocalContext.current
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val copied = stringResource(R.string.diag_copied)
    val clipLabel = stringResource(R.string.diag_title)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("diagnosis-panel")) {
        Text(stringResource(R.string.diag_cause_label), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(diagnosis.cause), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.diag_next_label), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(diagnosis.nextStep), style = MaterialTheme.typography.bodyMedium)
        if (showHelp) Text(stringResource(R.string.diag_help_text), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showDetails) Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("diagnosis-details"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in diagnosis.actions) when (action) {
                DiagnosisAction.RETRY -> if (onRetry != null) OutlinedButton(onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(retryLabel) }
                DiagnosisAction.REPAIR -> if (onRepair != null) OutlinedButton(onClick = onRepair,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_action_repair)) }
                DiagnosisAction.WIRELESS_DEBUGGING_HELP -> OutlinedButton(onClick = { showHelp = !showHelp },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_action_help)) }
                DiagnosisAction.OPEN_SETTINGS -> OutlinedButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (_: ActivityNotFoundException) { }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_action_settings)) }
                DiagnosisAction.COPY_DETAILS -> OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(clipLabel, details))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_action_copy)) }
                DiagnosisAction.DETAILS -> OutlinedButton(onClick = { showDetails = !showDetails },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.diag_action_details)) }
            }
        }
    }
}

@Composable
internal fun ConnectionDiagnosisDialog(
    report: FailureReport,
    onRetry: () -> Unit,
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ConnectionDiagnosisPanel(report.diagnosis, report.details,
                    onRetry = { onDismiss(); onRetry() }, onRepair = { onDismiss(); onRepair() })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Per-step results of "Check connection" for a saved device. */
@Composable
internal fun ConnectionCheckDialog(
    state: ConnectionCheckState,
    onCheckAgain: () -> Unit,
    onRepair: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_connection_title, state.deviceName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("connection-check"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (step in state.report.steps) {
                    val label = stringResource(when (step.step) {
                        ProbeStep.NETWORK -> R.string.check_step_network
                        ProbeStep.ADB_AUTHORIZATION -> R.string.check_step_adb
                        ProbeStep.SERVER -> R.string.check_step_server
                    })
                    val status = stringResource(when (step.status) {
                        ProbeStatus.PENDING -> R.string.check_status_pending
                        ProbeStatus.RUNNING -> R.string.check_status_running
                        ProbeStatus.PASSED -> R.string.check_status_passed
                        ProbeStatus.FAILED -> R.string.check_status_failed
                        ProbeStatus.SKIPPED -> R.string.check_status_skipped
                    })
                    // One announcement per step: its name and state, with the icon silent.
                    Row(Modifier.semantics(mergeDescendants = true) { stateDescription = status },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (step.status) {
                            ProbeStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            ProbeStatus.PASSED -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            ProbeStatus.FAILED -> Icon(Icons.Filled.Error, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error)
                            else -> Icon(Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(status, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val failure = state.report.firstFailure
                val diagnosis = state.report.diagnosis
                if (!state.running && state.report.passed) {
                    Text(stringResource(R.string.check_result_ok), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } else if (!state.running && failure != null && diagnosis != null) {
                    Text(stringResource(R.string.check_result_failed, stringResource(when (failure.step) {
                        ProbeStep.NETWORK -> R.string.check_step_network
                        ProbeStep.ADB_AUTHORIZATION -> R.string.check_step_adb
                        ProbeStep.SERVER -> R.string.check_step_server
                    })), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    ConnectionDiagnosisPanel(diagnosis, state.details.orEmpty(),
                        onRetry = onCheckAgain, onRepair = onRepair,
                        retryLabel = stringResource(R.string.check_connection_run_again))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (!state.running && state.report.passed) {
            { TextButton(onClick = onCheckAgain) { Text(stringResource(R.string.check_connection_run_again)) } }
        } else null,
    )
}

internal data class ConnectionCheckState(
    val deviceName: String,
    val report: ProbeReport,
    val running: Boolean,
    val details: String? = null,
)
