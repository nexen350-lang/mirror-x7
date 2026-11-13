package com.scrcpy.remote

import com.scrcpy.remote.session.VideoFrameMeter
import org.junit.Assert.*
import org.junit.Test

class VideoFrameStatsTest {
    @Test fun countersSeparateReceivedDecodedAndSurfaceEvents() {
        var now = 0L
        val meter = VideoFrameMeter { now }
        repeat(60) { index ->
            now = index * 1_000_000_000L / 60
            meter.received()
            if (index % 2 == 0) { meter.decoded(); meter.presented(); meter.rendered(now) }
        }
        val result = meter.snapshot()
        assertEquals(60, result.receivedFps)
        assertEquals(30, result.decodedFps)
        assertEquals(30, result.surfaceFps)
        assertEquals(30L, result.presentedFrames)
        assertEquals(0L, result.skippedFrames)
    }

    @Test fun delayedCallbacksUseRenderTimeAndStaticScreensDecayToZero() {
        var now = 2_000_000_000L
        val meter = VideoFrameMeter { now }
        meter.rendered(500_000_000)
        assertEquals(0, meter.snapshot().surfaceFps)
        meter.received()
        meter.decoded()
        meter.skipped()
        assertEquals(1, meter.snapshot().decodedFps)
        now += 1_000_000_000
        val idle = meter.snapshot()
        assertEquals(0, idle.receivedFps)
        assertEquals(0, idle.decodedFps)
        assertEquals(1L, idle.receivedFrames)
        assertEquals(1L, idle.skippedFrames)
    }

    @Test fun burstsHaveBoundedWindowMemoryButAccurateLifetimeCounts() {
        val meter = VideoFrameMeter { 0L }
        repeat(10_000) { meter.received() }
        val value = meter.snapshot()
        assertEquals(512, value.receivedFps)
        assertEquals(10_000L, value.receivedFrames)
    }
}
