package com.scrcpy.remote

import com.scrcpy.remote.session.RecordingTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTimelineTest {

    @Test fun aRecordingOnlyOpensOnAKeyFrame() {
        // Opening anywhere else leaves the decoder with nothing to decode against, so the
        // recording begins as a broken picture.
        val timeline = RecordingTimeline()
        assertFalse(timeline.canOpenAt(isKeyFrame = false))
        assertTrue(timeline.canOpenAt(isKeyFrame = true))
    }

    @Test fun framesBeforeTheFileIsOpenAreDropped() {
        val timeline = RecordingTimeline()
        assertNull(timeline.sampleTimeUs(1_000))
    }

    @Test fun timesAreRebasedSoTheRecordingStartsAtZero() {
        // Device timestamps can be hours into its uptime; written as they are, a player shows a
        // recording that does not begin until far into the timeline.
        val timeline = RecordingTimeline()
        timeline.opened(9_000_000)
        assertEquals(0L, timeline.sampleTimeUs(9_000_000))
        assertEquals(33_000L, timeline.sampleTimeUs(9_033_000))
    }

    @Test fun aFrameThatWouldGoBackwardsIsNudgedPastTheLastOne() {
        // A muxer requires strictly increasing times. One odd frame should cost a microsecond,
        // not the whole recording.
        val timeline = RecordingTimeline()
        timeline.opened(0)
        assertEquals(0L, timeline.sampleTimeUs(0))
        assertEquals(100L, timeline.sampleTimeUs(100))
        assertEquals(101L, timeline.sampleTimeUs(50))
        assertEquals(102L, timeline.sampleTimeUs(100))
        assertEquals(200L, timeline.sampleTimeUs(200))
    }

    @Test fun aTimestampBeforeTheOpeningFrameIsClampedRatherThanNegative() {
        val timeline = RecordingTimeline()
        timeline.opened(5_000)
        assertEquals(0L, timeline.sampleTimeUs(4_000))
    }

    @Test fun closingLetsTheNextKeyFrameStartAFreshFile() {
        val timeline = RecordingTimeline()
        timeline.opened(1_000)
        assertTrue(timeline.isOpen)
        assertFalse("An open recording must not open again", timeline.canOpenAt(isKeyFrame = true))

        timeline.closed()
        assertFalse(timeline.isOpen)
        assertNull(timeline.sampleTimeUs(2_000))
        assertTrue(timeline.canOpenAt(isKeyFrame = true))

        // The new file rebases against its own opening frame, not the old one.
        timeline.opened(8_000)
        assertEquals(0L, timeline.sampleTimeUs(8_000))
    }
}
