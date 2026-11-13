package com.scrcpy.remote.session

import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsValidation

/** Resolve once per attempt, not once per Activity lifetime; never mutate persisted preferences. */
class ConnectionRequest(
    val host: String,
    val port: Int,
    /** The saved device this connects to, whose connection history it adds to; null for an unsaved address. */
    val deviceId: String? = null,
    /** The link that opened the session asked for options of its own, which saved settings do not have. */
    val linkOptions: Boolean = false,
    private val settingsProvider: () -> MirrorSettings,
) {
    fun snapshot(enableAudio: Boolean = false): MirrorSettings {
        val settings = settingsProvider()
        require(SettingsValidation.settingsErrors(settings).isEmpty()) { "Invalid connection settings" }
        return if (enableAudio) settings.copy(audioEnabled = true) else settings
    }
}

/** Playback and reconnect policies belong to an attempt's snapshot; only local input changes are live. */
fun MirrorSettings.requiresReconnectFrom(active: MirrorSettings): Boolean =
    DeviceMirroringSettings.from(this).copy(captureEdgeGestures = false, backgroundAudio = false) !=
        DeviceMirroringSettings.from(active).copy(captureEdgeGestures = false, backgroundAudio = false)
