package com.scrcpy.remote

import com.scrcpy.remote.session.StorageGuard
import com.scrcpy.remote.session.lowLatencyRefusalApplies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingGuardsTest {
    @Test fun storageIsOnlySampledEveryNFramesAndFlagsLowSpace() {
        var free = 1_000L
        var reads = 0
        val guard = StorageGuard(reserveBytes = 100, everyFrames = 3) { reads++; free }
        assertFalse(guard.isLow()); assertFalse(guard.isLow())
        assertEquals(0, reads)
        assertFalse(guard.isLow())
        assertEquals(1, reads)
        free = 50
        assertFalse(guard.isLow()); assertFalse(guard.isLow())
        assertTrue(guard.isLow())
    }

    @Test fun defaultReserveIs64Mb() {
        assertEquals(64L * 1024 * 1024, StorageGuard.DEFAULT_RESERVE_BYTES)
        val guard = StorageGuard(everyFrames = 1) { StorageGuard.DEFAULT_RESERVE_BYTES - 1 }
        assertTrue(guard.isLow())
    }

    @Test fun lowLatencyRefusalIsLatchedOnlyForACurrentValidSurface() {
        assertTrue(lowLatencyRefusalApplies(surfaceValid = true, surfaceStillCurrent = true))
        assertFalse(lowLatencyRefusalApplies(surfaceValid = false, surfaceStillCurrent = true))
        assertFalse(lowLatencyRefusalApplies(surfaceValid = true, surfaceStillCurrent = false))
    }
}
