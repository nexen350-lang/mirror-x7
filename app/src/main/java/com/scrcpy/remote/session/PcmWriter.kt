package com.scrcpy.remote.session

import java.io.IOException

/** Retains an unwritten suffix. Zero/error returns must not masquerade as successful playback. */
object PcmWriter {
    fun writeFully(
        data: ByteArray,
        length: Int,
        active: () -> Boolean,
        write: (ByteArray, Int, Int) -> Int,
    ) {
        require(length in 0..data.size)
        var offset = 0
        while (offset < length && active()) {
            val written = write(data, offset, length - offset)
            if (written <= 0 || written > length - offset) {
                throw IOException("AudioTrack write failed or made no progress: $written")
            }
            offset += written
        }
    }
}
