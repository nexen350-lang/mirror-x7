package com.scrcpy.remote.ui

import android.content.Intent
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.StreamingPreset
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec

/** What a link may ask for on the audio stream. */
internal enum class LinkAudio(val token: String, val enabled: Boolean, val source: AudioSource?) {
    OFF("off", false, null),
    DEVICE("device", true, AudioSource.OUTPUT),
    MICROPHONE("mic", true, AudioSource.MIC),
    PLAYBACK("playback", true, AudioSource.PLAYBACK),
    // App 3.1: the extended sources, named as the server names them.
    MIC_UNPROCESSED("mic-unprocessed", true, AudioSource.MIC_UNPROCESSED),
    MIC_CAMCORDER("mic-camcorder", true, AudioSource.MIC_CAMCORDER),
    MIC_VOICE_RECOGNITION("mic-voice-recognition", true, AudioSource.MIC_VOICE_RECOGNITION),
    MIC_VOICE_COMMUNICATION("mic-voice-communication", true, AudioSource.MIC_VOICE_COMMUNICATION),
    VOICE_PERFORMANCE("voice-performance", true, AudioSource.VOICE_PERFORMANCE),
    VOICE_CALL("voice-call", true, AudioSource.VOICE_CALL),
    VOICE_CALL_UPLINK("voice-call-uplink", true, AudioSource.VOICE_CALL_UPLINK),
    VOICE_CALL_DOWNLINK("voice-call-downlink", true, AudioSource.VOICE_CALL_DOWNLINK),
    ;

    companion object {
        fun of(token: String): LinkAudio? = entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}

/**
 * Everything a connection link may ask to change, for the one session it opens.
 *
 * This is the single definition of the option surface: the URL parser, the link builder, the
 * intent that carries it into the session, and the prompt that describes it all read from here,
 * so they cannot drift apart as options are added.
 *
 * The surface is deliberately bounded to how the stream looks and sounds, which bundled server
 * produces it, plus remote screen behaviour that scrcpy itself undoes when a session ends. A link
 * arrives from outside the app, so it may not do anything with a lasting effect: it cannot launch
 * an app, create a virtual display, name an encoder, or change what is saved. Nothing here is ever
 * written back.
 */
internal data class LinkRequest(
    val preset: StreamingPreset? = null,
    val maxSize: Int? = null,
    val bitrateMbps: Int? = null,
    val maxFps: Int? = null,
    val codec: VideoCodec? = null,
    val orientation: CaptureOrientation? = null,
    val displayId: Int? = null,
    val audio: LinkAudio? = null,
    val audioCodec: AudioCodec? = null,
    val screenOff: Boolean? = null,
    val stayAwake: Boolean? = null,
    val showTouches: Boolean? = null,
    /**
     * Keep the sound playing on the device too. Only meaningful for playback capture, so a link
     * carrying it must also say `audio=playback`; [parse] refuses it otherwise rather than changing
     * the source to suit it or dropping it unsaid.
     */
    val audioDup: Boolean? = null,
    /** Sound quality in kbit/s, within the settings' own bounds; refused beside `audio=off`. */
    val audioBitrateKbps: Int? = null,
    /** scrcpy's `keep_active`, which the server undoes when the session ends like the others here. */
    val keepActive: Boolean? = null,
    /** Which bundled server the device runs, named by its version. */
    val server: ScrcpyServerVersion? = null,
) {
    /** Whether this asks for anything at all. */
    val isEmpty: Boolean get() = this == EMPTY

    /**
     * Applies the request on top of [settings].
     *
     * A preset is applied first so an individual value beside it still wins: a link may say
     * "responsive, but 45 fps" and mean exactly that.
     */
    fun applyTo(settings: MirrorSettings): MirrorSettings {
        var result = preset?.applyTo(settings) ?: settings
        maxSize?.let { result = result.copy(maxSize = it) }
        bitrateMbps?.let { result = result.copy(videoBitRateMbps = it) }
        maxFps?.let { result = result.copy(maxFps = it) }
        codec?.let {
            // An encoder is only valid for the codec it was listed under, so changing the codec
            // drops it rather than sending a pair the device refuses as "Incorrect encoder type".
            result = result.copy(
                videoCodec = it,
                videoEncoder = if (it != settings.videoCodec) null else result.videoEncoder,
            )
        }
        orientation?.let { result = result.copy(captureOrientation = it) }
        displayId?.let {
            // A named physical display selects screen capture. A saved camera or virtual display
            // would otherwise take precedence over the display promised in the link prompt.
            result = result.copy(displayId = it, virtualDisplay = null, camera = null)
        }
        audio?.let { choice ->
            result = result.copy(audioEnabled = choice.enabled)
            choice.source?.let { result = result.copy(audioSource = it) }
        }
        audioCodec?.let {
            result = result.copy(
                audioCodec = it,
                audioEncoder = if (it != settings.audioCodec) null else result.audioEncoder,
            )
        }
        screenOff?.let { result = result.copy(turnScreenOff = it) }
        stayAwake?.let { result = result.copy(stayAwake = it) }
        showTouches?.let { result = result.copy(showTouches = it) }
        audioDup?.let { result = result.copy(audioDup = it) }
        audioBitrateKbps?.let { result = result.copy(audioBitRateKbps = it) }
        keepActive?.let { result = result.copy(keepActive = it) }
        server?.let { result = result.copy(serverVersion = it) }
        return result
    }

    /** The query fragment a link carries, without a leading separator. */
    fun toQuery(): String = buildList {
        preset?.let { add("preset=${it.token}") }
        maxSize?.let { add("maxSize=$it") }
        bitrateMbps?.let { add("bitrate=$it") }
        maxFps?.let { add("fps=$it") }
        codec?.let { add("codec=${it.serverValue}") }
        orientation?.let { add("orientation=${orientationToken(it)}") }
        displayId?.let { add("displayId=$it") }
        audio?.let { add("audio=${it.token}") }
        audioCodec?.let { add("audioCodec=${it.serverValue}") }
        screenOff?.let { add("screenOff=$it") }
        stayAwake?.let { add("stayAwake=$it") }
        showTouches?.let { add("showTouches=$it") }
        audioDup?.let { add("audioDup=$it") }
        audioBitrateKbps?.let { add("audioBitrate=$it") }
        keepActive?.let { add("keepActive=$it") }
        server?.let { add("server=${it.version}") }
    }.joinToString("&")

    companion object {
        val EMPTY = LinkRequest()

        /** Parameter names a link may carry, beyond the destination itself. */
        val NAMES = setOf(
            "preset", "maxSize", "bitrate", "fps", "codec", "orientation", "displayId",
            "audio", "audioCodec", "screenOff", "stayAwake", "showTouches",
            "audioDup", "audioBitrate", "keepActive", "server",
        )

        /**
         * Reads a request from already-decoded parameters, or null if any value is unusable.
         *
         * Refusing the whole link on one bad value is deliberate: a link that silently did
         * something other than what it says would be worse than one that does not open.
         */
        fun parse(parameters: Map<String, String>): LinkRequest? {
            var failed = false
            fun bool(key: String): Boolean? {
                val raw = parameters[key] ?: return null
                return when (raw.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> { failed = true; null }
                }
            }
            fun int(key: String, range: IntRange): Int? {
                val raw = parameters[key] ?: return null
                val value = raw.toIntOrNull()?.takeIf { it in range }
                if (value == null) failed = true
                return value
            }
            fun <T> pick(key: String, values: List<T>, token: (T) -> String): T? {
                val raw = parameters[key] ?: return null
                val canonical = if (key == "server") ScrcpyServerVersion.of(raw)?.version ?: raw else raw
                val value = values.firstOrNull { token(it).equals(canonical, ignoreCase = true) }
                if (value == null) failed = true
                return value
            }
            val request = LinkRequest(
                preset = pick("preset", StreamingPreset.entries) { it.token },
                maxSize = int("maxSize", SettingsValidation.MAX_SIZE_RANGE),
                bitrateMbps = int("bitrate", SettingsValidation.BIT_RATE_RANGE),
                maxFps = int("fps", SettingsValidation.FPS_RANGE),
                codec = pick("codec", VideoCodec.entries) { it.serverValue },
                orientation = pick("orientation", CaptureOrientation.entries) { orientationToken(it) },
                displayId = int("displayId", SettingsValidation.DISPLAY_ID_RANGE),
                audio = pick("audio", LinkAudio.entries) { it.token },
                audioCodec = pick("audioCodec", AudioCodec.entries) { it.serverValue },
                screenOff = bool("screenOff"),
                stayAwake = bool("stayAwake"),
                showTouches = bool("showTouches"),
                audioDup = bool("audioDup"),
                audioBitrateKbps = int("audioBitrate", SettingsValidation.AUDIO_BIT_RATE_RANGE),
                keepActive = bool("keepActive"),
                server = pick("server", ScrcpyServerVersion.entries) { it.version },
            )
            // Options that only make sense together must come together: a link that asked to keep
            // sound on the device without playback capture, or for a sound quality while turning
            // sound off, says two things at once, and neither reading is safe to guess.
            if (request.audioDup != null && request.audio != LinkAudio.PLAYBACK) failed = true
            if (request.audioBitrateKbps != null && request.audio == LinkAudio.OFF) failed = true
            return if (failed) null else request
        }

        /** Parses a raw `a=b&c=d` fragment, used to carry a request through an intent. */
        fun parseQuery(query: String?): LinkRequest {
            if (query.isNullOrEmpty()) return EMPTY
            val parameters = query.split('&').mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator < 1) null else part.substring(0, separator) to part.substring(separator + 1)
            }.toMap()
            return parse(parameters) ?: EMPTY
        }

        /** Reads a request carried on a mirror intent. */
        fun from(intent: Intent): LinkRequest =
            parseQuery(intent.getStringExtra(MirrorActivity.EXTRA_LINK_OPTIONS))

        /**
         * `follow`, `current`, `0`, `90`, `180`, `270`. The server's own `@`-prefixed spelling is
         * awkward inside a URL, so a link uses the plain form.
         */
        fun orientationToken(orientation: CaptureOrientation): String = when (orientation) {
            CaptureOrientation.FOLLOW_DEVICE -> "follow"
            CaptureOrientation.LOCK_CURRENT -> "current"
            CaptureOrientation.LOCK_0 -> "0"
            CaptureOrientation.LOCK_90 -> "90"
            CaptureOrientation.LOCK_180 -> "180"
            CaptureOrientation.LOCK_270 -> "270"
        }
    }
}
