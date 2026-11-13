package com.scrcpy.remote.session

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation

/**
 * Settings the user changed for the session in front of them, without saving them anywhere.
 *
 * An encoder name only means something on the device that reported it, so the natural place to
 * keep a choice is that device's saved profile. But a session can be running against an address
 * nobody saved — a `scrcpy://` link, or a one-off connection — and there the choice had nowhere
 * to go, so the encoder list was shown greyed out and the user could look at the encoders but
 * not pick one. That is the dead end this removes.
 *
 * Deliberately not written back. It is remembered across a reconnect, because applying an encoder
 * *is* a reconnect and it would otherwise be undone by the very act of taking effect, and it is
 * dropped when the session ends.
 */
internal data class SessionOverrides(
    val videoEncoder: Choice<String?>? = null,
    val audioEncoder: Choice<String?>? = null,
    /**
     * A camera chosen for this session only.
     *
     * The camera use that matters most — a phone left somewhere watching something — is the one
     * least likely to have a saved profile: it is reached by a link, or by an address typed once.
     * Without this, such a session could list the device's cameras and then pick none of them.
     */
    val camera: Choice<CameraCapture?>? = null,
    val display: SessionDisplay? = null,
    /**
     * A sound codec for this session only.
     *
     * Recording with sound needs AAC, the only sound Android's MP4 writer takes. A session
     * running Opus — the default — can switch for itself when the user asks to record, without
     * rewriting a profile or a link that chose otherwise.
     */
    val audioCodec: AudioCodec? = null,
    /**
     * The profile codec [audioCodec] replaced, when known.
     *
     * The switch answers one question about the profile as it was. Once the saved codec is
     * changed on purpose, that choice wins: the switch stops applying, so the screen can say
     * the saved settings differ, and the next reconnect runs them.
     */
    val audioCodecFrom: AudioCodec? = null,
    /**
     * Sound kept on the device or not, for this session only.
     *
     * Android 13 refuses to send sound it keeps playing on the device. Hearing it here instead is a
     * choice for the session in front of the user: the saved device keeps its setting, which
     * Android 14 and newer honour.
     */
    val audioDup: Boolean? = null,
    /**
     * A video codec chosen for this session only, in Picture & sound.
     *
     * Follows [audioCodec]'s rule: it answers for the profile as it was, so once the saved codec is
     * changed on purpose, that choice wins and the switch stops applying.
     */
    val videoCodec: com.scrcpy.remote.scrcpy.VideoCodec? = null,
    /** The profile codec [videoCodec] replaced, when known. */
    val videoCodecFrom: com.scrcpy.remote.scrcpy.VideoCodec? = null,
    /**
     * A sound source chosen from the mirror's controls, for this session only. The saved device
     * keeps its own; the switch lasts until the session ends.
     */
    val audioSource: com.scrcpy.remote.scrcpy.AudioSource? = null,
) {
    /**
     * A value that was chosen, as distinct from one that was not.
     *
     * "Let the device choose" is a real choice whose value is null, so a bare nullable cannot
     * express it: it would be indistinguishable from having chosen nothing.
     */
    data class Choice<T>(val value: T)

    val isEmpty: Boolean get() = this == EMPTY

    fun applyTo(settings: MirrorSettings): MirrorSettings {
        var result = settings
        videoCodec?.takeIf { videoCodecFrom == null || result.videoCodec == videoCodecFrom }?.let { codec ->
            // As for sound below: the profile's encoder was listed for the profile's codec.
            if (codec != result.videoCodec) result = result.copy(videoCodec = codec, videoEncoder = null)
        }
        videoEncoder?.let { result = result.copy(videoEncoder = it.value) }
        audioCodec?.takeIf { appliesTo(result.audioCodec) }?.let { codec ->
            // The profile's encoder was chosen for the profile's codec, and the device refuses a
            // pair that does not match; an encoder chosen for this codec is applied below.
            if (codec != result.audioCodec) result = result.copy(audioCodec = codec, audioEncoder = null)
        }
        audioEncoder?.let { result = result.copy(audioEncoder = it.value) }
        audioDup?.let { result = result.copy(audioDup = it) }
        audioSource?.let { source ->
            // Keeping sound on the device belongs to playback capture alone.
            result = result.copy(audioSource = source,
                audioDup = result.audioDup && source == com.scrcpy.remote.scrcpy.AudioSource.PLAYBACK)
        }
        result = when (val selected = display.takeIf { camera?.value == null }) {
            is SessionDisplay.Physical -> result.copy(
                displayId = selected.id, virtualDisplay = null, camera = null,
            )
            is SessionDisplay.Desktop -> result.copy(
                displayId = 0,
                virtualDisplay = selected.spec,
                camera = null,
                captureOrientation = CaptureOrientation.FOLLOW_DEVICE,
                turnScreenOff = false,
                powerOffOnClose = false,
            )
            null -> result
        }
        camera?.let { result = result.copy(camera = it.value) }
        return result
    }

    /**
     * Drops an encoder that no longer matches the codec it was chosen for, and a session switch
     * whose saved value has since been changed on purpose.
     *
     * The device refuses a mismatched codec/encoder pair outright, which reaches the user only as
     * a session that will not start. Comparing against [previous] is what tells a real codec
     * change from an ordinary reconnect.
     */
    fun clearStaleEncoders(previous: MirrorSettings?, next: MirrorSettings): SessionOverrides {
        if (previous == null) return this
        val keepCodec = audioCodec != null && appliesTo(next.audioCodec)
        val keepVideoCodec = videoCodec != null && (videoCodecFrom == null || next.videoCodec == videoCodecFrom)
        return copy(
            // Compared with the codec the session will actually run, as for sound below.
            videoEncoder = videoEncoder?.takeIf {
                previous.videoCodec == (if (keepVideoCodec) videoCodec else next.videoCodec)
            },
            videoCodec = videoCodec.takeIf { keepVideoCodec },
            videoCodecFrom = videoCodecFrom.takeIf { keepVideoCodec },
            audioCodec = audioCodec.takeIf { keepCodec },
            audioCodecFrom = audioCodecFrom.takeIf { keepCodec },
            // Compared with the codec the session will actually run, which a codec chosen for
            // the session decides over the profile's.
            audioEncoder = audioEncoder?.takeIf {
                previous.audioCodec == (if (keepCodec) audioCodec else next.audioCodec)
            },
        )
    }

    /** Whether the session's own sound codec still answers for a profile on [profileCodec]. */
    private fun appliesTo(profileCodec: AudioCodec): Boolean = audioCodecFrom == null || profileCodec == audioCodecFrom

    /**
     * The switch to AAC that recording with sound asks for, on top of [profile].
     *
     * Like Picture & sound's switches it answers for the profile's own codec, not for whatever runs
     * now. Recorded against a codec chosen in Picture & sound, itself a switch from the profile, it
     * never matched the profile again: the reconnect dropped it and ran the profile's codec, and the
     * recording then waited for sound it could not take.
     */
    fun withAacForRecording(profile: MirrorSettings): SessionOverrides = copy(
        audioCodec = AudioCodec.AAC.takeIf { it != profile.audioCodec },
        audioCodecFrom = profile.audioCodec.takeIf { it != AudioCodec.AAC },
    )

    companion object {
        val EMPTY = SessionOverrides()
    }
}
