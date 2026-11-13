package com.scrcpy.remote.session

/**
 * How long the codec worker waits for the next packet.
 *
 * The worker cannot wait on the network and on the codec at the same time: it blocks for an input
 * packet, and only drains decoded output between those waits. A single fixed wait therefore has to
 * choose between two bad outcomes — a long one holds a finished frame back until the wait expires,
 * and a short one wakes an idle session continuously.
 *
 * Waiting briefly while packets are still arriving, and backing off only once they have genuinely
 * stopped, avoids choosing. A frame that finishes decoding is released within [ACTIVE_MILLIS]
 * instead of up to the old fixed 10 ms, and a session with nothing to show settles to a fifth of
 * the wake-ups it used to make.
 */
internal object PumpPollPolicy {
    /** Wait used while the stream is active, and so the worst case added to a decoded frame. */
    const val ACTIVE_MILLIS = 2L

    /** Wait used once the stream has gone quiet. */
    const val IDLE_MILLIS = 50L

    /**
     * Quiet rounds tolerated before backing off.
     *
     * [ACTIVE_MILLIS] times this is the gap between packets that still counts as active, so it
     * has to comfortably exceed a frame interval: at 20 fps packets are 50 ms apart, and treating
     * that as idle would add the idle wait to every frame.
     */
    const val ACTIVE_ROUNDS = 25

    fun timeoutMillis(quietRounds: Int): Long {
        require(quietRounds >= 0)
        return if (quietRounds < ACTIVE_ROUNDS) ACTIVE_MILLIS else IDLE_MILLIS
    }
}
