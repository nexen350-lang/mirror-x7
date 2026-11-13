package com.scrcpy.remote

import com.scrcpy.remote.session.FrameLatencyMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLatencyMeterTest {

    private val ms = 1_000_000L

    @Test fun aFrameIsTimedFromItsOwnArrivalNotTheMostRecentOne() {
        // Frames arrive in a burst and render in order, so pairing by "latest arrival" would report
        // the last frame's wait for all of them and make a backlog look instant.
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 100, nanoTime = 0)
        meter.arrived(ptsUs = 200, nanoTime = 10 * ms)
        meter.arrived(ptsUs = 300, nanoTime = 20 * ms)
        meter.rendered(ptsUs = 100, nanoTime = 30 * ms)
        meter.rendered(ptsUs = 200, nanoTime = 32 * ms)
        meter.rendered(ptsUs = 300, nanoTime = 34 * ms)

        val stats = meter.snapshot(34 * ms)
        assertEquals(3, stats.samples)
        // 30, 22 and 14 ms; the median is the middle one.
        assertEquals(22, stats.medianMillis)
        assertEquals(30, stats.worstMillis)
    }

    @Test fun aFrameThatNeverRendersDoesNotLeakOrPoisonTheNumbers() {
        val meter = FrameLatencyMeter()
        repeat(500) { meter.arrived(ptsUs = it.toLong(), nanoTime = it.toLong() * ms) }
        // Only the last arrival is ever rendered; the rest were dropped by the decoder.
        meter.rendered(ptsUs = 499, nanoTime = 505 * ms)

        val stats = meter.snapshot(505 * ms)
        assertEquals("Only the rendered frame counts", 1, stats.samples)
        assertEquals(6, stats.medianMillis)

        // An arrival evicted long ago must not pair up if its timestamp comes round again.
        meter.rendered(ptsUs = 0, nanoTime = 600 * ms)
        assertEquals(1, meter.snapshot(600 * ms).samples)
    }

    @Test fun samplesOlderThanTheWindowStopCountingSoTheFigureStaysCurrent() {
        val meter = FrameLatencyMeter(windowNanos = 100 * ms)
        meter.arrived(ptsUs = 1, nanoTime = 0)
        meter.rendered(ptsUs = 1, nanoTime = 90 * ms)
        assertEquals(1, meter.snapshot(90 * ms).samples)
        // Long enough later that the old sample has left the window.
        assertFalse(meter.snapshot(500 * ms).hasSamples)
    }

    @Test fun animpossiblePairingIsDiscardedRatherThanReportedAsAHugeNumber() {
        // A codec restart can reuse presentation timestamps. Pairing a new render against an old
        // arrival would otherwise report seconds of latency and make the figure useless.
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 7, nanoTime = 0)
        meter.rendered(ptsUs = 7, nanoTime = -5 * ms)
        assertFalse("A render before its arrival is not a sample", meter.snapshot(0).hasSamples)

        meter.arrived(ptsUs = 8, nanoTime = 0)
        meter.rendered(ptsUs = 8, nanoTime = 60_000 * ms)
        assertFalse("A minute of latency is not a real measurement",
            meter.snapshot(60_000 * ms).hasSamples)
    }

    @Test fun resetClearsEverythingSoANewStreamStartsFromNothing() {
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 1, nanoTime = 0)
        meter.rendered(ptsUs = 1, nanoTime = 5 * ms)
        assertTrue(meter.snapshot(5 * ms).hasSamples)
        meter.reset()
        assertFalse(meter.snapshot(5 * ms).hasSamples)
        // A render whose arrival was cleared must not pair against anything.
        meter.rendered(ptsUs = 1, nanoTime = 10 * ms)
        assertFalse(meter.snapshot(10 * ms).hasSamples)
    }

    @Test fun codecResetKeepsReceiptsForFramesStillWaitingOutsideTheDecoder() {
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 1, nanoTime = ms)
        // The reader can receive the new capture's keyframe before the worker consumes its
        // preceding configuration packet. That receipt belongs to the pump's packet.
        val receivedNanos = 10 * ms
        meter.reset()
        meter.arrived(ptsUs = 2, nanoTime = 110 * ms, receivedNanos = receivedNanos)
        meter.rendered(ptsUs = 1, nanoTime = 115 * ms)
        meter.rendered(ptsUs = 2, nanoTime = 120 * ms)

        val stats = meter.snapshot(120 * ms)
        assertEquals("The old codec's frame must not survive its reset", 1, stats.samples)
        assertEquals("Read-ahead is still part of the new frame's latency", 110, stats.medianMillis)
        assertEquals(100, stats.intakeP95Millis)
    }

    @Test fun oldCodecCallbacksCannotConsumeAReusedPresentationTimestamp() {
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 7, nanoTime = 10 * ms, receivedNanos = 0, codecEpoch = 1)
        meter.reset()
        meter.arrived(ptsUs = 7, nanoTime = 110 * ms, receivedNanos = 100 * ms, codecEpoch = 2)
        meter.rendered(ptsUs = 7, nanoTime = 112 * ms, codecEpoch = 1)
        assertFalse("A late callback from the old codec must not take the new frame", meter.snapshot(112 * ms).hasSamples)
        meter.rendered(ptsUs = 7, nanoTime = 120 * ms, codecEpoch = 2)
        val stats = meter.snapshot(120 * ms)
        assertEquals(1, stats.samples)
        assertEquals(20, stats.medianMillis)
        assertEquals(10, stats.intakeP95Millis)
    }

    @Test fun percentileAlwaysNamesARealSampleAndDoesNotRoundUpward() {
        // Nearest rank is ceil(p/100 * n), counting from one. Flooring instead lands one sample
        // too high, which for a hundred frames reports the 96th slowest as the 95th percentile and
        // for twenty reports the worst frame as though it were typical of the tail.
        for (size in 1..200) {
            val index = FrameLatencyMeter.percentileIndex(size, 95)
            assertTrue("size $size gave index $index", index in 0 until size)
        }
        assertEquals(0, FrameLatencyMeter.percentileIndex(1, 95))
        assertEquals(18, FrameLatencyMeter.percentileIndex(20, 95))
        assertEquals(94, FrameLatencyMeter.percentileIndex(100, 95))
        // Small windows have no 95th percentile distinct from their maximum, and saying so is
        // honest rather than a rounding artefact.
        assertEquals(9, FrameLatencyMeter.percentileIndex(10, 95))
        assertEquals(49, FrameLatencyMeter.percentileIndex(50, 99))
    }

    @Test fun millisecondsAreRoundedRatherThanTruncated() {
        assertEquals(0, FrameLatencyMeter.millis(400_000))
        assertEquals(1, FrameLatencyMeter.millis(500_000))
        assertEquals(2, FrameLatencyMeter.millis(1_600_000))
    }

    // --- The full client-side path ------------------------------------------------------------
    //
    // The meter used to start counting where the decoder accepted a frame, which is *after* the
    // packet has sat in the pump's queue and after any wait for a free codec input buffer. Both of
    // those grow precisely when the client is congested — the case a user reports as "sometimes
    // laggy" — so the instrument was blindest exactly where it was most needed. LatencyStats has
    // always described itself as "from the network to the screen"; this makes that true.

    @Test fun latencyIsMeasuredFromTheNetworkNotFromTheDecoder() {
        val meter = FrameLatencyMeter()
        // 40 ms between coming off the socket and the decoder accepting it: queued behind other
        // packets, or waiting for an input buffer to free.
        meter.arrived(ptsUs = 1, nanoTime = 40 * ms, receivedNanos = 0)
        meter.rendered(ptsUs = 1, nanoTime = 50 * ms)

        val stats = meter.snapshot(50 * ms)
        assertEquals(1, stats.samples)
        assertEquals("the wait before the decoder is part of what the user feels", 50, stats.medianMillis)
        assertEquals(40, stats.intakeP95Millis)
    }

    @Test fun intakeIsReportedSeparatelySoTheBottleneckIsIdentifiable() {
        // Two frames that took the same total time for opposite reasons: one waited to reach the
        // decoder, one decoded slowly. Without the split they are indistinguishable, and the user
        // cannot tell a backlog from a slow codec.
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 1, nanoTime = 30 * ms, receivedNanos = 0)
        meter.rendered(ptsUs = 1, nanoTime = 40 * ms)

        meter.arrived(ptsUs = 2, nanoTime = 2 * ms, receivedNanos = 0)
        meter.rendered(ptsUs = 2, nanoTime = 40 * ms)

        val stats = meter.snapshot(40 * ms)
        assertEquals(2, stats.samples)
        assertEquals(40, stats.worstMillis)
        // The worst intake is the backlogged frame, not the slow-decode one.
        assertEquals(30, stats.intakeP95Millis)
    }

    @Test fun aFrameNeverSeenAtTheSocketStillTimesFromTheDecoder() {
        // The fallback matters: a path that only stamps at the decoder must keep working exactly
        // as it did, rather than losing its samples because no receive stamp was ever recorded.
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 9, nanoTime = 0)
        meter.rendered(ptsUs = 9, nanoTime = 12 * ms)

        val stats = meter.snapshot(12 * ms)
        assertEquals(1, stats.samples)
        assertEquals(12, stats.medianMillis)
        // Nothing was waiting before the decoder, so the intake is zero rather than invented.
        assertEquals(0, stats.intakeP95Millis)
    }

    @Test fun aRepeatedTimestampRestartsTheMeasurementAtTheNewerReceipt() {
        // Replacing a submitted frame with the same PTS uses the replacement packet's receipt,
        // rather than a timestamp associated with the older payload.
        val meter = FrameLatencyMeter()
        meter.arrived(ptsUs = 5, nanoTime = 2 * ms, receivedNanos = 0)
        meter.arrived(ptsUs = 5, nanoTime = 102 * ms, receivedNanos = 100 * ms)
        meter.rendered(ptsUs = 5, nanoTime = 110 * ms)

        val stats = meter.snapshot(110 * ms)
        assertEquals(1, stats.samples)
        assertEquals(10, stats.medianMillis)
        assertEquals(2, stats.intakeP95Millis)
    }
}