package com.scrcpy.remote

import com.scrcpy.remote.session.VideoOutputBatch
import com.scrcpy.remote.session.VideoPresentation
import org.junit.Assert.*
import org.junit.Test

class VideoOutputBatchTest {
    @Test fun synchronizedFramesThatAreNotDueYetMustAllBePresented() {
        val released = mutableListOf<Pair<Int, VideoPresentation?>>()
        val batch = VideoOutputBatch({ pts -> VideoPresentation.At(1_000_000_000L + pts * 1_000) },
            { index, presentation -> released += index to presentation })
        repeat(4) { batch.offer(it, it * 16_667L) }
        batch.finish()
        assertEquals("A four-frame decoder burst must not become one displayed frame", 4, released.count { it.second != null })
        assertEquals((0..3).toList(), released.map { it.first })
    }

    @Test fun lowLatencyStillCoalescesAlreadyDueFramesToTheLatest() {
        val released = mutableListOf<Pair<Int, VideoPresentation?>>()
        val batch = VideoOutputBatch({ VideoPresentation.Immediate },
            { index, presentation -> released += index to presentation })
        repeat(4) { batch.offer(it, it * 16_667L) }
        batch.finish()
        assertEquals(listOf(0 to null, 1 to null, 2 to null, 3 to VideoPresentation.Immediate), released)
        batch.finish()
        assertEquals(4, released.size)
    }

    @Test fun overdueFramesCatchUpBeforeFutureFramesWithoutChangingTheirDeadlines() {
        val released = mutableListOf<Pair<Int, VideoPresentation?>>()
        val batch = VideoOutputBatch(
            { pts -> if (pts < 2 || pts > 3) VideoPresentation.Immediate else VideoPresentation.At(pts * 1_000_000) },
            { index, presentation -> released += index to presentation })
        repeat(6) { batch.offer(it, it.toLong()) }
        batch.finish()
        assertEquals(listOf(
            0 to null, 1 to VideoPresentation.Immediate, 2 to VideoPresentation.At(2_000_000),
            3 to VideoPresentation.At(3_000_000), 4 to null, 5 to VideoPresentation.Immediate,
        ), released)
    }

    @Test fun sixtyFpsStreamInFourFrameBurstsKeepsSixtyScheduledFramesPerSecond() {
        var retained = 0
        var skipped = 0
        val deadlines = mutableListOf<Long>()
        repeat(30) { burst ->
            val batch = VideoOutputBatch({ pts -> VideoPresentation.At(pts * 1_000 + 200_000_000) },
                { _, target ->
                    if (target is VideoPresentation.At) { retained++; deadlines += target.nanoTime }
                    else skipped++
                })
            repeat(4) { frame -> batch.offer(frame, (burst * 4 + frame) * 1_000_000L / 60) }
            batch.finish()
        }
        assertEquals(120, retained)
        assertEquals(0, skipped)
        assertTrue(deadlines.zipWithNext().all { (first, next) -> next - first in 16_666_000..16_667_000 })
    }

    @Test fun emptyBatchDoesNotReleaseAnUnownedBuffer() {
        val batch = VideoOutputBatch({ error("No presentation is needed") }, { _, _ -> fail("No buffer is owned") })
        batch.finish()
        assertThrows(IllegalArgumentException::class.java) { batch.offer(-1, 0) }
    }
}
