package com.scrcpy.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.session.AudioState
import com.scrcpy.remote.session.ConnectionQualityHistory
import com.scrcpy.remote.session.ConnectionQualitySnapshot
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.VideoFrameStats
import com.scrcpy.remote.ui.design.InsetDivider
import com.scrcpy.remote.ui.design.InsetGroup
import com.scrcpy.remote.ui.design.InsetRow

/**
 * What the session in front of the user is actually doing.
 *
 * Distinct from the diagnostics overlay, which is a developer's heads-up display drawn over the
 * video. This answers ordinary questions — which codec, how many megabits, what size, is audio
 * really on — that were previously only answerable by opening the settings and inferring, and
 * which the settings cannot answer truthfully anyway: a setting says what the *next* session will
 * use, and a link or a session-only encoder choice can make the running one differ.
 *
 * Session options come from the running session, not the saved profile. Requested settings are
 * distinguished from measured client statistics; the bit-rate request is not a bandwidth meter.
 */
@Composable
internal fun ConnectionDetailsDialog(
    address: String,
    deviceName: String?,
    settings: MirrorSettings?,
    videoWidth: Int,
    videoHeight: Int,
    frames: VideoFrameStats,
    latency: LatencyStats,
    timing: MediaTimingDiagnostics,
    recording: Boolean,
    onStatsWatched: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    history: ConnectionQualitySnapshot = ConnectionQualitySnapshot(),
    audioState: AudioState = AudioState(),
) {
    // Sampled only while something is looking, so the numbers here are live rather than frozen at
    // whatever they were when the session started.
    DisposableEffect(Unit) {
        onStatsWatched(true)
        onDispose { onStatsWatched(false) }
    }
    val audioAvailable = audioState.playing && audioState.error == null
    val summary = connectionSummary(
        address, deviceName, settings, videoWidth, videoHeight, frames, latency, timing,
        history, audioAvailable,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .testTag("connection-details"),
            ) {
                InsetGroup(caption = stringResource(R.string.details_connection)) {
                    InsetRow(title = stringResource(R.string.details_address), value = address)
                    deviceName?.let {
                        InsetDivider()
                        InsetRow(title = stringResource(R.string.details_device), value = it)
                    }
                    settings?.let {
                        InsetDivider()
                        InsetRow(title = stringResource(R.string.details_server), value = stringResource(it.serverVersion.labelRes))
                    }
                    if (recording) {
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_recording),
                            value = stringResource(R.string.details_on),
                        )
                    }
                }

                if (settings == null) {
                    // Only reachable before the first attempt has produced its settings; better
                    // than an empty dialog that looks broken.
                    Spacer(Modifier.height(18.dp))
                    Text(stringResource(R.string.details_not_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }

                Spacer(Modifier.height(18.dp))
                RecentConnectionQuality(history, settings.audioEnabled)

                Spacer(Modifier.height(18.dp))
                InsetGroup(caption = stringResource(R.string.details_video)) {
                    InsetRow(title = stringResource(R.string.details_codec),
                        value = settings.videoCodec.label)
                    InsetDivider()
                    InsetRow(
                        title = stringResource(R.string.details_encoder),
                        // The device picks one unless told otherwise, and it does not report
                        // which, so saying "chosen by the device" is the honest answer rather
                        // than inventing a name.
                        value = settings.videoEncoder ?: stringResource(R.string.details_device_default),
                    )
                    InsetDivider()
                    InsetRow(
                        title = stringResource(R.string.details_resolution),
                        value = if (videoWidth > 0 && videoHeight > 0) {
                            stringResource(R.string.details_resolution_value, videoWidth, videoHeight)
                        } else stringResource(R.string.details_pending),
                        // The requested cap, beside the size actually being sent: a device often
                        // sends something smaller, and the difference is worth seeing.
                        detail = stringResource(R.string.details_max_size, settings.maxSize),
                    )
                    InsetDivider()
                    InsetRow(
                        title = stringResource(R.string.details_bitrate),
                        value = stringResource(R.string.details_mbps, settings.videoBitRateMbps),
                        detail = stringResource(R.string.quality_requested_bitrate_help),
                    )
                    InsetDivider()
                    InsetRow(
                        title = stringResource(R.string.details_frame_rate),
                        value = if (frames.surfaceFrames > 0) {
                            stringResource(R.string.details_fps, frames.surfaceFps)
                        } else stringResource(R.string.details_pending),
                        detail = stringResource(R.string.details_frame_rate_detail,
                            if (frames.receivedFrames > 0) frames.receivedFps.toString()
                                else stringResource(R.string.quality_missing),
                            if (frames.decodedFrames > 0) frames.decodedFps.toString()
                                else stringResource(R.string.quality_missing),
                            settings.maxFps),
                    )
                    if (latency.hasSamples) {
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_latency),
                            value = stringResource(R.string.details_millis, latency.medianMillis),
                            detail = stringResource(R.string.details_latency_detail,
                                latency.p95Millis, latency.worstMillis, latency.intakeP95Millis),
                        )
                    }
                    if (frames.skippedFrames > 0) {
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_skipped),
                            value = frames.skippedFrames.toString(),
                            detail = stringResource(R.string.details_skipped_detail),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                InsetGroup(caption = stringResource(R.string.details_audio)) {
                    if (!settings.audioEnabled) {
                        InsetRow(title = stringResource(R.string.details_audio),
                            value = stringResource(R.string.details_off))
                    } else {
                        InsetRow(title = stringResource(R.string.details_codec),
                            value = settings.audioCodec.label)
                        InsetDivider()
                        InsetRow(title = stringResource(R.string.details_source),
                            value = stringResource(settings.audioSource.labelRes))
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_encoder),
                            value = settings.audioEncoder
                                ?: stringResource(R.string.details_device_default),
                        )
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_buffered),
                            value = if (audioAvailable) {
                                stringResource(R.string.details_ms, timing.audioQueuedMs)
                            } else stringResource(if (audioState.error != null) {
                                R.string.quality_audio_unavailable
                            } else R.string.details_pending),
                            detail = if (audioAvailable && timing.outputQueuedMs != null && timing.underruns > 0) {
                                pluralStringResource(R.plurals.details_underruns, timing.underruns,
                                    timing.underruns)
                            } else null,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                InsetGroup(
                    caption = stringResource(R.string.details_timing),
                    footer = stringResource(R.string.details_timing_help),
                ) {
                    InsetRow(
                        title = stringResource(R.string.details_mode),
                        value = stringResource(when (settings.syncMode) {
                            PlaybackSyncMode.LOW_LATENCY -> R.string.setting_sync_low_latency
                            PlaybackSyncMode.SYNCHRONIZED -> R.string.setting_sync_synchronized
                        }),
                    )
                    if (timing.synchronized) {
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_video_delay),
                            value = if (timing.clockReady) {
                                stringResource(R.string.details_ms, timing.videoDelayMs)
                            } else stringResource(R.string.details_pending),
                        )
                    }
                    timing.videoAheadMs?.let {
                        InsetDivider()
                        InsetRow(
                            title = stringResource(R.string.details_av_offset),
                            value = stringResource(R.string.details_ms, it),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            // The reason anyone reads these numbers is usually to tell someone else about them,
            // and an encoder name is not something to retype from a screen.
            TextButton(onClick = { onCopy(summary) }) { Text(stringResource(R.string.details_copy)) }
        },
    )
}

@Composable
private fun RecentConnectionQuality(history: ConnectionQualitySnapshot, audioEnabled: Boolean) {
    val samples = history.samples
    InsetGroup(
        modifier = Modifier.testTag("connection-quality"),
        caption = stringResource(R.string.quality_title),
        footer = stringResource(R.string.quality_method_help),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.quality_window, samples.size),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("quality-window"),
            )
            Text(stringResource(R.string.quality_collection_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (samples.isEmpty()) {
                Text(stringResource(R.string.quality_waiting),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        InsetDivider()
        QualityMetric(
            stringResource(R.string.quality_rendered_fps), history, samples.map { it.renderedFps },
            R.string.details_fps, "quality-fps",
        )
        InsetDivider()
        QualityMetric(
            stringResource(R.string.quality_client_p95), history, samples.map { it.clientP95Millis },
            R.string.details_ms, "quality-latency",
        )
        InsetDivider()
        QualityMetric(
            stringResource(R.string.quality_audio_queue), history, samples.map { it.audioQueuedMillis },
            R.string.details_ms, "quality-audio",
            missing = stringResource(if (audioEnabled) R.string.quality_missing else R.string.quality_audio_off),
        )
        InsetDivider()
        QualityMetric(
            stringResource(R.string.quality_underrun_delta), history, samples.map { it.underrunDelta },
            R.string.quality_increase, "quality-underruns",
            missing = stringResource(if (audioEnabled) R.string.quality_missing else R.string.quality_audio_off),
        )
        InsetDivider()
        Text(
            stringResource(
                R.string.quality_delta_totals,
                observedDeltaText(samples.mapNotNull { it.skippedDelta }),
                observedDeltaText(samples.mapNotNull { it.underrunDelta?.toLong() }),
            ),
            Modifier.padding(14.dp).testTag("quality-deltas"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QualityMetric(
    title: String,
    history: ConnectionQualitySnapshot,
    values: List<Int?>,
    unitResource: Int,
    tag: String,
    missing: String = stringResource(R.string.quality_missing),
) {
    val measured = values.filterNotNull()
    val summary = if (measured.isEmpty()) missing else stringResource(
        R.string.quality_metric_summary,
        stringResource(unitResource, measured.first()),
        values.lastOrNull()?.let { stringResource(unitResource, it) } ?: missing,
        stringResource(unitResource, measured.min()),
        stringResource(unitResource, measured.max()),
        measured.size,
    )
    val pointColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(summary, Modifier.testTag("$tag-summary"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (measured.isNotEmpty()) {
            // Discrete points deliberately leave missing/closed intervals blank, not interpolated.
            Canvas(Modifier.fillMaxWidth().height(32.dp).testTag("$tag-trend").clearAndSetSemantics {}) {
                val inset = 3.dp.toPx()
                val width = (size.width - 2 * inset).coerceAtLeast(0f)
                val height = (size.height - 2 * inset).coerceAtLeast(0f)
                val maximum = measured.max().coerceAtLeast(1).toFloat()
                drawLine(axisColor, Offset(inset, size.height - inset),
                    Offset(size.width - inset, size.height - inset), strokeWidth = 1.dp.toPx())
                values.forEachIndexed { index, value ->
                    if (value != null) {
                        val age = (history.elapsedMillis - history.samples[index].elapsedMillis)
                            .coerceIn(0, ConnectionQualityHistory.WINDOW_MILLIS)
                        val x = inset + width * (1f - age.toFloat() / ConnectionQualityHistory.WINDOW_MILLIS)
                        val y = inset + height * (1f - value / maximum)
                        drawCircle(pointColor, radius = 2.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
        }
    }
}

@Composable
private fun observedDeltaText(values: List<Long>): String =
    if (values.isEmpty()) stringResource(R.string.quality_missing)
    else stringResource(R.string.quality_observed_total, values.sum(), values.size)

/**
 * The same facts as one block of text, for pasting into a bug report or a message.
 *
 * Deliberately plain and stable rather than mirroring the dialog's layout: it is read by a person
 * somewhere else, with none of this on screen beside it.
 */
private fun connectionSummary(
    address: String,
    deviceName: String?,
    settings: MirrorSettings?,
    videoWidth: Int,
    videoHeight: Int,
    frames: VideoFrameStats,
    latency: LatencyStats,
    timing: MediaTimingDiagnostics,
    history: ConnectionQualitySnapshot,
    audioAvailable: Boolean,
): String = buildString {
    appendLine("scrcpy remote — connection details")
    appendLine("Address: $address")
    deviceName?.let { appendLine("Saved as: $it") }
    if (settings == null) {
        append("Not connected.")
        return@buildString
    }
    appendLine("Server: scrcpy ${settings.serverVersion.version}")
    appendLine("Video: ${settings.videoCodec.label}, " +
        "${videoWidth}x$videoHeight (max ${settings.maxSize} px)")
    appendLine("Requested video bit rate: ${settings.videoBitRateMbps} Mbps (not measured bandwidth)")
    appendLine("Video encoder: ${settings.videoEncoder ?: "device default"}")
    fun fps(value: Int, observations: Long) = if (observations > 0) "$value fps" else "not measured"
    appendLine("Frames: ${fps(frames.surfaceFps, frames.surfaceFrames)} shown, " +
        "${fps(frames.receivedFps, frames.receivedFrames)} received, " +
        "${fps(frames.decodedFps, frames.decodedFrames)} decoded, cap ${settings.maxFps}")
    appendLine("Frames skipped (session total): ${frames.skippedFrames}")
    if (latency.hasSamples) {
        appendLine("Client latency (received to shown): ${latency.medianMillis} ms median, " +
            "${latency.p95Millis} ms p95, ${latency.worstMillis} ms worst, " +
            "of which ${latency.intakeP95Millis} ms p95 before the decoder, " +
            "over ${latency.samples} frames")
    }
    appendLine("Client latency is receipt to render on this client, not network or glass-to-glass latency.")
    if (settings.audioEnabled) {
        appendLine("Audio: ${settings.audioCodec.label}, source ${settings.audioSource.serverValue}, " +
            "encoder ${settings.audioEncoder ?: "device default"}")
        if (audioAvailable) {
            appendLine("Audio buffer: ${timing.audioQueuedMs} ms")
            val underruns = if (timing.outputQueuedMs != null) timing.underruns.toString() else "not measured"
            appendLine("Audio underruns (session counter): $underruns")
            appendLine("Audio dropped frames (session total): ${timing.droppedFrames}")
        } else {
            appendLine("Audio buffer: not measured (playback unavailable)")
        }
    } else {
        appendLine("Audio: off")
    }
    append("Timing: ${if (timing.synchronized) "synchronized" else "low latency"}")
    if (timing.synchronized && timing.clockReady) append(", video held ${timing.videoDelayMs} ms")
    timing.videoAheadMs?.let { append(", video ahead ${it} ms") }
    appendLine()
    appendLine()
    appendLine("Recent quality — last 60 s, at most 120 samples")
    appendLine("Collected only while Connection details is open and the controller is visible.")
    appendLine("${history.samples.size} retained samples. Reopening keeps unexpired points; closed/hidden gaps are not measurements.")
    appendQualityMetric("Rendered FPS", history.samples.map { it.renderedFps }, "fps")
    appendQualityMetric("Client p95 latency", history.samples.map { it.clientP95Millis }, "ms")
    appendQualityMetric("Audio queue", history.samples.map { it.audioQueuedMillis }, "ms")
    appendQualityMetric("Underrun increase", history.samples.map { it.underrunDelta }, "per sampled interval")
    fun total(values: List<Long>): String =
        if (values.isEmpty()) "not measured" else "${values.sum()} across ${values.size} observed intervals"
    appendLine("Skipped increases (observed intervals, not session total): " +
        total(history.samples.mapNotNull { it.skippedDelta }))
    appendLine("Underrun increases (observed intervals, not session total): " +
        total(history.samples.mapNotNull { it.underrunDelta?.toLong() }))
    appendLine("Deltas require adjacent samples no more than 1 s apart; gaps are not interpolated.")
    appendLine("FPS uses the existing 1 s rendered window; client p95 uses the existing recent frame window.")
    append("Quality history stays in memory only: no persistence or upload, no addresses or clipboard contents stored.")
}

private fun StringBuilder.appendQualityMetric(label: String, values: List<Int?>, unit: String) {
    val measured = values.filterNotNull()
    if (measured.isEmpty()) {
        appendLine("$label: not measured")
    } else {
        val latest = values.lastOrNull()?.let { "$it $unit" } ?: "not measured"
        appendLine("$label: first ${measured.first()} $unit; latest $latest; " +
            "range ${measured.min()}–${measured.max()} $unit (${measured.size} measured samples)")
    }
}
