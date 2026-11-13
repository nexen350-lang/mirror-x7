package com.scrcpy.remote.session

/**
 * Decides when a recording has too little free storage left to be trusted with more frames.
 *
 * An MP4's index is written only when the file is closed, so a disk that fills mid-recording makes
 * the close fail and loses everything. Stopping while a reserve remains lets the close succeed.
 * Free space is read every [everyFrames] calls rather than per frame, as it is a file system query.
 */
internal class StorageGuard(
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val everyFrames: Int = CHECK_EVERY_FRAMES,
    private val freeSpace: () -> Long,
) {
    private var calls = 0

    /** True when the free space, sampled now, is below the reserve. */
    fun isLow(): Boolean {
        if (++calls % everyFrames != 0) return false
        return freeSpace() < reserveBytes
    }

    companion object {
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
        const val CHECK_EVERY_FRAMES = 120
    }
}

/**
 * Whether a refused low-latency decoder request says anything about the decoder.
 *
 * Configuring throws as well when the surface was replaced or released mid-call, which is an
 * ordinary transition; remembering that as "the decoder refuses low latency" would switch the
 * option off for the rest of the session.
 */
internal fun lowLatencyRefusalApplies(surfaceValid: Boolean, surfaceStillCurrent: Boolean): Boolean =
    surfaceValid && surfaceStillCurrent
