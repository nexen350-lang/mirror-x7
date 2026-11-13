package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.StreamingPreset
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.ui.LinkRequest
import com.scrcpy.remote.ui.QuickConnectConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectTest {

    @Test
    fun defaultConfigUsesBalancedPresetAndValidEndpoint() {
        val config = QuickConnectConfig(host = "192.168.1.50")
        assertEquals(StreamingPreset.BALANCED, config.preset)
        assertEquals(5555, config.port)
        assertNull(config.validate())

        val parsed = LinkRequest.parseQuery(config.toLinkOptions())
        val applied = parsed.applyTo(MirrorSettings())
        assertEquals(StreamingPreset.BALANCED.maxSize, applied.maxSize)
        assertEquals(StreamingPreset.BALANCED.videoBitRateMbps, applied.videoBitRateMbps)
        assertEquals(StreamingPreset.BALANCED.maxFps, applied.maxFps)
        assertTrue(applied.audioEnabled)
        assertEquals(AudioSource.OUTPUT, applied.audioSource)
        assertFalse(applied.turnScreenOff)
        assertFalse(applied.stayAwake)
    }

    @Test
    fun responsivePresetAndAdvancedFlagsRoundTripThroughLinkQuery() {
        val config = QuickConnectConfig(
            host = "10.0.0.12",
            port = 41235,
            preset = StreamingPreset.MOST_RESPONSIVE,
            audioEnabled = false,
            turnScreenOff = true,
            stayAwake = true,
        )
        assertNull(config.validate())

        val parsed = LinkRequest.parseQuery(config.toLinkOptions())
        val applied = parsed.applyTo(MirrorSettings())
        assertEquals(StreamingPreset.MOST_RESPONSIVE.maxSize, applied.maxSize)
        assertEquals(StreamingPreset.MOST_RESPONSIVE.videoBitRateMbps, applied.videoBitRateMbps)
        assertFalse(applied.audioEnabled)
        assertTrue(applied.turnScreenOff)
        assertTrue(applied.stayAwake)
    }

    @Test
    fun blankOrInvalidEndpointFailsValidation() {
        assertNotNull(QuickConnectConfig(host = "").validate())
        assertNotNull(QuickConnectConfig(host = "192.168.1.10", port = 0).validate())
        assertNotNull(QuickConnectConfig(host = "192.168.1.10", port = 70000).validate())
    }
}
