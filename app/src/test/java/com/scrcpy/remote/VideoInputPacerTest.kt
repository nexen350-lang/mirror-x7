package com.scrcpy.remote

import com.scrcpy.remote.session.VideoInputPacer
import com.scrcpy.remote.session.VideoPresentation
import org.junit.Assert.*
import org.junit.Test

class VideoInputPacerTest {
    @Test fun syncedFramesWaitOutsideTheDecoderUntilTheyAreNearTheirDisplayTime() {
        val now = 1_000_000_000L
        val target = VideoPresentation.At(now + 250_000_000)
        assertFalse(VideoInputPacer.ready(target, now))
        assertFalse(VideoInputPacer.ready(target, now + 199_999_999))
        assertTrue(VideoInputPacer.ready(target, now + 200_000_000))
        assertEquals(now + 250_000_000, target.nanoTime)
    }

    @Test fun liveLateAndUnavailableAudioClocksNeverWait() {
        assertTrue(VideoInputPacer.ready(VideoPresentation.Immediate, 0))
        assertTrue(VideoInputPacer.ready(VideoPresentation.At(0), 1_000_000_000))
    }

    @Test fun maximumSyncDelayAt120FpsFitsTheBoundedCompressedQueue() {
        val delay = 500_000_000L
        val frameInterval = 1_000_000_000L / 120
        val waitingFrames = (delay - VideoInputPacer.DECODE_LEAD_NANOS) / frameInterval + 1
        assertTrue(waitingFrames + 2 <= VideoInputPacer.MAX_QUEUED_PACKETS)
    }
}
