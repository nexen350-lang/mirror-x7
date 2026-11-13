package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class AppPackageTest {

    private fun encoded(message: ControlMessage): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(message::writeTo)
        return bytes.toByteArray()
    }

    @Test fun ordinaryPackageNamesAreSendable() {
        for (name in listOf("com.example.app", "com.example", "a", "a.b.c.d_e.f9")) {
            assertTrue(name, AppPackage.isSendable(name))
        }
    }

    @Test fun valuesThatAreNotPackageNamesAreRejected() {
        for (name in listOf("", " ", "com.example app", "1com.example", ".com.example",
            "com..example", "com.example.", "com/example", "+com.example", "?com.example")) {
            assertFalse(name, AppPackage.isSendable(name))
        }
    }

    @Test fun aNameTooLongForTheLengthByteIsRejectedBeforeItCanKillTheSession() {
        // START_APP writes its length as one byte, and Android allows package names right up
        // against that limit. Serializing an over-long one throws on the control writer, which
        // aborts the whole control transport rather than just failing this one action.
        val longest = "a".repeat(AppPackage.MAX_BYTES)
        assertTrue(AppPackage.isSendable(longest))
        assertFalse(AppPackage.isSendable("a".repeat(AppPackage.MAX_BYTES + 1)))

        // The force-stop prefix costs one of those bytes.
        assertFalse(AppPackage.isSendable(longest, forceStop = true))
        assertTrue(AppPackage.isSendable("a".repeat(AppPackage.MAX_BYTES - 1), forceStop = true))
    }

    @Test fun theRejectedCasesAreExactlyTheOnesThatWouldThrowWhenSerialized() {
        // Ties the guard to the real failure rather than to a guessed limit.
        val longest = "a".repeat(AppPackage.MAX_BYTES)
        assertEquals(AppPackage.MAX_BYTES + 2, encoded(ControlMessage.startApp(longest)).size)
        assertThrows(IllegalArgumentException::class.java) {
            encoded(ControlMessage.startApp(longest, forceStop = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encoded(ControlMessage.startApp("a".repeat(AppPackage.MAX_BYTES + 1)))
        }
    }

    @Test fun anUnusableStartupAppIsReportedAsASettingsErrorBeforeConnecting() {
        assertTrue(SettingsValidation.settingsErrors(MirrorSettings()).isEmpty())
        assertTrue(
            SettingsValidation.settingsErrors(MirrorSettings(startApp = "com.example.app")).isEmpty()
        )
        for (bad in listOf("", "not a package", "a".repeat(AppPackage.MAX_BYTES + 1))) {
            assertEquals(
                bad,
                setOf("startApp"),
                SettingsValidation.settingsErrors(MirrorSettings(startApp = bad)).keys,
            )
        }
    }
}
