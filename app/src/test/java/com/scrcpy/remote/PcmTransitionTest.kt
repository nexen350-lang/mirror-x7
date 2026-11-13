package com.scrcpy.remote

import com.scrcpy.remote.session.PcmPlaybackBuffer
import com.scrcpy.remote.session.PcmTransition
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTransitionTest {
    @Test fun skippedStereoIntegerSamplesRampWithoutSwappingChannels() {
        val transition = PcmTransition(1_000, 2, false)
        val before = shorts(10_000, -10_000)
        transition.apply(before, before.size, false)
        val after = shorts(*IntArray(12) { if (it % 2 == 0) -10_000 else 10_000 })
        transition.apply(after, after.size, true)
        val result = ByteBuffer.wrap(after).order(ByteOrder.nativeOrder()).asShortBuffer()
        assertEquals(6_000, result[0].toInt())
        assertEquals(-6_000, result[1].toInt())
        assertEquals(-10_000, result[8].toInt())
        assertEquals(10_000, result[9].toInt())
    }

    @Test fun floatRampContinuesAcrossChunksAndResetStartsFromSilence() {
        val transition = PcmTransition(1_000, 1, true)
        val initial = floats(0.8f)
        transition.apply(initial, initial.size, false)
        val first = floats(-0.8f, -0.8f)
        transition.apply(first, first.size, true)
        assertEquals(0.48f, ByteBuffer.wrap(first).order(ByteOrder.nativeOrder()).float, 0.0001f)
        val next = floats(-0.8f, -0.8f, -0.8f)
        transition.apply(next, next.size, false)
        assertEquals(-0.8f, ByteBuffer.wrap(next).order(ByteOrder.nativeOrder()).getFloat(8), 0.0001f)
        transition.reset()
        val reset = floats(1f)
        transition.apply(reset, reset.size, false)
        assertEquals(0.2f, ByteBuffer.wrap(reset).order(ByteOrder.nativeOrder()).float, 0.0001f)
    }

    @Test fun timestampedQueuePreservesPtsAcrossPartialReadsAndDrops() {
        val buffer = PcmPlaybackBuffer(2, 10, 20, 1_000)
        buffer.offer(ByteArray(40), 40, 1_000_000)
        val first = buffer.pollChunk(ByteArray(10), 0)
        assertEquals(1_000_000L, first.ptsUs)
        assertFalse(first.discontinuity)
        buffer.offer(ByteArray(40), 40, 1_020_000)
        val latest = buffer.pollChunk(ByteArray(20), 0)
        assertEquals(1_030_000L, latest.ptsUs)
        assertTrue(latest.discontinuity)
        assertEquals(25L, buffer.droppedFrames)
    }

    @Test fun reconstructedTimestampGapIsNotConvertedIntoSampleDropping() {
        val buffer = PcmPlaybackBuffer(2, 10, 20, 1_000)
        buffer.offer(ByteArray(10), 10, 1_000_000)
        buffer.offer(ByteArray(10), 10, 2_000_000)
        assertEquals(5 * 2, buffer.pollChunk(ByteArray(20), 0).length)
        assertEquals(2_000_000L, buffer.pollChunk(ByteArray(20), 0).ptsUs)
        assertEquals(0L, buffer.droppedFrames)
    }

    private fun shorts(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
        }.array()
    private fun floats(vararg samples: Float): ByteArray =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putFloat(it) }
        }.array()
}
