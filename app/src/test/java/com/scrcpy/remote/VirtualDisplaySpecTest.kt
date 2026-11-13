package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.VirtualDisplaySpec
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplaySpecTest {

    @Test fun everyFormTheServerDocumentsIsAccepted() {
        // Matches Options.parseNewDisplay: "", "<w>x<h>", "<w>x<h>/<dpi>", "/<dpi>".
        for (spec in listOf("", "1280x720", "1280x720/240", "/240", "2560x1440/560")) {
            assertNull(spec, VirtualDisplaySpec.error(spec))
        }
    }

    @Test fun malformedValuesAreExplainedInsteadOfFailingTheConnection() {
        // The server throws before opening a socket, which the client could only show as a
        // generic connection failure.
        for (spec in listOf("1280", "1280*720", "x720", "1280x", "abc", "1280x720/", "/", "//240")) {
            assertNotNull(spec, VirtualDisplaySpec.error(spec))
        }
    }

    @Test fun sizesAndDensitiesOutsideUsableBoundsAreRejected() {
        assertNotNull(VirtualDisplaySpec.error("1x1"))
        assertNotNull(VirtualDisplaySpec.error("99999x99999"))
        assertNotNull(VirtualDisplaySpec.error("/1"))
        assertNotNull(VirtualDisplaySpec.error("/99999"))
        assertNotNull(VirtualDisplaySpec.error("1280x720/0"))
    }

    @Test fun spacingAndCapitalisationAreNormalisedRatherThanRejected() {
        assertEquals("1280x720/240", VirtualDisplaySpec.normalize(" 1280X720 / 240 "))
        assertNull(VirtualDisplaySpec.error(" 1280X720/240 "))
    }

    @Test fun theOptionIsOnlySentWhenAVirtualDisplayIsRequested() {
        assertTrue(ScrcpyOptions().toServerArgs().none { it.startsWith("new_display") })
        assertTrue(ScrcpyOptions(newDisplay = "").toServerArgs().contains("new_display="))
        assertTrue(
            ScrcpyOptions(newDisplay = "1280x720/240").toServerArgs().contains("new_display=1280x720/240")
        )
    }

    @Test fun aVirtualDisplayReplacesTheDisplayIdRatherThanAccompanyingIt() {
        // The server asserts these are never both set and forces the display id to "none".
        val args = ScrcpyOptions(displayId = 2, newDisplay = "/240").toServerArgs()
        assertTrue(args.toString(), args.contains("new_display=/240"))
        assertTrue(args.toString(), args.none { it.startsWith("display_id") })

        val mirroring = ScrcpyOptions(displayId = 2).toServerArgs()
        assertTrue(mirroring.contains("display_id=2"))
    }

    @Test fun aVirtualDisplayKeepsTheSoftKeyboardOnTheDisplayBeingMirrored() {
        // Verified on a device: the default policy puts the IME on display 0, where the client
        // cannot see it and the person holding the device can.
        assertTrue(
            ScrcpyOptions(newDisplay = "").toServerArgs().contains("display_ime_policy=local")
        )
        assertTrue(
            "Mirroring the real screen must not change the device's IME behaviour",
            ScrcpyOptions().toServerArgs().none { it.startsWith("display_ime_policy") },
        )
    }

    @Test fun theSettingReachesTheServerOptionsNormalised() {
        assertNull(MirrorSettings().toOptions().newDisplay)
        assertEquals("", MirrorSettings(virtualDisplay = "").toOptions().newDisplay)
        assertEquals(
            "1280x720/240",
            MirrorSettings(virtualDisplay = " 1280X720/240 ").toOptions().newDisplay,
        )
    }

    @Test fun anInvalidSpecIsReportedAsASettingsErrorBeforeConnecting() {
        assertTrue(SettingsValidation.settingsErrors(MirrorSettings()).isEmpty())
        assertTrue(SettingsValidation.settingsErrors(MirrorSettings(virtualDisplay = "")).isEmpty())
        assertEquals(
            setOf("virtualDisplay"),
            SettingsValidation.settingsErrors(MirrorSettings(virtualDisplay = "1280")).keys,
        )
    }
}
