package com.scrcpy.remote.scrcpy

import android.annotation.SuppressLint
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.StringRes
import com.scrcpy.remote.R

/**
 * Codec identifiers sent by scrcpy-server as a big-endian `u32` at the start of the video and
 * audio sockets (see doc/develop.md "Video and audio").
 */
// MIMETYPE_VIDEO_AV1 is documented from API 29 but is a compile-time String constant, so
// referencing it below API 29 is safe; [VideoCodec.supported] additionally hides AV1 unless the
// device actually has a decoder for it.
@SuppressLint("InlinedApi")
object ScrcpyCodecs {

    /** Sent instead of a real codec id when the device could not enable the stream. */
    const val CODEC_DISABLED = 0

    const val VIDEO_H264 = 0x68323634
    const val VIDEO_H265 = 0x68323635
    const val VIDEO_AV1 = 0x00617631
    const val VIDEO_VP8 = 0x00767038
    const val VIDEO_VP9 = 0x00767039

    const val AUDIO_OPUS = 0x6F707573
    const val AUDIO_AAC = 0x00616163
    const val AUDIO_FLAC = 0x666C6163
    const val AUDIO_RAW = 0x00726177

    fun videoMimeType(codecId: Int): String = when (codecId) {
        VIDEO_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VIDEO_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VIDEO_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        VIDEO_VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
        VIDEO_VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
        else -> throw IllegalArgumentException("Unsupported video codec id: ${codecName(codecId)}")
    }

    /** Mime type a [VideoCodec] decodes to, used to check local decoder support. */
    fun videoMimeType(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
        VideoCodec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        VideoCodec.VP8 -> MediaFormat.MIMETYPE_VIDEO_VP8
        VideoCodec.VP9 -> MediaFormat.MIMETYPE_VIDEO_VP9
    }

    fun audioMimeType(codecId: Int): String? = when (codecId) {
        AUDIO_OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
        AUDIO_AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        AUDIO_FLAC -> MediaFormat.MIMETYPE_AUDIO_FLAC
        AUDIO_RAW -> null // already PCM, no decoder needed
        else -> throw IllegalArgumentException("Unsupported audio codec id: ${codecName(codecId)}")
    }

    fun codecName(codecId: Int): String {
        val bytes = byteArrayOf(
            (codecId ushr 24).toByte(),
            (codecId ushr 16).toByte(),
            (codecId ushr 8).toByte(),
            codecId.toByte(),
        )
        return String(bytes, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
    }

    /**
     * The FLAC codec configuration a local decoder will accept.
     *
     * The device sends the STREAMINFO block on its own: the server strips the `fLaC` marker and
     * the metadata block header before the packet goes out. MediaCodec will not take that — its
     * parser looks for the marker, never finds one, so it never learns the block size and then
     * rejects the first frame as malformed — which is why FLAC produced an error instead of sound
     * on every session. The framing is put back here.
     *
     * A configuration that already carries the marker is passed through untouched, so this stays
     * correct whichever form a device sends.
     */
    fun flacCodecConfig(config: ByteArray): ByteArray {
        if (config.size >= FLAC_MAGIC.size &&
            config.copyOfRange(0, FLAC_MAGIC.size).contentEquals(FLAC_MAGIC)
        ) return config
        // Last metadata block, type 0 (STREAMINFO), then its length as a 24 bit big-endian count.
        val blockHeader = byteArrayOf(
            0x80.toByte(),
            (config.size ushr 16).toByte(),
            (config.size ushr 8).toByte(),
            config.size.toByte(),
        )
        return FLAC_MAGIC + blockHeader + config
    }

    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)

    /** What an AAC AudioSpecificConfig says about the stream an MP4 track has to describe. */
    data class AacAudioConfig(val sampleRate: Int, val channels: Int)

    /**
     * Reads an AAC AudioSpecificConfig: the configuration packet the device sends for AAC sound.
     *
     * An MP4 track needs the sample rate and channel count stated beside the configuration, and
     * the device states them nowhere else. Returns null for a configuration that cannot describe a
     * track: cut short, a reserved rate, or channels given by a program configuration element.
     */
    fun aacAudioConfig(config: ByteArray): AacAudioConfig? {
        var position = 0
        fun bits(count: Int): Int? {
            if (position + count > config.size * 8) return null
            var value = 0
            repeat(count) {
                val byte = config[position / 8].toInt() and 0xff
                value = (value shl 1) or ((byte shr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
        val objectType = bits(5) ?: return null
        // An escape to the extended object types, which the rate still follows.
        if (objectType == 31) bits(6) ?: return null
        val index = bits(4) ?: return null
        val sampleRate = if (index == 15) bits(24) ?: return null else AAC_SAMPLE_RATES.getOrNull(index) ?: return null
        val channels = when (val channelConfig = bits(4) ?: return null) {
            in 1..6 -> channelConfig
            7 -> 8
            else -> return null
        }
        return if (sampleRate > 0) AacAudioConfig(sampleRate, channels) else null
    }

    private val AAC_SAMPLE_RATES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

    /**
     * Splits an H.264 configuration into the two parameter sets a muxer asks for separately.
     *
     * A decoder is happy to take the sequence and picture parameter sets in one buffer, and that
     * is how the device sends them. An MP4 writer is not: it builds its own header from `csd-0`
     * and `csd-1` and, given only the first, writes a file with no usable track at all. Returns
     * null when the configuration does not hold both, so the caller can fall back rather than
     * write something broken.
     */
    fun avcParameterSets(config: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (unit in nalUnits(config)) {
            val payload = config.copyOfRange(unit.first, unit.last + 1)
            // The type is the low five bits of the byte after the start code.
            val typeIndex = unit.first + startCodeLength(config, unit.first)
            if (typeIndex > unit.last) continue
            when (config[typeIndex].toInt() and 0x1F) {
                NAL_SPS -> if (sps == null) sps = payload
                NAL_PPS -> if (pps == null) pps = payload
            }
        }
        val sequence = sps ?: return null
        val picture = pps ?: return null
        return sequence to picture
    }

    private const val NAL_SPS = 7
    private const val NAL_PPS = 8

    /** Ranges of the Annex B units in [data], each including its own start code. */
    private fun nalUnits(data: ByteArray): List<IntRange> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index + 2 < data.size) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0 &&
                (data[index + 2].toInt() == 1 ||
                    (index + 3 < data.size && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1))
            ) {
                starts += index
                index += if (data[index + 2].toInt() == 1) 3 else 4
            } else {
                index++
            }
        }
        return starts.mapIndexed { position, start ->
            val end = if (position + 1 < starts.size) starts[position + 1] - 1 else data.size - 1
            start..end
        }
    }

    private fun startCodeLength(data: ByteArray, start: Int): Int =
        if (start + 2 < data.size && data[start + 2].toInt() == 1) 3 else 4
}

enum class VideoCodec(val serverValue: String, val label: String) {
    H264("h264", "H.264"),
    H265("h265", "H.265 (HEVC)"),
    AV1("av1", "AV1"),
    VP8("vp8", "VP8"),
    VP9("vp9", "VP9");

    companion object {
        /**
         * Codecs this phone can actually decode.
         *
         * Offering a codec with no local decoder only produces a failed session, so the choice is
         * narrowed to what `MediaCodecList` reports. H.264 is always kept as a fallback because a
         * device without it could not mirror at all.
         */
        fun supported(): List<VideoCodec> {
            val decoders = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filterNot { it.isEncoder }
                    .flatMap { it.supportedTypes.asIterable() }
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())

            if (decoders.isEmpty()) return listOf(H264)
            return entries.filter { codec ->
                codec == H264 || ScrcpyCodecs.videoMimeType(codec).lowercase() in decoders
            }
        }
    }
}

enum class AudioCodec(val serverValue: String, val label: String, val mimeType: String?) {
    OPUS("opus", "Opus", MediaFormat.MIMETYPE_AUDIO_OPUS),
    AAC("aac", "AAC", MediaFormat.MIMETYPE_AUDIO_AAC),
    FLAC("flac", "FLAC", MediaFormat.MIMETYPE_AUDIO_FLAC),

    /** Already PCM on the wire, so it plays without a decoder at all. */
    RAW("raw", "Raw PCM", null),
    ;

    companion object {
        /**
         * Codecs this phone can actually decode.
         *
         * The mirror of [VideoCodec.supported], for the same reason and one more: a FLAC decoder
         * is only guaranteed from Android 8.1, so on 8.0 — which this app still supports —
         * choosing it produced an audio error, and "Retry audio" then failed the same way every
         * time. Raw PCM needs no decoder, and Opus is kept as the fallback because it is what the
         * server sends unless told otherwise.
         */
        fun supported(): List<AudioCodec> {
            val decoders = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                    .filterNot { it.isEncoder }
                    .flatMap { it.supportedTypes.asIterable() }
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())

            if (decoders.isEmpty()) return listOf(OPUS, RAW)
            return entries.filter { codec ->
                codec == OPUS || codec.mimeType == null || codec.mimeType.lowercase() in decoders
            }
        }
    }
}

/**
 * Where the server takes sound from: every `audio_source` value the bundled servers accept
 * (`com.genymobile.scrcpy.audio.AudioSource` in scrcpy 5.0.1).
 *
 * All sources are available without a server-version gate. [privileged] sources read
 * call audio, which Android normally reserves for system apps, so most devices refuse them; the
 * session then continues without sound and says why rather than failing the connection.
 */
enum class AudioSource(
    val serverValue: String,
    @StringRes val labelRes: Int,
    @StringRes val detailRes: Int? = null,
    val extended: Boolean = false,
    val privileged: Boolean = false,
) {
    OUTPUT("output", R.string.audio_source_output),
    MIC("mic", R.string.audio_source_mic),
    PLAYBACK("playback", R.string.audio_source_playback),
    MIC_UNPROCESSED("mic-unprocessed", R.string.audio_source_mic_unprocessed,
        R.string.audio_source_mic_unprocessed_detail, extended = true),
    MIC_CAMCORDER("mic-camcorder", R.string.audio_source_mic_camcorder,
        R.string.audio_source_mic_camcorder_detail, extended = true),
    MIC_VOICE_RECOGNITION("mic-voice-recognition", R.string.audio_source_mic_voice_recognition,
        R.string.audio_source_mic_voice_recognition_detail, extended = true),
    MIC_VOICE_COMMUNICATION("mic-voice-communication", R.string.audio_source_mic_voice_communication,
        R.string.audio_source_mic_voice_communication_detail, extended = true),
    VOICE_PERFORMANCE("voice-performance", R.string.audio_source_voice_performance,
        R.string.audio_source_voice_performance_detail, extended = true),
    VOICE_CALL("voice-call", R.string.audio_source_voice_call,
        R.string.audio_source_privileged_detail, extended = true, privileged = true),
    VOICE_CALL_UPLINK("voice-call-uplink", R.string.audio_source_voice_call_uplink,
        R.string.audio_source_privileged_detail, extended = true, privileged = true),
    VOICE_CALL_DOWNLINK("voice-call-downlink", R.string.audio_source_voice_call_downlink,
        R.string.audio_source_privileged_detail, extended = true, privileged = true),
    ;

}

/**
 * How the device orients the video it captures.
 *
 * The wire values are the ones `Options.parseCaptureOrientation` accepts in scrcpy 5.0.1: a bare
 * `@` locks to whatever orientation the device is in when the session starts, and `@` followed by
 * a rotation locks to that rotation. Without a lock the capture follows the device, which is the
 * default and is simply not sent.
 */
enum class CaptureOrientation(val serverValue: String?, @StringRes val labelRes: Int) {
    FOLLOW_DEVICE(null, R.string.capture_orientation_follow),
    LOCK_CURRENT("@", R.string.capture_orientation_lock_current),
    LOCK_0("@0", R.string.capture_orientation_lock_0),
    LOCK_90("@90", R.string.capture_orientation_lock_90),
    LOCK_180("@180", R.string.capture_orientation_lock_180),
    LOCK_270("@270", R.string.capture_orientation_lock_270),
}
