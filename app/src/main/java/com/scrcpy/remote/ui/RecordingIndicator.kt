package com.scrcpy.remote.ui

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.session.RecordingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * A small mark that a recording is running, with how long it has run and whether it has sound.
 *
 * The Stop button only shows with the controls, so hiding them used to leave nothing on screen to
 * say a recording was still being made. Not interactive, so the screen under it stays touchable,
 * and it sits below the Input Lock banner rather than over it.
 */
@Composable
internal fun RecordingIndicator(
    status: RecordingStatus,
    inputLocked: Boolean,
    lockBanner: InputLockBannerPlacement,
    modifier: Modifier = Modifier,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    val seconds by produceState(elapsedSeconds(status, clock), status) {
        while (true) {
            value = elapsedSeconds(status, clock)
            // Woken on the next whole second rather than every second from now, so the count
            // never lags a second behind.
            delay(1_000 - (clock() - status.startedAtElapsedMs).mod(1_000L))
        }
    }
    val time = DateUtils.formatElapsedTime(seconds)
    val description = stringResource(
        if (status.withSound) R.string.recording_indicator_with_sound else R.string.recording_indicator_picture_only,
        time,
    )
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
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            // Tagged before the semantics are replaced, which would otherwise clear the tag too.
            .testTag("recording-indicator")
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(RECORDING_RED, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(time, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Icon(
            if (status.withSound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun elapsedSeconds(status: RecordingStatus, clock: () -> Long): Long =
    ((clock() - status.startedAtElapsedMs) / 1_000).coerceAtLeast(0)

/**
 * How long a finished recording runs and whether it has sound, read from the file itself.
 *
 * Read from the file rather than remembered, so a recording offered again after Android ended
 * the app is described the same way, and the description is what will actually play.
 */
@Composable
internal fun RecordingSummary(file: File, modifier: Modifier = Modifier) {
    val summary by produceState<Pair<Long, Boolean>?>(null, file) {
        value = withContext(Dispatchers.IO) { readSummary(file) }
    }
    summary?.let { (durationMs, sound) ->
        Text(
            stringResource(
                if (sound) R.string.recording_summary_with_sound else R.string.recording_summary_picture_only,
                DateUtils.formatElapsedTime(durationMs / 1_000),
            ),
            modifier.testTag("recording-summary"),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private fun readSummary(file: File): Pair<Long, Boolean>? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: return@runCatching null
        duration to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
    } finally {
        retriever.release()
    }
}.getOrNull()

private val RECORDING_RED = Color(0xFFE53935)

/**
 * Asked when Record is pressed in a session whose sound an MP4 cannot hold.
 *
 * Nothing changes until the user answers: reconnecting is their choice, and recording the
 * picture alone is offered beside it rather than happening silently.
 */
@Composable
internal fun RecordSoundChoiceDialog(
    codec: AudioCodec,
    onReconnect: () -> Unit,
    onPictureOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.recording_sound_title)) },
        text = { Text(stringResource(R.string.recording_sound_body, codec.label)) },
        confirmButton = {
            Button(onClick = onReconnect) { Text(stringResource(R.string.recording_sound_reconnect)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = onPictureOnly) { Text(stringResource(R.string.recording_sound_picture_only)) }
            }
        },
    )
}
