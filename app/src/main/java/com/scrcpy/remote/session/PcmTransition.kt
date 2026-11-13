package com.scrcpy.remote.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A short ramp across catch-up/route discontinuities avoids a full-scale sample step. */
internal class PcmTransition(sampleRate: Int, private val channels: Int, private val floatPcm: Boolean) {
    private val rampFrames = (sampleRate * 5 / 1_000).coerceAtLeast(1)
    private val previous = DoubleArray(channels)
    private var remaining = 0
    private val origin = DoubleArray(channels)
    private val sampleBytes = if (floatPcm) 4 else 2

    fun apply(data: ByteArray, length: Int, discontinuity: Boolean) {
        require(length in 0..data.size && length % (channels * sampleBytes) == 0)
        if (discontinuity) {
            previous.copyInto(origin)
            remaining = rampFrames
        }
        val bytes = ByteBuffer.wrap(data, 0, length).order(ByteOrder.nativeOrder())
        repeat(length / (channels * sampleBytes)) {
            val mix = if (remaining > 0) (rampFrames - remaining + 1).toDouble() / rampFrames else 1.0
            repeat(channels) { channel ->
                val position = bytes.position()
                val value = if (floatPcm) bytes.float.toDouble() else bytes.short.toDouble()
                val adjusted = origin[channel] * (1 - mix) + value * mix
                if (floatPcm) bytes.putFloat(position, adjusted.toFloat())
                else bytes.putShort(position, adjusted.toInt().coerceIn(-32768, 32767).toShort())
                previous[channel] = adjusted
            }
            if (remaining > 0) remaining--
        }
    }

    fun reset() {
        previous.fill(0.0)
        origin.fill(0.0)
        remaining = rampFrames
    }
}
