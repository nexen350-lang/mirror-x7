package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException
import com.scrcpy.remote.session.AppBundle
import com.scrcpy.remote.session.MirrorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class BundleLimitsTest {
    @Test fun copyBoundedCountsRealBytes() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, AppBundle.copyBounded(ByteArrayInputStream(ByteArray(10)), out, 10))
        try {
            AppBundle.copyBounded(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), 10)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test fun fitsWithinChecksTheRunningTotal() {
        fun p(size: Long) = AppBundle.Part("a", null, size)
        assertTrue(AppBundle.fitsWithin(listOf(p(5), p(5)), 10))
        assertFalse(AppBundle.fitsWithin(listOf(p(6), p(5)), 10))
        assertFalse(AppBundle.fitsWithin(listOf(p(Long.MAX_VALUE), p(Long.MAX_VALUE)), 10))
        assertFalse(AppBundle.fitsWithin(listOf(p(-1)), 10))
    }

    @Test fun onlyTheDevicesOwnRefusalOfCamerasIsShown() {
        assertNull(MirrorViewModel.cameraListFailureMessage(IOException("Unexpected end of sync stream")))
        assertNull(MirrorViewModel.cameraListFailureMessage(IOException()))
        assertEquals("Camera mirroring is not supported",
            MirrorViewModel.cameraListFailureMessage(IOException("Camera mirroring is not supported")))
        assertEquals("encoder missing", MirrorViewModel.cameraListFailureMessage(ScrcpyServerRefusedException("encoder missing")))
    }

    @Test fun onDemandPartsDoNotMakeABundleUnmatchable() {
        val variant = AppBundle.Variant(0, listOf(
            AppBundle.Part("base.apk", null, 1),
            AppBundle.Part("tex.apk", "config.astc", 1, onDemand = true),
        ))
        val bundle = AppBundle.complete(AppBundle.Format.APK_SET, "p", listOf(variant), 2, "base.apk", false)
        assertNull(bundle.problem)
        assertTrue(bundle.unmatchable.isEmpty())
    }
}
