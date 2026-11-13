package com.scrcpy.remote.data

import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import kotlinx.serialization.Serializable

/**
 * An explicit customized configuration, independent from the separate orientation calibrations.
 * A null SavedDevice.mirroringSettings means inheritance, not a snapshot of today's globals.
 */
@Serializable
data class DeviceMirroringSettings(
    val maxSize: Int = 1600,
    val videoBitRateMbps: Int = 8,
    val maxFps: Int = 60,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val captureOrientation: CaptureOrientation = CaptureOrientation.FOLLOW_DEVICE,
    val videoEncoder: String? = null,
    val audioEnabled: Boolean = true,
    val audioCodec: AudioCodec = AudioCodec.OPUS,
    val audioEncoder: String? = null,
    val audioSource: AudioSource = AudioSource.OUTPUT,
    val audioDup: Boolean = false,
    val backgroundAudio: Boolean = false,
    val stayAwake: Boolean = false,
    val keepActive: Boolean = false,
    val showTouches: Boolean = false,
    val powerOffOnClose: Boolean = false,
    val turnScreenOff: Boolean = false,
    val virtualDisplay: String? = null,
    val displayId: Int = 0,
    val startApp: String? = null,
    val captureEdgeGestures: Boolean = true,
    val syncMode: PlaybackSyncMode = PlaybackSyncMode.LOW_LATENCY,
    val audioBufferMs: Int = 0,
    val syncOffsetMs: Int = 0,
    val autoReconnect: Boolean = false,
    val audioBitRateKbps: Int = 128,
    val clipboardAutosync: Boolean = true,
    val camera: com.scrcpy.remote.scrcpy.CameraCapture? = null,
    val serverVersion: com.scrcpy.remote.scrcpy.ScrcpyServerVersion = com.scrcpy.remote.scrcpy.ScrcpyServerVersion.DEFAULT,
) {
    fun applyTo(defaults: MirrorSettings): MirrorSettings = defaults.copy(
        maxSize = maxSize, videoBitRateMbps = videoBitRateMbps, maxFps = maxFps,
        videoCodec = videoCodec, audioEnabled = audioEnabled, audioCodec = audioCodec,
        captureOrientation = captureOrientation,
        videoEncoder = videoEncoder,
        audioEncoder = audioEncoder,
        audioSource = audioSource, audioDup = audioDup, backgroundAudio = backgroundAudio,
        stayAwake = stayAwake, keepActive = keepActive, showTouches = showTouches,
        powerOffOnClose = powerOffOnClose, turnScreenOff = turnScreenOff,
        virtualDisplay = virtualDisplay,
        displayId = displayId,
        startApp = startApp,
        captureEdgeGestures = captureEdgeGestures,
        syncMode = syncMode, audioBufferMs = audioBufferMs, syncOffsetMs = syncOffsetMs,
        autoReconnect = autoReconnect,
        audioBitRateKbps = audioBitRateKbps,
        clipboardAutosync = clipboardAutosync,
        camera = camera,
        serverVersion = serverVersion,
    )

    /**
     * Merge only edited fields into the latest profile, not a stale dialog's whole snapshot.
     *
     * A codec and its encoder, and the capture source (display, virtual display or camera), are
     * single choices spread over several fields. Merging their fields one by one could pair one
     * editor's codec with another's encoder, which the device refuses, so a group an editor
     * touched is taken from that editor whole.
     */
    fun mergeChanges(baseline: DeviceMirroringSettings, edited: DeviceMirroringSettings): DeviceMirroringSettings {
        val merged = fieldwiseMerge(baseline, edited)
        val videoEdited = baseline.videoCodec != edited.videoCodec || baseline.videoEncoder != edited.videoEncoder
        val audioEdited = baseline.audioCodec != edited.audioCodec || baseline.audioEncoder != edited.audioEncoder
        val sourceEdited = baseline.displayId != edited.displayId ||
            baseline.virtualDisplay != edited.virtualDisplay || baseline.camera != edited.camera
        return merged.copy(
            videoCodec = if (videoEdited) edited.videoCodec else videoCodec,
            videoEncoder = if (videoEdited) edited.videoEncoder else videoEncoder,
            audioCodec = if (audioEdited) edited.audioCodec else audioCodec,
            audioEncoder = if (audioEdited) edited.audioEncoder else audioEncoder,
            displayId = if (sourceEdited) edited.displayId else displayId,
            virtualDisplay = if (sourceEdited) edited.virtualDisplay else virtualDisplay,
            camera = if (sourceEdited) edited.camera else camera,
        )
    }

    private fun fieldwiseMerge(baseline: DeviceMirroringSettings, edited: DeviceMirroringSettings) = copy(
        maxSize = if (baseline.maxSize != edited.maxSize) edited.maxSize else maxSize,
        videoBitRateMbps = if (baseline.videoBitRateMbps != edited.videoBitRateMbps) edited.videoBitRateMbps else videoBitRateMbps,
        maxFps = if (baseline.maxFps != edited.maxFps) edited.maxFps else maxFps,
        videoCodec = if (baseline.videoCodec != edited.videoCodec) edited.videoCodec else videoCodec,
        captureOrientation = if (baseline.captureOrientation != edited.captureOrientation) edited.captureOrientation else captureOrientation,
        videoEncoder = if (baseline.videoEncoder != edited.videoEncoder) edited.videoEncoder else videoEncoder,
        audioEnabled = if (baseline.audioEnabled != edited.audioEnabled) edited.audioEnabled else audioEnabled,
        audioCodec = if (baseline.audioCodec != edited.audioCodec) edited.audioCodec else audioCodec,
        audioEncoder = if (baseline.audioEncoder != edited.audioEncoder) edited.audioEncoder else audioEncoder,
        audioSource = if (baseline.audioSource != edited.audioSource) edited.audioSource else audioSource,
        audioDup = if (baseline.audioDup != edited.audioDup) edited.audioDup else audioDup,
        backgroundAudio = if (baseline.backgroundAudio != edited.backgroundAudio) edited.backgroundAudio else backgroundAudio,
        stayAwake = if (baseline.stayAwake != edited.stayAwake) edited.stayAwake else stayAwake,
        keepActive = if (baseline.keepActive != edited.keepActive) edited.keepActive else keepActive,
        showTouches = if (baseline.showTouches != edited.showTouches) edited.showTouches else showTouches,
        powerOffOnClose = if (baseline.powerOffOnClose != edited.powerOffOnClose) edited.powerOffOnClose else powerOffOnClose,
        turnScreenOff = if (baseline.turnScreenOff != edited.turnScreenOff) edited.turnScreenOff else turnScreenOff,
        virtualDisplay = if (baseline.virtualDisplay != edited.virtualDisplay) edited.virtualDisplay else virtualDisplay,
        displayId = if (baseline.displayId != edited.displayId) edited.displayId else displayId,
        startApp = if (baseline.startApp != edited.startApp) edited.startApp else startApp,
        captureEdgeGestures = if (baseline.captureEdgeGestures != edited.captureEdgeGestures) edited.captureEdgeGestures else captureEdgeGestures,
        syncMode = if (baseline.syncMode != edited.syncMode) edited.syncMode else syncMode,
        audioBufferMs = if (baseline.audioBufferMs != edited.audioBufferMs) edited.audioBufferMs else audioBufferMs,
        syncOffsetMs = if (baseline.syncOffsetMs != edited.syncOffsetMs) edited.syncOffsetMs else syncOffsetMs,
        autoReconnect = if (baseline.autoReconnect != edited.autoReconnect) edited.autoReconnect else autoReconnect,
        audioBitRateKbps = if (baseline.audioBitRateKbps != edited.audioBitRateKbps) edited.audioBitRateKbps else audioBitRateKbps,
        clipboardAutosync = if (baseline.clipboardAutosync != edited.clipboardAutosync) edited.clipboardAutosync else clipboardAutosync,
        camera = if (baseline.camera != edited.camera) edited.camera else camera,
        serverVersion = if (baseline.serverVersion != edited.serverVersion) edited.serverVersion else serverVersion,
    )

    companion object {
        /**
         * Named rather than positional: every field here has a sibling of the same type, so a
         * field added in the middle of the constructor would otherwise shift the rest by one and
         * silently map, for example, one boolean setting onto another.
         */
        fun from(settings: MirrorSettings) = DeviceMirroringSettings(
            maxSize = settings.maxSize,
            videoBitRateMbps = settings.videoBitRateMbps,
            maxFps = settings.maxFps,
            videoCodec = settings.videoCodec,
            captureOrientation = settings.captureOrientation,
            videoEncoder = settings.videoEncoder,
            audioEnabled = settings.audioEnabled,
            audioCodec = settings.audioCodec,
            audioEncoder = settings.audioEncoder,
            audioSource = settings.audioSource,
            audioDup = settings.audioDup,
            backgroundAudio = settings.backgroundAudio,
            stayAwake = settings.stayAwake,
            keepActive = settings.keepActive,
            showTouches = settings.showTouches,
            powerOffOnClose = settings.powerOffOnClose,
            turnScreenOff = settings.turnScreenOff,
            virtualDisplay = settings.virtualDisplay,
            displayId = settings.displayId,
            startApp = settings.startApp,
            captureEdgeGestures = settings.captureEdgeGestures,
            syncMode = settings.syncMode,
            audioBufferMs = settings.audioBufferMs,
            syncOffsetMs = settings.syncOffsetMs,
            autoReconnect = settings.autoReconnect,
            audioBitRateKbps = settings.audioBitRateKbps,
            clipboardAutosync = settings.clipboardAutosync,
            camera = settings.camera,
            serverVersion = settings.serverVersion,
        )
    }
}

fun SavedDevice?.effectiveSettings(defaults: MirrorSettings): MirrorSettings =
    this?.mirroringSettings?.applyTo(defaults) ?: defaults
