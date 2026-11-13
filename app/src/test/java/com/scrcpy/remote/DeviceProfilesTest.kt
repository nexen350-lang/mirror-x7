package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.calibrationFor
import org.junit.Assert.*
import org.junit.Test

class DeviceProfilesTest {
    private class MemoryStorage(initial: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = initial.toMutableMap()
        var fail = false
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            if (fail) return false
            this.values.putAll(values)
            return true
        }
    }
    private val first = SavedDevice("one", "Phone", "192.168.1.2")
    private val second = SavedDevice("two", "Tablet", "192.168.1.3")
    private val defaults = MirrorSettings(touchOffsetXDp = -9, touchOffsetYDp = -32)

    @Test fun oldSavedDevicesKeepGlobalCalibrationInBothOrientations() {
        val decoded = SettingsCodec.decodeDevices(
            """[{"id":"one","name":"Phone","host":"192.168.1.2","port":5555}]""")
        assertTrue(decoded.issues.isEmpty())
        val device = decoded.value.single()
        assertFalse(device.isFavorite)
        assertNull(device.portraitCalibration)
        assertNull(device.landscapeCalibration)
        assertEquals(TouchCalibration(-9, -32), device.calibrationFor(false, defaults))
        assertEquals(TouchCalibration(-9, -32), device.calibrationFor(true, defaults))
    }

    @Test fun profilesAreIndependentAcrossDevicesAndOrientationsAndSurviveRestart() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        repository.saveSettings(defaults)
        repository.saveDevice(first)
        repository.saveDevice(second)
        assertTrue(repository.setCalibration(first.id, false, TouchCalibration(5, -40)))
        assertTrue(repository.setCalibration(first.id, true, TouchCalibration(-11, 7)))
        assertTrue(repository.setFavorite(first.id, true))
        val restarted = DeviceRepository(storage)
        val saved = restarted.getDevices().first()
        assertEquals(TouchCalibration(5, -40), saved.calibrationFor(false, defaults))
        assertEquals(TouchCalibration(-11, 7), saved.calibrationFor(true, defaults))
        assertEquals(TouchCalibration(-9, -32), restarted.getDevices()[1].calibrationFor(false, defaults))
        assertEquals(defaults, restarted.getSettings())
        assertTrue(saved.isFavorite)
    }

    @Test fun changingAddressFromStaleEditorDoesNotClobberLiveCalibrationOrFavorite() {
        val repository = DeviceRepository(MemoryStorage())
        repository.saveDevice(first)
        val draft = repository.getDevices().single()
        repository.setCalibration(first.id, false, TouchCalibration(7, -55))
        repository.setFavorite(first.id, true)
        assertTrue(repository.saveDevice(draft.copy(name = "Renamed", port = 37123)))
        val saved = repository.getDevices().single()
        assertEquals("Renamed", saved.name)
        assertEquals(37123, saved.port)
        assertEquals(TouchCalibration(7, -55), saved.portraitCalibration)
        assertTrue(saved.isFavorite)
    }

    @Test fun resetToDefaultsRemovesOnlyTheChosenOrientation() {
        val repository = DeviceRepository(MemoryStorage())
        repository.saveDevice(first.copy(
            portraitCalibration = TouchCalibration(8, 9),
            landscapeCalibration = TouchCalibration(10, 11)))
        assertTrue(repository.setCalibration(first.id, false, null))
        val saved = repository.getDevices().single()
        assertNull(saved.portraitCalibration)
        assertEquals(TouchCalibration(10, 11), saved.landscapeCalibration)
        assertEquals(TouchCalibration(-9, -32), saved.calibrationFor(false, defaults))
        assertTrue(repository.setCalibration(first.id, false, TouchCalibration()))
        assertEquals(TouchCalibration(), repository.getDevices().single().calibrationFor(false, defaults))
    }

    @Test fun invalidProfileIsReportedWithoutLosingDeviceOrOtherOrientation() {
        val raw = """[{"id":"one","name":"Phone","host":"192.168.1.2","isFavorite":true,
            "portraitCalibration":{"xDp":0,"yDp":-999},
            "landscapeCalibration":{"xDp":1,"yDp":2}}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repository = DeviceRepository(storage)
        val device = repository.getDevices().single()
        assertNull(device.portraitCalibration)
        assertEquals(TouchCalibration(1, 2), device.landscapeCalibration)
        assertTrue(device.isFavorite)
        assertEquals(raw, storage.values["devices"])
        assertTrue(repository.errors.value.isNotEmpty())
        assertTrue(repository.setCalibration(first.id, false, TouchCalibration(1, -10)))
        assertTrue(storage.values.values.contains(raw))
    }

    @Test fun writeFailureDoesNotPretendCalibrationOrFavoriteWasSaved() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        repository.saveDevice(first)
        storage.fail = true
        assertFalse(repository.setCalibration(first.id, false, TouchCalibration(3, 4)))
        assertFalse(repository.setFavorite(first.id, true))
        assertEquals(first, repository.getDevices().single())
        assertTrue(repository.errors.value.isNotEmpty())
    }

    @Test fun cannotResurrectDeletedDeviceByUpdatingItsProfile() {
        val repository = DeviceRepository(MemoryStorage())
        repository.saveDevice(first)
        repository.deleteDevice(first.id)
        assertFalse(repository.setCalibration(first.id, false, TouchCalibration(3, 4)))
        assertFalse(repository.setFavorite(first.id, true))
        assertTrue(repository.getDevices().isEmpty())
    }

    @Test fun outOfRangeCalibrationCannotBePersisted() {
        val repository = DeviceRepository(MemoryStorage())
        repository.saveDevice(first)
        assertFalse(repository.setCalibration(first.id, true, TouchCalibration(Int.MAX_VALUE, 0)))
        assertNull(repository.getDevices().single().landscapeCalibration)
    }

    @Test fun malformedFavoriteDoesNotDiscardUsefulEndpoint() {
        val decoded = SettingsCodec.decodeDevices(
            """[{"id":"one","name":"Phone","host":"192.168.1.2","isFavorite":"not a boolean"}]""")
        assertEquals("one", decoded.value.single().id)
        assertFalse(decoded.value.single().isFavorite)
        assertEquals("[0].isFavorite", decoded.issues.single().field)
    }
}
