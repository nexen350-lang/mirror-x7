package com.scrcpy.remote

import com.scrcpy.remote.session.SessionGeneration
import org.junit.Assert.*
import org.junit.Test

class SessionGenerationTest {
    @Test fun delayedErrorFromOldConnectionCannotAffectReconnect() {
        val generations = SessionGeneration()
        val old = generations.next()
        assertTrue(generations.accepts(old))
        generations.next() // disconnect invalidates immediately
        val reconnected = generations.next()
        assertFalse(generations.accepts(old))
        assertTrue(generations.accepts(reconnected))
    }
    @Test fun delayedFrameCannotResurrectDisconnectedUi() {
        val generations = SessionGeneration()
        val token = generations.next()
        generations.next()
        var resurrected = false
        if (generations.accepts(token)) resurrected = true
        assertFalse(resurrected)
    }
}
