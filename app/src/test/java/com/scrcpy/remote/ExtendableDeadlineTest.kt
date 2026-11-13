package com.scrcpy.remote

import com.scrcpy.remote.session.ExtendableDeadline
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendableDeadlineTest {
    @Test fun finishesWithinTheDeadline() = runBlocking {
        assertEquals(7, ExtendableDeadline(1_000).run { delay(20); 7 })
    }

    @Test fun expiresWhenNotExtended() {
        assertThrows(ExtendableDeadline.Expired::class.java) {
            runBlocking { ExtendableDeadline(150).run { delay(2_000) } }
        }
    }

    @Test fun anExtensionDuringTheWaitLetsItFinish() = runBlocking {
        val deadline = ExtendableDeadline(150)
        val result = deadline.run {
            delay(50)
            deadline.extendBy(1_000) // the device starts asking its user
            delay(400)               // longer than the original 150 ms
            "connected"
        }
        assertEquals("connected", result)
    }

    @Test fun extendingNeverShortens() {
        val deadline = ExtendableDeadline(10_000)
        deadline.extendBy(10)
        assertTrue(deadline.remainingMillis() > 5_000)
    }

    @Test fun anExtendedDeadlineIsStillBounded() {
        val deadline = ExtendableDeadline(50)
        val start = System.nanoTime()
        assertThrows(ExtendableDeadline.Expired::class.java) {
            runBlocking { deadline.run { deadline.extendBy(200); delay(5_000) } }
        }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }
}
