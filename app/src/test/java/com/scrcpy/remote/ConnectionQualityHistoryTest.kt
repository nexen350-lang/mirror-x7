package com.scrcpy.remote

import com.scrcpy.remote.session.ConnectionQualityHistory
import com.scrcpy.remote.session.ConnectionQualitySnapshot
import com.scrcpy.remote.session.LatencyStats
import com.scrcpy.remote.session.MediaTimingDiagnostics
import com.scrcpy.remote.session.VideoFrameStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionQualityHistoryTest {
    @Test fun aPanelThatHasNeverOpenedCollectsNothing() {
        val snapshot = ConnectionQualityHistory().record(500)
        assertFalse(snapshot.collecting)
        assertTrue(snapshot.samples.isEmpty())
    }

    @Test fun onlyTheLatest120PointsWithin60SecondsAreRetained() {
        val history = watching()
        repeat(1_000) { history.record(it * 500L, skipped = it.toLong()) }
        val snapshot = history.snapshot(499_500)
        assertEquals(120, snapshot.samples.size)
        assertEquals(440_000L, snapshot.samples.first().elapsedMillis)
        assertEquals(499_500L, snapshot.samples.last().elapsedMillis)
        assertNull(snapshot.samples.first().skippedDelta)
        assertNull(snapshot.samples.first().intervalMillis)
        assertEquals(119L, snapshot.samples.sumOf { it.skippedDelta ?: 0 })
        assertTrue(snapshot.samples.zipWithNext().all { (first, second) ->
            second.elapsedMillis - first.elapsedMillis == 500L
        })
    }

    @Test fun sixtySecondBoundaryExpiresWithoutNeedingAnotherMeasurement() {
        val history = watching()
        history.record(0)
        history.record(500)
        val closed = history.setWatching(false, 60_000)
        assertFalse(closed.collecting)
        assertEquals(listOf(500L), closed.samples.map { it.elapsedMillis })
        assertNull(closed.samples.single().skippedDelta)
        assertTrue(history.snapshot(60_500).samples.isEmpty())
    }

    @Test fun unusuallyFrequentCallsAreStillBounded() {
        val history = watching()
        repeat(1_000) { history.record(it.toLong()) }
        assertEquals(120, history.snapshot(999).samples.size)
    }

    @Test fun returnedSnapshotsDoNotChangeWhenTheHistoryChanges() {
        val history = watching()
        val first = history.record(0)
        history.record(500)
        history.reset(1_000)
        assertEquals(1, first.samples.size)
        assertEquals(0L, first.samples.single().elapsedMillis)
    }

    @Test fun identicalValuesAtDifferentTicksRemainSeparateObservations() {
        val history = watching()
        val first = history.record(0)
        val second = history.record(500)
        assertNotEquals(first, second)
        assertEquals(2, second.samples.size)
        assertEquals(listOf(30, 30), second.samples.map { it.renderedFps })
    }

    @Test fun duplicateTimestampDoesNotInventAnotherIntervalOrReplaceItsBaseline() {
        val history = watching()
        history.record(0, skipped = 10)
        assertEquals(1, history.record(0, skipped = 20).samples.size)
        assertEquals(15L, history.record(500, skipped = 25).samples.last().skippedDelta)
    }

    @Test fun renderedRateUsesTheExistingWindowNotLifetimeCounters() {
        val history = watching()
        val sample = history.sample(
            0,
            VideoFrameStats(
                receivedFps = 120, decodedFps = 60, surfaceFps = 7,
                receivedFrames = 10_000, decodedFrames = 9_000, presentedFrames = 8_000,
                surfaceFrames = 7_000,
            ),
            LatencyStats(samples = 3, p95Millis = 23),
            MediaTimingDiagnostics(),
            audioAvailable = false,
        ).samples.single()
        assertEquals(7, sample.renderedFps)
        assertEquals(23, sample.clientP95Millis)
    }

    @Test fun neverRenderedAndNoLatencySamplesStayMissingInsteadOfBecomingZeros() {
        val history = watching()
        val sample = history.sample(
            0,
            VideoFrameStats(receivedFrames = 10, decodedFrames = 10, presentedFrames = 10),
            LatencyStats(),
            MediaTimingDiagnostics(),
            audioAvailable = false,
        ).samples.single()
        assertNull(sample.renderedFps)
        assertNull(sample.clientP95Millis)
        assertNull(sample.audioQueuedMillis)
        assertNull(sample.underrunDelta)
        assertNull(sample.skippedDelta)
    }

    @Test fun aStaticScreenAfterRenderingIsAnObservedZeroNotMissing() {
        val sample = watching().sample(
            0, VideoFrameStats(surfaceFrames = 1), LatencyStats(),
            MediaTimingDiagnostics(audioQueuedMs = 0, outputQueuedMs = 0),
            audioAvailable = true,
        ).samples.single()
        assertEquals(0, sample.renderedFps)
        assertEquals(0, sample.audioQueuedMillis)
        assertNull(sample.clientP95Millis)
    }

    @Test fun zeroLatencyAndZeroCounterChangesWithObservationsAreNotMissing() {
        val history = watching()
        history.sample(0, VideoFrameStats(surfaceFrames = 1), LatencyStats(samples = 1),
            MediaTimingDiagnostics(outputQueuedMs = 0), audioAvailable = true)
        val next = history.sample(500, VideoFrameStats(surfaceFrames = 1), LatencyStats(samples = 1),
            MediaTimingDiagnostics(outputQueuedMs = 0), audioAvailable = true).samples.last()
        assertEquals(0, next.clientP95Millis)
        assertEquals(0L, next.skippedDelta)
        assertEquals(0, next.underrunDelta)
    }

    @Test fun disabledOrFailedAudioCannotReuseStaleQueueOrUnderrunCounters() {
        val history = watching()
        history.record(0, underruns = 10)
        history.record(500, underruns = 12)
        val unavailable = history.record(1_000, underruns = 90, audioAvailable = false).samples.last()
        assertNull(unavailable.audioQueuedMillis)
        assertNull(unavailable.underrunDelta)
        assertNull(history.record(1_500, underruns = 100).samples.last().underrunDelta)
        assertEquals(1, history.record(2_000, underruns = 101).samples.last().underrunDelta)
    }

    @Test fun missingOutputPresentationDoesNotInventAnUnderrunMeasurement() {
        val history = watching()
        history.record(0, underruns = 10)
        val sample = history.sample(
            500, VideoFrameStats(surfaceFrames = 1), LatencyStats(),
            MediaTimingDiagnostics(audioQueuedMs = 12, underruns = 10),
            audioAvailable = true,
        ).samples.last()
        assertEquals(12, sample.audioQueuedMillis)
        assertNull(sample.underrunDelta)
        assertNull(history.record(1_000, underruns = 15).samples.last().underrunDelta)
    }

    @Test fun firstCumulativeCountsAreBaselinesAndOnlySubsequentIncreasesAreDeltas() {
        val history = watching()
        val first = history.record(0, skipped = 5_000_000_000L, underruns = 300).samples.single()
        assertNull(first.skippedDelta)
        assertNull(first.underrunDelta)
        val next = history.record(500, skipped = 5_000_000_007L, underruns = 302).samples.last()
        assertEquals(7L, next.skippedDelta)
        assertEquals(2, next.underrunDelta)
        assertEquals(500L, next.intervalMillis)
    }

    @Test fun counterResetsAreMissingDeltasAndThenEstablishNewBaselines() {
        val history = watching()
        history.record(0, skipped = 100, underruns = 10)
        val reset = history.record(500, skipped = 0, underruns = 0).samples.last()
        assertNull(reset.skippedDelta)
        assertNull(reset.underrunDelta)
        val next = history.record(1_000, skipped = 3, underruns = 1).samples.last()
        assertEquals(3L, next.skippedDelta)
        assertEquals(1, next.underrunDelta)
    }

    @Test fun closedPanelsCollectNothingAndReopeningRetainsOnlyUnexpiredPoints() {
        val history = watching()
        history.record(0, skipped = 100, underruns = 10)
        history.record(500, skipped = 103, underruns = 11)
        history.setWatching(false, 600)
        assertEquals(2, history.record(1_000, skipped = 150, underruns = 20).samples.size)
        val reopened = history.setWatching(true, 5_000)
        assertEquals(2, reopened.samples.size)
        val first = history.record(5_000, skipped = 200, underruns = 30).samples.last()
        assertNull(first.skippedDelta)
        assertNull(first.underrunDelta)
        assertNull(first.intervalMillis)
        val next = history.record(5_500, skipped = 201, underruns = 32).samples.last()
        assertEquals(1L, next.skippedDelta)
        assertEquals(2, next.underrunDelta)
        history.setWatching(false, 6_000)
        assertTrue(history.setWatching(true, 66_000).samples.isEmpty())
    }

    @Test fun reopeningInTheSameMillisecondStillRebasesCounters() {
        val history = watching()
        history.record(0, skipped = 10)
        history.record(500, skipped = 11)
        history.setWatching(false, 500)
        history.setWatching(true, 500)
        history.record(500, skipped = 50)
        assertNull(history.record(1_000, skipped = 60).samples.last().skippedDelta)
        assertEquals(2L, history.record(1_500, skipped = 62).samples.last().skippedDelta)
    }

    @Test fun repeatedWatchingNotificationDoesNotLoseAValidInterval() {
        val history = watching()
        history.record(0, skipped = 10)
        history.setWatching(true, 100)
        assertEquals(2L, history.record(500, skipped = 12).samples.last().skippedDelta)
    }

    @Test fun longSamplingGapsAreNotInterpolatedOrCountedAsMeasuredIntervals() {
        val history = watching()
        history.record(0, skipped = 10, underruns = 1)
        val gap = history.record(10_000, skipped = 500, underruns = 20)
        assertEquals(2, gap.samples.size)
        assertNull(gap.samples.last().intervalMillis)
        assertNull(gap.samples.last().skippedDelta)
        assertNull(gap.samples.last().underrunDelta)
        assertEquals(1L, history.record(10_500, skipped = 501, underruns = 20).samples.last().skippedDelta)
    }

    @Test fun sessionResetClearsPointsAndCountersWithoutClosingThePanel() {
        val history = watching()
        history.record(0, skipped = 100, underruns = 10)
        val reset = history.reset(500)
        assertTrue(reset.collecting)
        assertTrue(reset.samples.isEmpty())
        val first = history.record(500, skipped = 500, underruns = 50).samples.single()
        assertNull(first.skippedDelta)
        assertNull(first.underrunDelta)
    }

    @Test fun backwardClockStartsAFreshLocalWindowInsteadOfRetainingFuturePoints() {
        val history = watching()
        history.record(2_000, skipped = 50)
        history.record(2_500, skipped = 60)
        val reset = history.record(100, skipped = 70)
        assertEquals(listOf(100L), reset.samples.map { it.elapsedMillis })
        assertNull(reset.samples.single().skippedDelta)
        assertEquals(1L, history.record(600, skipped = 71).samples.last().skippedDelta)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeElapsedTimeIsNotAcceptedAsALocalTimestamp() {
        watching().record(-1)
    }

    @Test fun aMissingDecoderClearsTheCounterBaselineRatherThanInferringActivity() {
        val history = watching()
        history.record(0, skipped = 100)
        val absent = history.sample(500, null, null, null, audioAvailable = false).samples.last()
        assertNull(absent.renderedFps)
        assertNull(absent.clientP95Millis)
        assertNull(absent.skippedDelta)
        assertNull(history.record(1_000, skipped = 200).samples.last().skippedDelta)
    }

    private fun watching() = ConnectionQualityHistory().apply { setWatching(true, 0) }

    private fun ConnectionQualityHistory.record(
        at: Long,
        skipped: Long = 0,
        underruns: Int = 0,
        audioAvailable: Boolean = true,
    ): ConnectionQualitySnapshot = sample(
        at,
        VideoFrameStats(surfaceFps = 30, surfaceFrames = 100, skippedFrames = skipped),
        LatencyStats(samples = 10, p95Millis = 25),
        MediaTimingDiagnostics(audioQueuedMs = 20, outputQueuedMs = 10, underruns = underruns),
        audioAvailable,
    )
}
