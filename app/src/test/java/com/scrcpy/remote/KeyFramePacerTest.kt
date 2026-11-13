package com.scrcpy.remote

import com.scrcpy.remote.session.KeyFramePacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFramePacerTest {
    @Test fun theFirstRequestGoesAtOnce() {
        assertEquals(0L, KeyFramePacer(250).request(1_000))
    }

    @Test fun aRequestTooSoonAfterTheLastIsDelayedNotDropped() {
        // Dropped, a refused key frame could leave a recording waiting for the device's own next
        // one, ten seconds away.
        val pacer = KeyFramePacer(250)
        pacer.request(1_000)
        assertEquals(150L, pacer.request(1_100))
    }

    @Test fun requestsWhileOneIsWaitingBecomeThatOne() {
        val pacer = KeyFramePacer(250)
        pacer.request(1_000)
        assertEquals(150L, pacer.request(1_100))
        assertNull("Already waiting", pacer.request(1_200))
        pacer.fired(1_250)
        assertEquals(200L, pacer.request(1_300))
    }

    @Test fun afterTheSpacingARequestGoesAtOnce() {
        val pacer = KeyFramePacer(250)
        pacer.request(1_000)
        assertEquals(0L, pacer.request(1_300))
    }

    @Test fun anEndedRecordingWaitsForNothing() {
        val pacer = KeyFramePacer(250)
        pacer.request(1_000)
        pacer.request(1_100)
        pacer.reset()
        assertEquals(0L, pacer.request(1_150))
    }
}
