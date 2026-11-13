package com.scrcpy.remote

import com.scrcpy.remote.data.BackupFormatException
import com.scrcpy.remote.data.BackupFormatReason
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceIcon
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.ProfileBackupCodec
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.ui.design.AvatarPalette
import com.scrcpy.remote.ui.design.avatarPaletteIndices
import com.scrcpy.remote.ui.design.deviceAvatarColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device's chosen look, and putting back a device that was just removed. */
class DeviceLookTest {
    private class MemoryStorage(initial: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = initial.toMutableMap()
        var writes = 0
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            writes++
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    private val phone = SavedDevice(id = "a", name = "Phone", host = "10.0.0.2")

    @Test fun aChosenLookIsSavedChangedByTheEditorAndKeptByOtherChanges() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        val styled = phone.copy(avatarColor = 3, avatarIcon = DeviceIcon.TABLET)
        assertTrue(repository.saveDevice(styled))
        assertEquals(styled, repository.devices.value.single())
        // The editor owns the look, as it owns the name and address.
        assertTrue(repository.saveDevice(styled.copy(avatarColor = 5, avatarIcon = null)))
        assertEquals(5, repository.devices.value.single().avatarColor)
        assertNull(repository.devices.value.single().avatarIcon)
        // A favourite or a calibration nudge leaves it alone.
        assertTrue(repository.setFavorite("a", true))
        assertTrue(repository.setCalibration("a", landscape = false, TouchCalibration(4, -4)))
        assertEquals(5, repository.devices.value.single().avatarColor)
        assertEquals(repository.devices.value, DeviceRepository(storage).devices.value)
    }

    @Test fun anEditorChangingOnlyTheLookKeepsAnotherEditorsRename() {
        val repository = DeviceRepository(MemoryStorage())
        assertTrue(repository.saveDevice(phone))
        val baseline = repository.devices.value.single()
        assertTrue(repository.editDevice(baseline, baseline.copy(name = "Office phone")))
        assertTrue(repository.editDevice(baseline, baseline.copy(avatarColor = 6, avatarIcon = DeviceIcon.TV)))
        val current = repository.devices.value.single()
        assertEquals("Office phone", current.name)
        assertEquals(6, current.avatarColor)
        assertEquals(DeviceIcon.TV, current.avatarIcon)
        // And back to automatic, which is a change of its own.
        assertTrue(repository.editDevice(current, current.copy(avatarColor = null, avatarIcon = null)))
        assertNull(repository.devices.value.single().avatarColor)
        assertNull(repository.devices.value.single().avatarIcon)
    }

    @Test fun onlyThePalettesColoursCanBeChosen() {
        val repository = DeviceRepository(MemoryStorage())
        for (color in listOf(-1, 8, Int.MAX_VALUE)) {
            assertFalse("$color", repository.saveDevice(phone.copy(avatarColor = color)))
        }
        assertTrue(repository.devices.value.isEmpty())
        for (color in SettingsValidation.AVATAR_COLORS) assertTrue(repository.saveDevice(phone.copy(avatarColor = color)))
        assertEquals(AvatarPalette.SIZE, SettingsValidation.AVATAR_COLORS.count())
    }

    @Test fun anUnreadableLookFallsBackToAutomaticAndKeepsTheDeviceAndItsSource() {
        val raw = """[{"id":"a","name":"Phone","host":"10.0.0.2","avatarColor":12,"avatarIcon":"HOLOGRAM"},""" +
            """{"id":"b","name":"Tablet","host":"10.0.0.3","avatarColor":"blue","avatarIcon":7},""" +
            """{"id":"c","name":"TV","host":"10.0.0.4","avatarColor":2,"avatarIcon":"TV"}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repository = DeviceRepository(storage)
        val devices = repository.devices.value
        assertEquals(listOf("a", "b", "c"), devices.map { it.id })
        assertNull(devices[0].avatarColor); assertNull(devices[0].avatarIcon)
        assertNull(devices[1].avatarColor); assertNull(devices[1].avatarIcon)
        assertEquals(2, devices[2].avatarColor); assertEquals(DeviceIcon.TV, devices[2].avatarIcon)
        assertTrue(repository.errors.value.isNotEmpty())
        // Reported and left as it was, for a newer version to read.
        assertEquals(raw, storage.values["devices"])
    }

    @Test fun aRemovedDeviceComesBackWhereItWasWithEverything() {
        val repository = DeviceRepository(MemoryStorage())
        val middle = SavedDevice(
            id = "b", name = "Tablet", host = "10.0.0.3", port = 41234, isFavorite = true,
            portraitCalibration = TouchCalibration(3, -4), landscapeCalibration = TouchCalibration(-5, 6),
            mirroringSettings = DeviceMirroringSettings.from(MirrorSettings(maxSize = 1024)),
            avatarColor = 4, avatarIcon = DeviceIcon.TABLET,
        )
        val all = listOf(phone, middle, SavedDevice(id = "c", name = "TV", host = "10.0.0.4"))
        all.forEach { assertTrue(repository.saveDevice(it)) }
        val removed = repository.devices.value[1]
        assertEquals(middle, removed)

        assertTrue(repository.deleteDevice("b"))
        assertTrue(repository.restoreDevice(removed, 1))
        assertEquals(all, repository.devices.value)
        // Twice is once: no duplicate.
        assertTrue(repository.restoreDevice(removed, 1))
        assertEquals(all, repository.devices.value)
        // A position the list no longer has puts it at the end.
        assertTrue(repository.deleteDevice("b"))
        assertTrue(repository.deleteDevice("c"))
        assertTrue(repository.restoreDevice(removed, 7))
        assertEquals(listOf(phone, middle), repository.devices.value)
        // And it is checked like any other device.
        assertFalse(repository.restoreDevice(removed.copy(id = "d", avatarColor = 99), 0))
        assertEquals(listOf(phone, middle), repository.devices.value)
    }

    @Test fun backupsCarryTheLookFromVersion12() {
        val styled = phone.copy(avatarColor = 7, avatarIcon = DeviceIcon.WATCH)
        val snapshot = ConfigurationSnapshot(listOf(styled, phone.copy(id = "z")), MirrorSettings())
        val bytes = ProfileBackupCodec.encode(snapshot)
        assertEquals(snapshot, ProfileBackupCodec.decode(bytes))
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        // Written in the current format, which has carried the look since version 12.
        assertEquals(JsonPrimitive(ProfileBackupCodec.VERSION), root["version"])
        assertTrue(ProfileBackupCodec.VERSION >= 12)
        val first = (root["devices"] as JsonArray).first().jsonObject
        assertEquals(JsonPrimitive(7), first["avatarColor"])
        assertEquals(JsonPrimitive("WATCH"), first["avatarIcon"])
    }

    @Test fun olderBackupsImportWithAutomaticLooksAndMayNotCarryOne() {
        val plain = """{"id":"a","name":"Phone","host":"10.0.0.2"}"""
        for (version in 1..11) {
            val decoded = ProfileBackupCodec.decode(backup("[$plain]", version))
            assertNull(decoded.devices.single().avatarColor)
            assertNull(decoded.devices.single().avatarIcon)
            for (field in listOf(""""avatarColor":2""", """"avatarIcon":"TV"""")) {
                val styled = plain.replace("}", ",$field}")
                val error = assertThrows(BackupFormatException::class.java) {
                    ProfileBackupCodec.decode(backup("[$styled]", version))
                }
                assertEquals(BackupFormatReason.INVALID_DATA, error.reason)
            }
        }
    }

    @Test fun aBackupLookMustBeAPaletteColourAndAKnownIcon() {
        for (field in listOf(""""avatarColor":8""", """"avatarColor":-1""", """"avatarColor":"2"""",
            """"avatarColor":2.5""", """"avatarIcon":"HOLOGRAM"""", """"avatarIcon":3""")) {
            val error = assertThrows(field, BackupFormatException::class.java) {
                ProfileBackupCodec.decode(backup("""[{"id":"a","name":"Phone","host":"h",$field}]""", 12))
            }
            assertEquals(field, BackupFormatReason.INVALID_DATA, error.reason)
        }
        // Null is automatic, as an export writes it.
        val decoded = ProfileBackupCodec.decode(
            backup("""[{"id":"a","name":"Phone","host":"h","avatarColor":null,"avatarIcon":null}]""", 12))
        assertNull(decoded.devices.single().avatarColor)
    }

    @Test fun aChosenColourIsShownAndChangesNoOtherDevicesColour() {
        val devices = (1..6).map { SavedDevice(id = "device-$it", name = "Device $it", host = "10.0.0.$it") }
        val automatic = avatarPaletteIndices(devices.map { it.id })
        assertEquals(automatic, deviceAvatarColors(devices))
        // Choosing one device's colour changes that device and no other, even to a colour another
        // device already shows.
        val taken = automatic.getValue("device-2")
        val chosen = devices.map { if (it.id == "device-5") it.copy(avatarColor = taken) else it }
        val colours = deviceAvatarColors(chosen)
        assertEquals(taken, colours.getValue("device-5"))
        for (device in devices.filter { it.id != "device-5" }) {
            assertEquals(device.id, automatic.getValue(device.id), colours.getValue(device.id))
        }
    }

    private fun backup(devices: String, version: Int): ByteArray =
        """{"format":"scrcpy-remote-settings","version":$version,"devices":$devices,"settings":{}}""".toByteArray()
}
