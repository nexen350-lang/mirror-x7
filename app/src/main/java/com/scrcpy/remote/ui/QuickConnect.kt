package com.scrcpy.remote.ui

import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.StreamingPreset

/**
 * Per-session parameters for one-tap Quick Connect.
 *
 * Uses [StreamingPreset.BALANCED] by default, offers [StreamingPreset.MOST_RESPONSIVE] for slower
 * Wi-Fi links, and keeps advanced toggles collapsed out of the way.
 */
internal data class QuickConnectConfig(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val preset: StreamingPreset = DEFAULT_PRESET,
    val audioEnabled: Boolean = true,
    val turnScreenOff: Boolean = false,
    val stayAwake: Boolean = false,
) {
    fun validate(): String? = SettingsValidation.endpointError(host.trim(), port)

    fun toLinkOptions(): String = LinkRequest(
        preset = preset,
        audio = if (audioEnabled) LinkAudio.DEVICE else LinkAudio.OFF,
        screenOff = turnScreenOff,
        stayAwake = stayAwake,
    ).toQuery()

    companion object {
        const val DEFAULT_PORT = 5555
        val DEFAULT_PRESET = StreamingPreset.BALANCED
        val PRESET_OPTIONS = listOf(StreamingPreset.BALANCED, StreamingPreset.MOST_RESPONSIVE)
    }
}
