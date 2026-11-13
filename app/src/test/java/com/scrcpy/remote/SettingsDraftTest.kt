package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.SettingsValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings editor keeps a draft across rotation, and a draft is allowed to be half-typed.
 *
 * Saving it must therefore never depend on the draft already being valid: the editor shows the
 * error and keeps the text, so the value that reaches the saver can legitimately be one the
 * repository would reject.
 */
class SettingsDraftTest {

    /** What the editor stores the moment "start app" is switched on, before anything is typed. */
    private val justSwitchedOn = MirrorSettings(startApp = "")

    @Test fun switchingStartAppOnProducesADraftTheRepositoryWouldReject() {
        // Establishes the premise: this really is an invalid draft, not a hypothetical one.
        assertTrue(SettingsValidation.settingsErrors(justSwitchedOn).containsKey("startApp"))
    }

    @Test fun ahalfTypedDraftCanBeSavedAndRestored() {
        // Encoding used to require a valid draft, so rotating with the field switched on and
        // still empty threw out of onSaveInstanceState and took the activity down with it.
        val encoded = SettingsCodec.encodeDraft(justSwitchedOn)
        val restored = SettingsCodec.decodeDraft(encoded)
        assertNotNull("A half-typed draft must survive rotation", restored)
        assertEquals("", restored!!.startApp)
    }

    @Test fun aPartiallyTypedPackageAndDisplaySizeAlsoSurvive() {
        val draft = MirrorSettings(startApp = "com", virtualDisplay = "12")
        val restored = SettingsCodec.decodeDraft(SettingsCodec.encodeDraft(draft))
        assertNotNull(restored)
        assertEquals("com", restored!!.startApp)
        assertEquals("12", restored.virtualDisplay)
    }

    @Test fun aValidDraftStillRoundTripsUnchanged() {
        val draft = MirrorSettings(maxSize = 1280, videoBitRateMbps = 12, startApp = "com.example.app")
        assertEquals(draft, SettingsCodec.decodeDraft(SettingsCodec.encodeDraft(draft)))
    }

    @Test fun theSavedValueEncoderStillRefusesAnInvalidValue() {
        // The saved-value path must keep rejecting: this is exactly what the draft saver used to
        // call, which is why rotating with a half-typed field threw out of onSaveInstanceState.
        assertThrows(IllegalArgumentException::class.java) {
            SettingsCodec.encodeSettings(justSwitchedOn)
        }
    }

    @Test fun aDraftThatIsNotSettingsAtAllIsRefusedRatherThanCrashing() {
        assertEquals(null, SettingsCodec.decodeDraft("not json"))
        assertEquals(null, SettingsCodec.decodeDraft(""))
    }
}
