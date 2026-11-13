package com.scrcpy.remote.session

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec

/** What Picture & sound changes for one session: both codecs, applied together; the server is current-version metadata. */
data class StreamChoice(
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val server: ScrcpyServerVersion,
) {
    companion object {
        fun of(settings: MirrorSettings) = StreamChoice(settings.videoCodec, settings.audioCodec, settings.serverVersion)
    }
}

/** How a codec stands on the connected device, from its own encoder list when one was read. */
enum class CodecSupport { ON_DEVICE, NOT_ON_DEVICE, NOT_CHECKED }

/**
 * The rules behind Picture & sound, kept apart from its screen so they can be tested on their own.
 *
 * A choice is applied as session-only switches on top of what the device's settings say, the same
 * kind the session already uses for an encoder or a camera: nothing is saved, and choosing the
 * saved values again leaves no switch behind.
 */
internal object StreamChoices {
    /** Whether the device can send [codec] video, as far as its encoder list says. */
    fun videoSupport(codec: VideoCodec, encoders: DeviceEncodersState): CodecSupport =
        support(codec.serverValue, encoders)

    /** The same for sound. Raw PCM needs no encoder: the server sends what it captures. */
    fun audioSupport(codec: AudioCodec, encoders: DeviceEncodersState): CodecSupport =
        if (codec == AudioCodec.RAW) CodecSupport.ON_DEVICE else support(codec.serverValue, encoders)

    /**
     * The session-only switches that make [choice] run on top of [profile], keeping every other
     * session choice in [current]. Each switch remembers the saved value it replaced, so a value
     * saved on purpose later wins over it.
     */
    fun overridesFor(current: SessionOverrides, choice: StreamChoice, profile: MirrorSettings): SessionOverrides =
        current.copy(
            videoCodec = choice.videoCodec.takeIf { it != profile.videoCodec },
            videoCodecFrom = profile.videoCodec.takeIf { choice.videoCodec != it },
            audioCodec = choice.audioCodec.takeIf { it != profile.audioCodec },
            audioCodecFrom = profile.audioCodec.takeIf { choice.audioCodec != it },
        )

    private fun support(serverValue: String, encoders: DeviceEncodersState): CodecSupport = when {
        // Loading, failed or never asked: not checked, which is not the same as available.
        !encoders.loaded || encoders.loading || encoders.error != null -> CodecSupport.NOT_CHECKED
        encoders.encoders.any { it.codec.equals(serverValue, ignoreCase = true) } -> CodecSupport.ON_DEVICE
        else -> CodecSupport.NOT_ON_DEVICE
    }
}