package com.scrcpy.remote.session

/**
 * Decides which encoded frames belong in a recording, and the timestamp each one is written with.
 *
 * The stream this records from was already running before the user asked for a recording, so it
 * cannot simply be copied from wherever it happens to be. Three things have to be got right:
 *
 * - a file has to open on a key frame, because a decoder starting at anything else has nothing to
 *   decode against and the recording opens as a grey or broken picture;
 * - timestamps arrive relative to the device's own clock and can be large, so they are rebased to
 *   start at zero rather than leaving the player with a long empty lead-in;
 * - a muxer requires strictly increasing sample times, and the stream does not promise that,
 *   so a frame that would go backwards is nudged forward instead of failing the whole recording.
 */
internal class RecordingTimeline {

    private var firstPtsUs = UNSET
    private var lastWrittenUs = UNSET
    private var open = false

    /** Whether a file has been opened and is taking frames. */
    val isOpen: Boolean get() = open

    /**
     * Whether recording may start at this frame.
     *
     * Returns false until a key frame comes along, which is what the caller waits for before
     * creating the file at all.
     */
    fun canOpenAt(isKeyFrame: Boolean): Boolean = !open && isKeyFrame

    /** Marks the file as open, with [ptsUs] as the frame it opened on. */
    fun opened(ptsUs: Long) {
        open = true
        firstPtsUs = ptsUs
        lastWrittenUs = UNSET
    }

    /**
     * The time to write [ptsUs] at, or null when the frame should be dropped.
     *
     * Null means the recording is not open, so there is nowhere to put it.
     */
    fun sampleTimeUs(ptsUs: Long): Long? {
        if (!open) return null
        val rebased = (ptsUs - firstPtsUs).coerceAtLeast(0L)
        // A repeated or out-of-order timestamp is nudged past the previous one rather than
        // rejected: one odd frame is not worth ending a recording over, and a player reads a
        // one microsecond gap as the same instant.
        val time = if (lastWrittenUs != UNSET && rebased <= lastWrittenUs) lastWrittenUs + 1 else rebased
        lastWrittenUs = time
        return time
    }

    /** Forgets the open file, so the next key frame can start a new one. */
    fun closed() {
        open = false
        firstPtsUs = UNSET
        lastWrittenUs = UNSET
    }

    private companion object {
        const val UNSET = -1L
    }
}
