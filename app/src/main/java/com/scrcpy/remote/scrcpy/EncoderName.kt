package com.scrcpy.remote.scrcpy

/**
 * The encoder names the device can be asked to use.
 *
 * Names come from the device's own MediaCodec list — `c2.android.avc.encoder`,
 * `OMX.google.h264.encoder`, `c2.qti.avc.encoder` — so the app cannot know them in advance and
 * only checks the shape. A name the server rejects makes it exit before opening any socket, which
 * the client could otherwise report only as a failed connection.
 */
object EncoderName {
    /** Comfortably above any real name; guards against a pasted wall of text. */
    const val MAX_LENGTH = 128

    private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._\\-]*")

    fun isUsable(name: String): Boolean =
        name.length <= MAX_LENGTH && PATTERN.matches(name)
}
