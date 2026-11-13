package com.scrcpy.remote.session

import androidx.annotation.StringRes
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.AudioCodec

/** The running recording, for the screen: when it started and whether it takes sound. */
data class RecordingStatus(val startedAtElapsedMs: Long, val withSound: Boolean)

/** What pressing Record does about the session's sound. */
internal sealed interface RecordingSound {
    /** The session's sound is AAC and running: it is recorded with the picture. */
    data object WithSound : RecordingSound

    /** The sound is running in a codec an MP4 cannot hold, so the user has to choose. */
    data class NeedsAac(val codec: AudioCodec) : RecordingSound

    /** There is no sound to record: switched off for the session, or not running. */
    data class PictureOnly(val soundOff: Boolean) : RecordingSound

    companion object {
        /**
         * [soundRequested] is whether the session asked for sound at all; [soundRunning] whether
         * its sound stream is working now; [codec] what it is coded in.
         */
        fun decide(soundRequested: Boolean, soundRunning: Boolean, codec: AudioCodec): RecordingSound = when {
            !soundRequested -> PictureOnly(soundOff = true)
            !soundRunning -> PictureOnly(soundOff = false)
            codec != AudioCodec.AAC -> NeedsAac(codec)
            else -> WithSound
        }

        /**
         * The notice for a recording that ended for a reason about its sound, or null for any
         * other reason. [kept] is whether anything was written: a recording whose sound stopped
         * before its file opened must not be described as kept.
         */
        @StringRes
        fun stopNotice(reason: String?, kept: Boolean): Int? = when (reason) {
            SessionRecorder.AUDIO_STOPPED ->
                if (kept) R.string.recording_stopped_sound else R.string.recording_sound_stopped_empty
            SessionRecorder.AUDIO_TIMELINE -> R.string.recording_sound_timeline
            SessionRecorder.AUDIO_UNSUPPORTED -> R.string.recording_sound_unsupported
            else -> null
        }
    }
}
