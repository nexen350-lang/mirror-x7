package com.scrcpy.remote.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.ConnectionProblem
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.DeviceConnectionHistory
import com.scrcpy.remote.session.FailureHistory
import java.util.Calendar

/** When something happened: the time alone today, the date and time before that. */
internal fun historyTime(context: Context, atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val flags = if (sameDay(atMillis, nowMillis)) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    return DateUtils.formatDateTime(context, atMillis, flags)
}

private fun sameDay(first: Long, second: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = first }
    val b = Calendar.getInstance().apply { timeInMillis = second }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

/** How an attempt failed, in a few words. A cancelled attempt is never recorded. */
@StringRes
internal fun FailureKind.historyOutcome(): Int = when (this) {
    FailureKind.CONNECTION_REFUSED -> R.string.connection_history_outcome_refused
    FailureKind.NOT_RESPONDING -> R.string.connection_history_outcome_no_answer
    FailureKind.TRANSIENT_TRANSPORT -> R.string.connection_history_outcome_dropped
    FailureKind.AUTHENTICATION -> R.string.connection_history_outcome_not_allowed
    FailureKind.PAIRING_REQUIRED -> R.string.connection_history_outcome_pairing
    FailureKind.PROTOCOL -> R.string.connection_history_outcome_protocol
    FailureKind.CODEC_OR_INPUT -> R.string.connection_history_outcome_codec
    FailureKind.INVALID_INPUT -> R.string.connection_history_outcome_setting
    FailureKind.CANCELLED, FailureKind.OTHER -> R.string.connection_history_outcome_other
}

/** "Oct 3, 9:04 PM · ADB authorization: not allowed", with how many times when more than once. */
@Composable
internal fun problemText(problem: ConnectionProblem, nowMillis: Long): String {
    val time = historyTime(LocalContext.current, problem.lastAtMillis, nowMillis)
    val step = stageLabel(problem.stage, problem.endpoint)
    val outcome = stringResource(problem.kind.historyOutcome())
    return if (problem.count > 1) {
        pluralStringResource(R.plurals.connection_history_problem_repeated, problem.count, time, step, outcome, problem.count)
    } else {
        stringResource(R.string.connection_history_problem, time, step, outcome)
    }
}

/**
 * Under a failed attempt's checkup: whether the same failure keeps happening, when the device last
 * connected, and what went wrong before.
 */
@Composable
internal fun FailureHistoryNote(history: FailureHistory, nowMillis: Long = System.currentTimeMillis()) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().testTag("failure-history"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.connection_history_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall)
        if (history.current.count > 1) {
            Text(pluralStringResource(R.plurals.connection_history_repeated, history.current.count,
                history.current.count, historyTime(context, history.current.firstAtMillis, nowMillis)),
                style = MaterialTheme.typography.bodySmall)
        }
        history.lastConnectedAtMillis?.let {
            Text(stringResource(R.string.connection_history_last_connected, historyTime(context, it, nowMillis)),
                style = MaterialTheme.typography.bodySmall)
        }
        if (history.earlier.isNotEmpty()) {
            Text(stringResource(R.string.connection_history_earlier), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (problem in history.earlier) {
                Text(problemText(problem, nowMillis), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A saved device's connection history, from its menu, with a way to clear it. */
@Composable
internal fun ConnectionHistoryDialog(
    device: SavedDevice,
    history: DeviceConnectionHistory?,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    val recorded = history?.takeUnless { it.isEmpty }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connection_history_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag("connection-history-dialog"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                if (recorded == null) {
                    Text(stringResource(R.string.connection_history_none))
                } else {
                    Text(recorded.lastConnectedAtMillis
                        ?.let { stringResource(R.string.connection_history_last_connected, historyTime(context, it, nowMillis)) }
                        ?: stringResource(R.string.connection_history_never_connected))
                    if (recorded.problems.isEmpty()) {
                        Text(stringResource(R.string.connection_history_no_problems))
                    } else {
                        Text(stringResource(R.string.connection_history_problems), Modifier.semantics { heading() },
                            style = MaterialTheme.typography.labelLarge)
                        for (problem in recorded.problems.asReversed()) {
                            Text(problemText(problem, nowMillis), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(stringResource(R.string.connection_history_kept_here), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = onClear, enabled = recorded != null) { Text(stringResource(R.string.connection_history_clear)) }
        },
    )
}
