package com.scrcpy.remote.session

/** AudioTrack counters on some devices expose only the unsigned low 32 bits. */
internal class FramePositionCounter {
    private var last = 0L
    private var wraps = 0L

    fun update(position: Long): Long {
        require(position >= 0)
        if (position > 0xffffffffL) {
            last = position and 0xffffffffL
            wraps = position and -0x100000000L
            return position
        }
        if (position < last && last - position > 0x80000000L) wraps += 0x100000000L
        last = position
        return wraps + position
    }

    fun reset() {
        last = 0
        wraps = 0
    }
}
