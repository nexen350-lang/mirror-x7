package com.scrcpy.remote.scrcpy

import java.io.IOException

/** One encoder the device reported, for a specific codec. */
data class DeviceEncoder(
    val codec: String,
    val name: String,
    /** `hw`, `sw` or `hybrid` as the device classifies it; null before Android 10. */
    val acceleration: String? = null,
    val vendor: Boolean = false,
)

/**
 * Reads the encoder list the scrcpy server prints for `list_encoders=true`.
 *
 * The server formats each entry as the command-line flags a desktop user would pass, padded to a
 * 70 character column and followed by the acceleration class:
 *
 * ```
 * List of video encoders:
 *     --video-codec=h264 --video-encoder=c2.android.avc.encoder           (sw)
 *     --video-codec=h264 --video-encoder=c2.qti.avc.encoder               (hw) [vendor]
 * ```
 *
 * Pure so the format can be tested without a device.
 */
object DeviceEncoderListParser {

    /** Header the server prints before the video entries. */
    const val VIDEO_HEADER = "List of video encoders:"

    /** Header the server prints before the audio entries, after the video ones. */
    const val AUDIO_HEADER = "List of audio encoders:"

    private val ENTRY = Regex(
        """--(video|audio)-codec=(\S+)\s+--\1-encoder=(\S+)(.*)$"""
    )
    private val ACCELERATION = Regex("""\((hw|sw|hybrid)\)""")

    /** Encoders for [type], which is `video` or `audio`. */
    fun parse(output: String, type: String = "video"): List<DeviceEncoder> {
        val encoders = mutableListOf<DeviceEncoder>()
        for (raw in output.lineSequence()) {
            val match = ENTRY.find(raw.trimEnd('\r')) ?: continue
            if (match.groupValues[1] != type) continue
            val trailing = match.groupValues[4]
            encoders += DeviceEncoder(
                codec = match.groupValues[2],
                name = match.groupValues[3],
                acceleration = ACCELERATION.find(trailing)?.groupValues?.get(1),
                vendor = "[vendor]" in trailing,
            )
        }
        // A device can report the same encoder under more than one alias line.
        return encoders.distinctBy { it.codec to it.name }
    }

    /**
     * Parses server output, rejecting output that never reached the listing stage so the caller
     * reports a failure instead of a device that appears to have no encoders at all.
     *
     * The requested list's own header is required: audio is printed after video, so output that
     * stopped in between used to read as a device with no audio encoders.
     */
    @Throws(IOException::class)
    fun parseServerOutput(output: String, type: String = "video"): List<DeviceEncoder> {
        val header = when (type) {
            "video" -> VIDEO_HEADER
            "audio" -> AUDIO_HEADER
            else -> throw IllegalArgumentException("Unknown encoder type: $type")
        }
        if (!output.contains(header)) {
            throw IOException(
                "The device did not return its encoders: " +
                    output.trim().ifBlank { "no output" }.take(200)
            )
        }
        return parse(output, type)
    }
}
