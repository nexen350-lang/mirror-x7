package com.scrcpy.remote.ui

import android.content.Intent
import com.scrcpy.remote.data.sameEndpoint

/**
 * What a mirror intent asks for, apart from any file it carries.
 *
 * The mirror screen is reused rather than stacked, so a second request arrives at the one already
 * running and has to be compared against what it is doing. Getting that comparison wrong is
 * expensive in both directions: treating a new destination as the same one leaves the previous
 * device mirrored under the new device's name, and treating a repeat as new tears down a working
 * stream to rebuild the same one.
 */
internal data class MirrorTarget(
    val host: String,
    val port: Int,
    val deviceId: String?,
    val linkOptions: String?,
) {
    /**
     * Whether [other] asks for the session this already describes.
     *
     * The saved device and the link's own options count as part of the destination, not decoration
     * on top of it: both decide the settings the session connects with, and a connection already
     * running cannot adopt them without being restarted.
     */
    fun sameSessionAs(other: MirrorTarget): Boolean =
        sameEndpoint(host, port, other.host, other.port) &&
            deviceId == other.deviceId &&
            linkOptions.orEmpty() == other.linkOptions.orEmpty()

    companion object {
        /** Reads a target from a mirror intent, or null when it names no destination. */
        fun from(intent: Intent?): MirrorTarget? {
            val host = intent?.getStringExtra(MirrorActivity.EXTRA_HOST)?.takeIf { it.isNotEmpty() }
                ?: return null
            return MirrorTarget(
                host = host,
                port = intent.getIntExtra(MirrorActivity.EXTRA_PORT, MirrorActivity.DEFAULT_PORT),
                deviceId = intent.getStringExtra(MirrorActivity.EXTRA_DEVICE_ID),
                linkOptions = intent.getStringExtra(MirrorActivity.EXTRA_LINK_OPTIONS),
            )
        }
    }
}
