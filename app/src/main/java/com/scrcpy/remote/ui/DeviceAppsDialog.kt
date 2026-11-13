package com.scrcpy.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.session.DeviceAppPages
import com.scrcpy.remote.session.DeviceAppsState

/**
 * Filters by both the visible name and the package name.
 *
 * The package is searchable because two apps can share a display name, and because a user who
 * knows the package should not have to guess how the device labels it.
 */
internal fun visibleApps(apps: List<DeviceApp>, query: String): List<DeviceApp> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps
    return apps.filter {
        it.name.contains(trimmed, ignoreCase = true) ||
            it.packageName.contains(trimmed, ignoreCase = true)
    }
}

@Composable
internal fun DeviceAppsDialog(
    state: DeviceAppsState,
    onLaunch: (DeviceApp, Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onInstall: (() -> Unit)? = null,
    /** Null when the session cannot show the device's own pages; see [DeviceAppPages.availableFor]. */
    onAppPage: ((DeviceApp, DeviceAppPages.Page) -> Unit)? = null,
) {
    // Saved with the dialog: `showApps` survives rotation and process death, so resetting the
    // filter here would silently discard what the user had typed.
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(state.apps, query) { visibleApps(state.apps, query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.apps_title), Modifier.weight(1f))
                // The list is the device's answer when it was asked. Changes made on the device
                // itself, such as an app installed from its store, appear when it is asked again.
                if (state.loaded && !state.loading) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.apps_refresh))
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // A refresh that failed keeps the list it had, with the reason above it.
                if (state.loaded && state.error != null) {
                    Text(state.error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                when {
                    state.loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.heightIn(min = 24.dp))
                        Column {
                            Text(stringResource(R.string.apps_loading),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.apps_loading_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    state.error != null && !state.loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.apps_retry))
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.apps_search)) },
                            singleLine = true,
                            enabled = state.apps.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (visible.isEmpty() && !state.loaded) {
                            // Nothing has been loaded yet, so there is nothing to report as empty.
                        } else if (visible.isEmpty()) {
                            // A device that reports nothing is a different situation from a search
                            // that matched nothing, and suggesting the search is at fault would be
                            // misleading when there is no search.
                            Text(
                                stringResource(
                                    if (state.apps.isEmpty()) R.string.apps_none else R.string.apps_empty
                                ),
                                Modifier.padding(top = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            LazyColumn(
                                Modifier.heightIn(max = 320.dp).testTag("device-app-list"),
                            ) {
                                // Indexed so that a device reporting the same package twice cannot
                                // crash the list on a duplicate key.
                                itemsIndexed(visible, key = { index, app -> "$index-${app.packageName}" }) { _, app ->
                                    AppRow(
                                        app = app,
                                        onOpen = { onLaunch(app, false) },
                                        onRestart = { onLaunch(app, true) },
                                        onAppPage = onAppPage?.let { open -> { page -> open(app, page) } },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.done))
            }
        },
        // Installing sits with the apps it adds to, rather than taking another tile in the panel.
        dismissButton = onInstall?.let { install ->
            {
                TextButton(onClick = install, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.apps_install))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(
    app: DeviceApp,
    onOpen: () -> Unit,
    onRestart: () -> Unit,
    onAppPage: ((DeviceAppPages.Page) -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The whole row opens the app. The name and the package each get two lines of their own,
        // so neither is cut short by the other or by a label appended to it.
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.apps_open), onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            // The tag follows the name, and moves under it when the name fills the line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.system) SystemTag()
            }
            Text(
                wrappablePackageName(app.packageName),
                // Read and searched as written; the zero-width spaces only place the line breaks.
                Modifier.semantics { text = AnnotatedString(app.packageName) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.apps_more_actions, app.name))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Restarting a frozen app remotely otherwise means walking the device's own Settings.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.apps_restart)) },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                    onClick = { menuOpen = false; onRestart() },
                )
                if (onAppPage != null) for (page in DeviceAppPages.offered(app)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(page.label)) },
                        leadingIcon = { Icon(page.icon, contentDescription = null) },
                        onClick = { menuOpen = false; onAppPage(page) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemTag() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            stringResource(R.string.apps_system),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

private val DeviceAppPages.Page.label: Int
    get() = when (this) {
        DeviceAppPages.Page.APP_INFO -> R.string.apps_app_info
        DeviceAppPages.Page.UNINSTALL -> R.string.apps_uninstall
    }

private val DeviceAppPages.Page.icon: ImageVector
    get() = when (this) {
        DeviceAppPages.Page.APP_INFO -> Icons.Outlined.Info
        DeviceAppPages.Page.UNINSTALL -> Icons.Outlined.Delete
    }
