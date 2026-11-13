package com.scrcpy.remote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.AppBundle
import com.scrcpy.remote.session.AppInstallOutcome
import com.scrcpy.remote.session.AppOffer
import com.scrcpy.remote.session.FileTransfers

/**
 * Asks what to do with an app package: install it on the device, or, when the user asked to send
 * a file, copy it to Download like any other.
 *
 * The app's own name, version and icon come first, read on this phone, so a wrong pick is caught
 * before anything is sent. Nothing can be chosen while the package is still being read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppOfferDialog(
    offer: AppOffer,
    onInstall: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(if (offer.canCopy) R.string.install_offer_choice_title else R.string.install_offer_title))
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("app-offer"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val app = offer.app
                when {
                    offer.reading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.install_offer_reading), style = MaterialTheme.typography.bodyMedium)
                    }
                    app != null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        app.icon?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.size(48.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            app.versionName?.let {
                                Text(stringResource(R.string.install_offer_version, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> Text(stringResource(R.string.install_offer_unread), style = MaterialTheme.typography.bodyMedium)
                }
                if (offer.name.isNotEmpty()) {
                    Text(
                        stringResource(R.string.install_offer_file, offer.name, FileTransfers.formatSize(offer.size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                offer.bundle?.let { bundle ->
                    // An unreadable bundle has no count worth giving: nothing in it was read.
                    if (bundle.problem != AppBundle.Problem.UNREADABLE) {
                        Text(pluralStringResource(R.plurals.install_offer_bundle, bundle.files, bundle.files),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    bundle.problemText(LocalContext.current)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (offer.canCopy) {
                    Text(stringResource(R.string.install_offer_choice), style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.install_offer_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !offer.reading && offer.bundle?.problem == null,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.install_action))
            }
        },
        dismissButton = {
            // Wraps rather than squeezing three labels onto one line of a narrow window.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
                if (offer.canCopy) {
                    TextButton(onClick = onCopy, enabled = !offer.reading, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.install_copy_action))
                    }
                }
            }
        },
    )
}

/**
 * How an install ended, in the device's own words as well as plain ones.
 *
 * Open is offered only once the device's refreshed app list shows the new app, so the button is
 * never there for an app that has nothing to open.
 */
@Composable
internal fun AppInstallOutcomeDialog(
    outcome: AppInstallOutcome,
    canOpen: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    val result = outcome.result
    val openable = result == ApkInstall.Result.Installed && canOpen
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(stringResource(when (result) {
                ApkInstall.Result.Installed -> R.string.install_done_title
                ApkInstall.Result.Unconfirmed -> R.string.install_unconfirmed_title
                is ApkInstall.Result.Refused -> R.string.install_failed_title
            }))
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("app-install-outcome"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (result) {
                    ApkInstall.Result.Installed ->
                        Text(stringResource(R.string.install_done_message, outcome.name))
                    ApkInstall.Result.Unconfirmed ->
                        Text(stringResource(R.string.install_unconfirmed, outcome.name))
                    is ApkInstall.Result.Refused -> {
                        Text(stringResource(R.string.install_failed_message, outcome.name),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(ApkInstall.message(result.reason)), style = MaterialTheme.typography.bodyMedium)
                        // Only the device's own words are put in its mouth.
                        Text(stringResource(if (result.local) R.string.install_checked_here else R.string.install_device_said,
                            result.detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (openable) onOpen else onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (openable) R.string.install_open_action else R.string.done))
            }
        },
        dismissButton = if (openable) {
            {
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.done))
                }
            }
        } else {
            null
        },
    )
}
