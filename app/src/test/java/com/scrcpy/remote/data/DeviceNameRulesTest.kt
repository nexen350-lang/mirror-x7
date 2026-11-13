package com.scrcpy.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DeviceNameRulesTest {
    private fun device(name: String) = SavedDevice(id = "a", name = name, host = "localhost", port = 5555)

    @Test fun spoofingAndInvisibleNamesAreRejected() {
        for (name in listOf("Ph\u202Eone", "Ph\u200Bone", "Ph\none", "Ph\u0000one", "Ph\u2028one", "Ph\u3164one", "\u200D", "a\u200D", "\u200Da", "a\u200D\u200Db")) {
            assertNotNull(name, SettingsValidation.deviceError(device(name)))
        }
    }

    @Test fun ordinaryNamesAreAccepted() {
        for (name in listOf("휴대폰", "Phone 📱", "Pixel \u2764\uFE0F", "Desk phone", "a\u200Db", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")) {
            assertNull(name, SettingsValidation.deviceError(device(name)))
        }
    }

    @Test fun backupValidationRejectsSuchNamesWithTheCodecError() {
        val snapshot = ConfigurationSnapshot(listOf(device("Ph\u202Eone")), MirrorSettings())
        val error = assertThrows(IOException::class.java) { ProfileBackupCodec.validateSnapshot(snapshot) }
        assertEquals(BackupFormatReason.INVALID_DATA, (error as BackupFormatException).reason)
    }
}
