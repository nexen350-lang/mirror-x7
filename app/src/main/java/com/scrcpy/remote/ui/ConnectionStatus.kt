package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.CheckupStep
import com.scrcpy.remote.session.ConnectionCheckup
import com.scrcpy.remote.session.FailureHistory
import com.scrcpy.remote.session.StageStatus
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionStatus(
    message: String,
    connecting: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
    showGenericHint: Boolean = true,
    /** The steps the current or failed attempt got through; null when none was made. */
    checkup: ConnectionCheckup? = null,
    /** What a saved device's earlier attempts say about this failure; null when it is not saved. */
    history: FailureHistory? = null,
    /**
     * After a Picture & sound switch failed: going back to the choice that worked, named. Offered
     * beside Retry, which would try the new choice again, so the user picks rather than the app.
     */
    revertLabel: String? = null,
    onRevert: () -> Unit = {},
    /** Opens the diagnosis of this failure; null hides the button. */
    onDiagnose: (() -> Unit)? = null,
    /** Raw technical details kept collapsed under a "Details" button until tapped. */
    technicalDetails: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .testTag("connection-status-body"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(if (connecting) Icons.Outlined.Phonelink else Icons.Outlined.WifiOff, null,
                            Modifier.padding(16.dp).size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(stringResource(if (connecting) R.string.release_connecting_title else R.string.release_connection_failed),
                        Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center)
                    if (connecting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        // Announced as it changes: a screen reader user waiting to connect would
                        // otherwise hear nothing when the attempt progresses or gives up, since
                        // the dialog itself stays on screen throughout.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    // A message that already says what to do must not be followed by generic
                    // advice that repeats or contradicts it.
                    if (connecting || showGenericHint) {
                        Text(stringResource(if (connecting) R.string.release_connecting_help else R.string.release_connection_check),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                    }
                    if (!connecting && !technicalDetails.isNullOrBlank()) {
                        var showDetails by rememberSaveable(technicalDetails) { mutableStateOf(false) }
                        TextButton(
                            onClick = { showDetails = !showDetails },
                            modifier = Modifier.testTag("connection-details-toggle"),
                        ) {
                            Text(stringResource(if (showDetails) R.string.error_details_hide else R.string.error_details_show))
                        }
                        if (showDetails) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().testTag("connection-error-details"),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Text(
                                    stringResource(R.string.error_detail, technicalDetails),
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (checkup != null) ConnectionCheckupSteps(checkup)
                    // Only under a failure: while connecting there is nothing yet for it to describe.
                    if (!connecting && history != null && history.worthShowing) FailureHistoryNote(history)
                }
                FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    if (!connecting) Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.reconnect))
                    }
                    if (!connecting && revertLabel != null) {
                        OutlinedButton(onClick = onRevert, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(revertLabel)
                        }
                    }
                    if (!connecting && onDiagnose != null) {
                        OutlinedButton(onClick = onDiagnose, modifier = Modifier.heightIn(min = 48.dp).testTag("diagnose-button")) {
                            Text(stringResource(R.string.diag_open))
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (connecting) R.string.cancel else R.string.back_to_devices))
                    }
                }
            }
        }
    }
}

/**
 * The connection checkup: each step the attempt took, how it ended and how long it took, with one
 * next action under the step that failed.
 *
 * Each row is one accessibility node, so a screen reader hears "Connect to 192.0.2.20:5555, Done
 * in 0.1 s" rather than an icon, a name and a status as three unrelated stops.
 */
@Composable
private fun ConnectionCheckupSteps(checkup: ConnectionCheckup) {
    Column(Modifier.fillMaxWidth().testTag("connection-checkup"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.checkup_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall)
        val nextAction = checkup.nextAction
        checkup.steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
                    .testTag("checkup-${step.stage.name.lowercase()}"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CheckupIcon(step.status)
                Column(Modifier.weight(1f)) {
                    Text(stageLabel(step.stage, checkup.endpoint), style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel(step), style = MaterialTheme.typography.bodySmall,
                        color = if (step.status == StageStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (step.status == StageStatus.FAILED && nextAction != null) {
                        Text(stringResource(R.string.checkup_next, stringResource(nextAction)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckupIcon(status: StageStatus) {
    val modifier = Modifier.padding(top = 2.dp).size(18.dp)
    when (status) {
        StageStatus.RUNNING -> CircularProgressIndicator(modifier, strokeWidth = 2.dp)
        StageStatus.PASSED -> Icon(Icons.Filled.CheckCircle, null, modifier, tint = MaterialTheme.colorScheme.primary)
        StageStatus.FAILED -> Icon(Icons.Filled.Error, null, modifier, tint = MaterialTheme.colorScheme.error)
        StageStatus.NOT_REACHED -> Icon(Icons.Outlined.RadioButtonUnchecked, null, modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun stageLabel(stage: CheckupStage, endpoint: String): String = when (stage) {
    CheckupStage.REACH -> stringResource(R.string.checkup_stage_reach, endpoint)
    CheckupStage.AUTHORIZE -> stringResource(R.string.checkup_stage_authorize)
    CheckupStage.DEPLOY -> stringResource(R.string.checkup_stage_deploy)
    CheckupStage.START -> stringResource(R.string.checkup_stage_start)
    CheckupStage.MEDIA -> stringResource(R.string.checkup_stage_media)
    CheckupStage.SESSION -> stringResource(R.string.checkup_stage_session)
}

@Composable
private fun statusLabel(step: CheckupStep): String {
    // Durations describe startup steps; how long a session ran before it dropped is not a delay.
    val elapsed = step.elapsedMillis?.takeIf { step.stage != CheckupStage.SESSION }
    val seconds = elapsed?.let { String.format(Locale.getDefault(), "%.1f", it / 1000.0) }
    return when (step.status) {
        StageStatus.PASSED -> when {
            elapsed == null -> stringResource(R.string.checkup_status_passed)
            elapsed < 1_000 -> stringResource(R.string.checkup_status_passed_ms, elapsed)
            else -> stringResource(R.string.checkup_status_passed_timed, seconds.orEmpty())
        }
        StageStatus.FAILED -> when {
            step.stage == CheckupStage.SESSION -> stringResource(R.string.checkup_status_lost)
            elapsed == null -> stringResource(R.string.checkup_status_failed)
            elapsed < 1_000 -> stringResource(R.string.checkup_status_failed_ms, elapsed)
            else -> stringResource(R.string.checkup_status_failed_timed, seconds.orEmpty())
        }
        StageStatus.RUNNING -> stringResource(R.string.checkup_status_running)
        StageStatus.NOT_REACHED -> stringResource(R.string.checkup_status_not_reached)
    }
}
