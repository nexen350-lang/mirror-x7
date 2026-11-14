package com.scrcpy.remote.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.session.TouchDiagnostics
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.VideoFrameStats

/** Non-interactive instrumentation; transport completion is not a remote injection acknowledgement. */
@Composable
fun TouchDiagnosticsOverlay(
    diagnostics: TouchDiagnostics,
    layout: VideoLayout?,
    videoWidth: Int,
    videoHeight: Int,
    modifier: Modifier = Modifier,
    timing: MediaTimingDiagnostics = MediaTimingDiagnostics(),
    video: VideoFrameStats = VideoFrameStats(),
    videoOffsetX: Float = 0f,
    videoOffsetY: Float = 0f,
) {
    Box(modifier.fillMaxSize()) {
        if (layout?.isValid == true &&
            diagnostics.lastScreenWidth == videoWidth && diagnostics.lastScreenHeight == videoHeight
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = videoOffsetX + layout.contentLeft + (diagnostics.lastDeviceX + 0.5f) * layout.scaleX
                val y = videoOffsetY + layout.contentTop + (diagnostics.lastDeviceY + 0.5f) * layout.scaleY
                val color = if (diagnostics.written) Color.Green else Color.Yellow
                val arm = 12.dp.toPx()
                drawLine(color, Offset(x - arm, y), Offset(x + arm, y), 1.dp.toPx())
                drawLine(color, Offset(x, y - arm), Offset(x, y + arm), 1.dp.toPx())
                drawCircle(Color.Cyan, 4.dp.toPx(),
                    Offset(videoOffsetX + diagnostics.lastRawX, videoOffsetY + diagnostics.lastRawY),
                    style = Stroke(1.dp.toPx()))
                drawRect(Color.White.copy(alpha = 0.4f),
                    Offset(videoOffsetX + layout.contentLeft, videoOffsetY + layout.contentTop),
                    Size(layout.contentWidth, layout.contentHeight), style = Stroke(1.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(8.dp)) {
            DiagnosticLine(stringResource(R.string.diagnostics_session,
                diagnostics.captureGeneration, videoWidth, videoHeight))
            DiagnosticLine(stringResource(R.string.diagnostics_decoder,
                diagnostics.decodedWidth, diagnostics.decodedHeight))
            DiagnosticLine(stringResource(R.string.video_frame_rates, video.receivedFps, video.decodedFps, video.surfaceFps))
            DiagnosticLine(stringResource(R.string.video_frames_skipped, video.skippedFrames))
            if (layout?.isValid == true) DiagnosticLine(stringResource(R.string.diagnostics_rect,
                layout.contentLeft.toInt(), layout.contentTop.toInt(),
                layout.contentWidth.toInt(), layout.contentHeight.toInt()))
            DiagnosticLine(stringResource(R.string.diagnostics_touch,
                diagnostics.lastRawX, diagnostics.lastRawY, diagnostics.lastDeviceX, diagnostics.lastDeviceY))
            DiagnosticLine(stringResource(if (diagnostics.written) R.string.diagnostics_written else R.string.diagnostics_pending))
            if (diagnostics.sizeMismatch(videoWidth, videoHeight)) {
                DiagnosticLine(stringResource(R.string.diagnostics_mismatch), Color.Yellow)
            }
            DiagnosticLine(stringResource(R.string.timing_audio_queue,
                timing.audioQueuedMs, timing.droppedFrames, timing.underruns))
            timing.outputQueuedMs?.let {
                DiagnosticLine(stringResource(R.string.timing_output_queue, it))
            }
            timing.videoAheadMs?.let {
                DiagnosticLine(stringResource(R.string.timing_av_delta, it))
            }
            timing.outputRoute?.let { route ->
                DiagnosticLine(stringResource(when (route) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.timing_route_speaker
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.string.timing_route_bluetooth
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.timing_route_wired
                    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.timing_route_usb
                    else -> R.string.timing_route_other
                }))
            }
            DiagnosticLine(when {
                !timing.synchronized -> stringResource(R.string.timing_low_latency)
                timing.clockReady -> stringResource(R.string.timing_synchronized, timing.videoDelayMs)
                else -> stringResource(R.string.timing_sync_waiting)
            })
        }
    }
}

@Composable
private fun DiagnosticLine(text: String, color: Color = Color.White) {
    Text(text, color = color, style = MaterialTheme.typography.labelSmall)
}
