package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.StreamingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPresetTest {

    @Test fun everyPresetIsSomethingTheRepositoryWillAccept() {
        // A preset writes straight into the saved settings, so one that fails validation would be
        // a button that cannot be saved.
        for (preset in StreamingPreset.entries) {
            val applied = preset.applyTo(MirrorSettings())
            assertEquals(preset.name, emptyMap<String, String>(), SettingsValidation.settingsErrors(applied))
        }
    }

    @Test fun everyPresetStaysInsideTheSlidersOwnRanges() {
        // The sliders are the other way of setting these, and a preset outside their range would
        // jump the moment the user touched one.
        for (preset in StreamingPreset.entries) {
            assertTrue(preset.name, preset.maxSize in SettingsValidation.MAX_SIZE_RANGE)
            assertTrue(preset.name, preset.videoBitRateMbps in SettingsValidation.BIT_RATE_RANGE)
            assertTrue(preset.name, preset.maxFps in SettingsValidation.FPS_RANGE)
        }
    }

    @Test fun theDefaultSettingsAreTheBalancedPreset() {
        // Otherwise a user who has changed nothing would be shown as "custom".
        assertEquals(StreamingPreset.BALANCED, StreamingPreset.of(MirrorSettings()))
    }

    @Test fun eachPresetIsRecognisedAfterItIsApplied() {
        for (preset in StreamingPreset.entries) {
            assertEquals(preset, StreamingPreset.of(preset.applyTo(MirrorSettings())))
        }
    }

    @Test fun settingsBetweenPresetsAreReportedAsCustom() {
        assertNull(StreamingPreset.of(MirrorSettings(maxSize = 1280, videoBitRateMbps = 7, maxFps = 45)))
    }

    @Test fun applyingAPresetLeavesEverythingElseAlone() {
        // It is a picture setting, not a reset: touch calibration and audio choices must survive.
        val original = MirrorSettings(
            touchOffsetXDp = 7, touchOffsetYDp = -3, audioEnabled = false, startApp = "com.example.app")
        val applied = StreamingPreset.MOST_RESPONSIVE.applyTo(original)
        assertEquals(original.touchOffsetXDp, applied.touchOffsetXDp)
        assertEquals(original.touchOffsetYDp, applied.touchOffsetYDp)
        assertEquals(original.audioEnabled, applied.audioEnabled)
        assertEquals(original.startApp, applied.startApp)
    }

    @Test fun theOrderRunsFromMostResponsiveToSharpest() {
        // The row is read left to right, so the list order has to match what it promises.
        val sizes = StreamingPreset.entries.map { it.maxSize }
        assertEquals(sizes.sorted(), sizes)
        val rates = StreamingPreset.entries.map { it.videoBitRateMbps }
        assertEquals(rates.sorted(), rates)
    }
}
