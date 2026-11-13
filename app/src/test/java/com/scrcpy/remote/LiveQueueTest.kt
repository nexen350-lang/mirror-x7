package com.scrcpy.remote

import com.scrcpy.remote.session.ExtendableDeadline
import com.scrcpy.remote.session.drainChannel
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQueueTest {
    @Test fun drainEmptiesTheQueue() {
        val c = Channel<Int>(Channel.UNLIMITED)
        repeat(5) { c.trySend(it) }
        drainChannel(c)
        assertTrue(c.tryReceive().isFailure)
    }

    @Test fun deadlineExtendedAgainAfterHandshakeGrowsAgain() {
        var now = 0L
        val d = ExtendableDeadline(45_000) { now }
        now = 40_000L * 1_000_000
        d.extendBy(70_000)
        now += 60_000L * 1_000_000
        assertEquals(10_000, d.remainingMillis())
        d.extendBy(45_000)
        assertEquals(45_000, d.remainingMillis())
    }
}
