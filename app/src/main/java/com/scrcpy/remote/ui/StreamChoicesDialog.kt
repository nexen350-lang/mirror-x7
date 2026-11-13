package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.CodecSupport
import com.scrcpy.remote.session.DeviceEncodersState
import com.scrcpy.remote.session.StreamChoice
import com.scrcpy.remote.session.StreamChoices
import com.scrcpy.remote.ui.design.InsetDivider
import com.scrcpy.remote.ui.design.InsetGroup
import com.scrcpy.remote.ui.design.InsetSelectRow

/**
 * More controls > Picture & sound: the session's video and sound codecs.
 *
 * Changing a selection does nothing by itself; Apply and reconnect applies all of them in one
 * restart, and only when something differs from what runs. The choice is for this session only,
 * as links and one-off addresses need, and leaves the device's saved settings as they are.
 *
 * The device's own encoder lists narrow the codecs: one it lacks cannot be chosen, and when the
 * lists could not be read the sheet says so rather than implying every codec will work.
 */
@Composable
internal fun StreamChoicesDialog(
    active: StreamChoice,
    audioEnabled: Boolean,
    videoEncoders: DeviceEncodersState,
    audioEncoders: DeviceEncodersState,
    recording: Boolean,
    onApply: (StreamChoice) -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
    supportedVideoCodecs: List<VideoCodec> = remember { VideoCodec.supported() },
    supportedAudioCodecs: List<AudioCodec> = remember { AudioCodec.supported() },
) {
    var video by rememberSaveable(active) { mutableStateOf(active.videoCodec) }
    var audio by rememberSaveable(active) { mutableStateOf(active.audioCodec) }
    // A codec tapped while the lists were loading, which they then show the device lacks, goes back
    // to the running one: the sheet never applies a codec it says the device cannot send.
    val videoMissing = video != active.videoCodec &&
        StreamChoices.videoSupport(video, videoEncoders) == CodecSupport.NOT_ON_DEVICE
    val audioMissing = audioEnabled && audio != active.audioCodec &&
        StreamChoices.audioSupport(audio, audioEncoders) == CodecSupport.NOT_ON_DEVICE
    LaunchedEffect(videoMissing, audioMissing) {
        if (videoMissing) video = active.videoCodec
        if (audioMissing) audio = active.audioCodec
    }
    // With sound off there is no sound codec to change, so the running one is carried unchanged.
    val pending = StreamChoice(video, if (audioEnabled) audio else active.audioCodec, ScrcpyServerVersion.DEFAULT)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stream_choices_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                    .testTag("stream-choices"),
            ) {
                Text(stringResource(R.string.stream_choices_running, streamChoiceSummary(active, audioEnabled)),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.stream_choices_session_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recording) {
                    Text(stringResource(R.string.stream_choices_recording),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                EncoderListStatus(videoEncoders, audioEncoders, audioEnabled, onCheckAgain)

                Spacer(Modifier.height(16.dp))
                InsetGroup(caption = stringResource(R.string.stream_choices_video)) {
                    supportedVideoCodecs.forEachIndexed { index, codec ->
                        if (index > 0) InsetDivider()
                        val support = StreamChoices.videoSupport(codec, videoEncoders)
                        InsetSelectRow(
                            title = codec.label,
                            detail = supportLabel(codec == active.videoCodec, support),
                            selected = video == codec,
                            // The running codec stays choosable: it is the way back from another.
                            enabled = codec == active.videoCodec || support != CodecSupport.NOT_ON_DEVICE,
                            onClick = { video = codec },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (audioEnabled) {
                    InsetGroup(caption = stringResource(R.string.stream_choices_audio)) {
                        supportedAudioCodecs.forEachIndexed { index, codec ->
                            if (index > 0) InsetDivider()
                            val support = StreamChoices.audioSupport(codec, audioEncoders)
                            InsetSelectRow(
                                title = codec.label,
                                detail = supportLabel(codec == active.audioCodec, support),
                                selected = audio == codec,
                                enabled = codec == active.audioCodec || support != CodecSupport.NOT_ON_DEVICE,
                                onClick = { audio = codec },
                            )
                        }
                    }
                } else {
                    Text(stringResource(R.string.stream_choices_audio_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(pending) }, enabled = pending != active && !videoMissing && !audioMissing,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stream_choices_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** "H.264 · Opus · scrcpy 5.0", or without the sound codec when the session has no sound. */
@Composable
internal fun streamChoiceSummary(choice: StreamChoice, audioEnabled: Boolean = true): String =
    if (audioEnabled) {
        stringResource(R.string.stream_choice_summary, choice.videoCodec.label, choice.audioCodec.label, choice.server.version)
    } else {
        stringResource(R.string.stream_choice_summary_silent, choice.videoCodec.label, choice.server.version)
    }

@Composable
private fun supportLabel(inUse: Boolean, support: CodecSupport): String = stringResource(
    when {
        inUse -> R.string.codec_support_running
        support == CodecSupport.ON_DEVICE -> R.string.codec_support_on_device
        support == CodecSupport.NOT_ON_DEVICE -> R.string.codec_support_not_on_device
        else -> R.string.codec_support_not_checked
    },
)

/** Whether the device's encoder lists are being read, or could not be, with the way to ask again. */
@Composable
private fun EncoderListStatus(
    video: DeviceEncodersState,
    audio: DeviceEncodersState,
    audioEnabled: Boolean,
    onCheckAgain: () -> Unit,
) {
    val lists = if (audioEnabled) listOf(video, audio) else listOf(video)
    when {
        lists.any { it.loading } -> Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.stream_choices_checking), style = MaterialTheme.typography.bodySmall)
        }
        lists.any { it.error != null } -> Column {
            Text(stringResource(R.string.stream_choices_unchecked), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onCheckAgain, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.stream_choices_check_again))
            }
        }
    }
}