package com.scrcpy.remote.session

/**
 * Spaces out the key frames a recording asks the device for, without losing any request.
 *
 * Each request makes the device encode a whole frame, so they are kept at least [spacingMs]
 * apart. One that comes too soon waits for the rest of the spacing instead of being dropped: a
 * dropped request left a recording waiting for the device's own next key frame, ten seconds
 * away. Requests made while one is waiting are that one.
 */
internal class KeyFramePacer(private val spacingMs: Long) {
    private var lastSentMs = Long.MIN_VALUE / 2
    private var waiting = false

    /** How long to wait before sending: 0 to send now, null when a request is already waiting. */
    fun request(nowMs: Long): Long? {
        if (waiting) return null
        val wait = lastSentMs + spacingMs - nowMs
        if (wait <= 0) {
            lastSentMs = nowMs
            return 0
        }
        waiting = true
        return wait
    }

    /** The waiting request was sent at [nowMs]. */
    fun fired(nowMs: Long) {
        waiting = false
        lastSentMs = nowMs
    }

    /** The recording ended: nothing is waiting any more. */
    fun reset() {
        waiting = false
        lastSentMs = Long.MIN_VALUE / 2
    }
}
