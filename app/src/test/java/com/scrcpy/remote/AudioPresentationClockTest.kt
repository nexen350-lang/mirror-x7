package com.scrcpy.remote

import com.scrcpy.remote.session.AudioPresentationClock
import com.scrcpy.remote.session.PcmPlaybackStats
import com.scrcpy.remote.session.PcmPresentation
import com.scrcpy.remote.session.VideoPresentation
import com.scrcpy.remote.session.FramePositionCounter
import org.junit.Assert.*
import org.junit.Test

class AudioPresentationClockTest {
    private val now = 5_000_000_000L
    private val basePts = 1_000_000L

    private fun ready(sync: Boolean = true, offset: Int = 0): AudioPresentationClock {
        val clock = AudioPresentationClock(sync, offset)
        clock.record(0, 9_600, basePts, 48_000)
        clock.observe(PcmPresentation(2_400, now, true, 2), PcmPlaybackStats(480, 9_600, 96), now)
        return clock
    }

    @Test fun queuedFramesAboveIntMillisecondRangeDoNotOverflow() {
        val clock = AudioPresentationClock(false, 0)
        clock.record(0, 100, null, 48_000)
        clock.observe(null, PcmPlaybackStats(3_000_000, 0, 0), now)
        assertEquals(62_500, clock.diagnostics().audioQueuedMs)
    }

    @Test fun synchronizedVideoUsesActualAudioPresentationNotTheWriteTime() {
        val clock = ready()
        assertEquals(VideoPresentation.At(now + 100_000_000), clock.videoPresentation(basePts + 150_000, now))
        assertEquals(150, clock.diagnostics().outputQueuedMs)
        assertEquals(10, clock.diagnostics().audioQueuedMs)
        assertEquals(2, clock.diagnostics().underruns)
        assertEquals(96L, clock.diagnostics().droppedFrames)
    }

    @Test fun defaultModeNeverDelaysVideoEvenWithMeasuredAudio() {
        assertEquals(VideoPresentation.Immediate, ready(sync = false).videoPresentation(basePts + 150_000, now))
    }

    @Test fun explicitOffsetOnlyAdjustsSynchronizedViewing() {
        assertEquals(VideoPresentation.At(now + 125_000_000),
            ready(offset = 25).videoPresentation(basePts + 150_000, now))
        assertEquals(VideoPresentation.At(now + 75_000_000),
            ready(offset = -25).videoPresentation(basePts + 150_000, now))
    }

    @Test fun unknownStaleSilentMutedOrIncomparableClocksKeepVideoLive() {
        assertEquals(VideoPresentation.Immediate, AudioPresentationClock(true).videoPresentation(0, now))
        val clock = ready()
        assertEquals(VideoPresentation.Immediate, clock.videoPresentation(basePts + 150_000, now + 300_000_000))
        assertEquals(VideoPresentation.Immediate, ready().videoPresentation(99_000_000, now))
        assertEquals(VideoPresentation.Immediate, ready().videoPresentation(basePts - 500_000, now))
        val muted = ready()
        muted.setAudible(false)
        assertEquals(VideoPresentation.Immediate, muted.videoPresentation(basePts + 150_000, now))
        val fallback = AudioPresentationClock(true)
        fallback.record(0, 9_600, basePts, 48_000)
        fallback.observe(PcmPresentation(0, now, false, 0), PcmPlaybackStats(0, 9_600, 0), now)
        assertEquals(VideoPresentation.Immediate, fallback.videoPresentation(basePts + 150_000, now))
        val drained = ready()
        drained.observe(PcmPresentation(9_600, now, true, 0), PcmPlaybackStats(0, 9_600, 0), now)
        assertFalse(drained.diagnostics().clockReady)
    }

    @Test fun lateVideoRendersImmediatelyRatherThanFreezingOnEveryFrame() {
        assertEquals(VideoPresentation.Immediate, ready().videoPresentation(basePts - 200_000, now))
    }

    @Test fun droppedPcmUsesRetainedSampleTimestampWithoutCountingDiscardedFramesAsPlayed() {
        val clock = AudioPresentationClock(true)
        clock.record(0, 480, basePts, 48_000)
        clock.record(480, 4_800, basePts + 500_000, 48_000)
        clock.observe(PcmPresentation(960, now, true, 0), PcmPlaybackStats(0, 5_280, 24_000), now)
        assertEquals(VideoPresentation.At(now + 40_000_000), clock.videoPresentation(basePts + 550_000, now))
        clock.reset()
        assertEquals(VideoPresentation.Immediate, clock.videoPresentation(basePts + 550_000, now))
    }

    @Test fun freshTimestampRecoversAfterProjectionBeyondAllWrittenFrames() {
        val clock = AudioPresentationClock(true)
        clock.record(0, 480, basePts, 48_000)
        clock.record(480, 480, basePts + 20_000, 48_000)
        val stats = PcmPlaybackStats(0, 960, 0)

        // An underrun leaves a timestamp that projects to frame 1440, beyond the 960 written.
        clock.observe(PcmPresentation(480, now - 20_000_000, true, 1), stats, now)
        assertEquals(VideoPresentation.Immediate, clock.videoPresentation(basePts + 25_000, now))

        val recoveredAt = now + 1_000_000
        clock.observe(PcmPresentation(528, recoveredAt, true, 1), stats, recoveredAt)
        assertEquals("Fresh timestamps must recover without another PCM write",
            VideoPresentation.At(recoveredAt + 4_000_000),
            clock.videoPresentation(basePts + 25_000, recoveredAt))
    }

    @Test fun freshTimestampRecoversAfterProjectionSkipsSpansWithinTheQueue() {
        val clock = AudioPresentationClock(true)
        clock.record(0, 480, basePts, 48_000)
        clock.record(480, 480, basePts + 20_000, 48_000)
        clock.record(960, 480, basePts + 30_000, 48_000)
        val stats = PcmPlaybackStats(0, 1_440, 0)

        // Frame 1200 is inside the queue, but only frame 480 has been confirmed by hardware.
        clock.observe(PcmPresentation(480, now - 15_000_000, true, 1), stats, now)
        assertTrue(clock.diagnostics().clockReady)

        val recoveredAt = now + 1_000_000
        clock.observe(PcmPresentation(528, recoveredAt, true, 1), stats, recoveredAt)
        assertEquals("A projection within the queue must not discard an earlier unplayed span",
            VideoPresentation.At(recoveredAt + 4_000_000),
            clock.videoPresentation(basePts + 25_000, recoveredAt))
    }

    @Test fun thirtyMinuteHardwareClockDriftDoesNotAccumulateSchedulingError() {
        val clock = AudioPresentationClock(true)
        val sampleRate = 48_000
        var written = 0L
        repeat(180_000) { index ->
            val time = now + index * 10_000_000L
            // Device output runs 80 ppm faster. Real frame/timestamp observations continuously re-anchor it.
            val presented = (index * 480L * 1.00008).toLong()
            while (written < presented + 4_800) {
                clock.record(written, 480, basePts + written * 1_000_000L / sampleRate, sampleRate)
                written += 480
            }
            clock.observe(PcmPresentation(presented, time, true, 0),
                PcmPlaybackStats(0, written, 0), time)
            val videoPts = basePts + presented * 1_000_000L / sampleRate + 50_000
            val presentation = clock.videoPresentation(videoPts, time)
            assertTrue(presentation is VideoPresentation.At)
            assertTrue(kotlin.math.abs((presentation as VideoPresentation.At).nanoTime - time - 50_000_000) <= 1_000)
        }

    }

    @Test fun longSessionsUnwrapUnsignedFrameCountersAndFlushResetsTheirEpoch() {
        val counter = FramePositionCounter()
        assertEquals(0xfffffff0L, counter.update(0xfffffff0L))
        assertEquals(0x100000010L, counter.update(0x10))
        assertEquals(0x200000020L, counter.update(0x200000020L))
        counter.reset()
        assertEquals(10L, counter.update(10))
    }
}
