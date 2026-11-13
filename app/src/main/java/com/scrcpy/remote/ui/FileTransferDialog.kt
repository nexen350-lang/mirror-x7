package com.scrcpy.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.session.FileTransfers
import com.scrcpy.remote.session.FileTransferState
import kotlin.math.roundToInt

/**
 * What the single transfer slot is doing: a modal dialog while a file is sent, and only a corner
 * note while the device installs an app.
 *
 * The device may ask for confirmation on its own screen while it installs, and the user answers
 * it through the mirror, which a modal dialog would block. Waiting on the package manager puts no
 * load on the connection either, so nothing is gained by holding the screen.
 */
@Composable
internal fun BoxScope.TransferProgress(
    state: FileTransferState,
    inputLocked: Boolean,
    lockBanner: InputLockBannerPlacement,
    onCancel: () -> Unit,
) {
    when {
        !state.running -> Unit
        state.installing -> InstallingIndicator(state.name, inputLocked, lockBanner, Modifier.align(Alignment.TopEnd))
        else -> FileTransferDialog(state, onCancel)
    }
}

/**
 * Progress for a file being copied to the device, including an app on its way to being installed.
 *
 * Modal on purpose: the transfer shares the session's ADB connection, so mirroring is degraded
 * while it runs, and interacting with the remote screen during it would be misleading.
 */
@Composable
internal fun FileTransferDialog(state: FileTransferState, onCancel: () -> Unit) {
    AlertDialog(
        // Dismissal has to be the explicit Cancel: tapping outside would abandon a transfer the
        // user is only waiting on.
        onDismissRequest = {},
        title = { Text(stringResource(if (state.install) R.string.install_progress_title else R.string.send_file_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    state.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val fraction = state.fraction
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                }
                // An app bundle's size is known once the device has said which of its files it needs.
                if (!state.install || state.totalBytes > 0) {
                    Text(
                        stringResource(
                            R.string.send_file_progress,
                            FileTransfers.formatSize(state.sentBytes),
                            FileTransfers.formatSize(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Says an app is being installed, while the screen stays usable for the device's own questions.
 *
 * Not interactive, so the mirror under it still takes touches. Like the recording indicator it
 * sits below the Input Lock banner rather than over it. There is no Cancel: stopping an install is
 * the device's to do, and closing the connection would only lose its answer.
 */
@Composable
internal fun InstallingIndicator(
    name: String,
    inputLocked: Boolean,
    lockBanner: InputLockBannerPlacement,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.install_indicator_description, name)
    var parentTop by remember { mutableFloatStateOf(0f) }
    Row(
        modifier
            .onPlaced { parentTop = it.parentLayoutCoordinates?.positionInRoot()?.y ?: 0f }
            .offset {
                val below = if (inputLocked) (lockBanner.bottom - parentTop).roundToInt() else 0
                IntOffset(0, below.coerceAtLeast(0))
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(8.dp)
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // Tagged before the semantics are replaced, which would otherwise clear the tag too.
            .testTag("installing-indicator")
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.install_indicator_title, name), color = Color.White,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.install_indicator_hint), color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}