package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.duplicateDraft
import org.junit.Assert.*
import org.junit.Test

class DeviceCopiesTest {
    @Test fun copiedConfigurationHasIndependentIdentityAndCanBeEditedIndependently() {
        val values = mutableMapOf<String, Any?>()
        val repo = DeviceRepository(object : PreferenceStorage {
            override fun read(): Map<String, *> = values.toMap()
            override fun write(changes: Map<String, Any?>): Boolean { values.putAll(changes); return true }
        })
        val original = SavedDevice("original", "Phone", "192.168.1.2", isFavorite = true,
            portraitCalibration = TouchCalibration(2, 3), landscapeCalibration = TouchCalibration(4, 5),
            mirroringSettings = DeviceMirroringSettings(maxSize = 800))
        repo.saveDevice(original)
        val copy = original.duplicateDraft("Copy of Phone")
        assertNotEquals(original.id, copy.id)
        assertEquals(listOf(original), repo.getDevices()) // A draft has no persistence side effect.
        assertEquals(original.copy(id = copy.id, name = copy.name), copy)
        repo.saveDevice(copy.copy(host = "192.168.1.3"))
        repo.setCalibration(copy.id, false, TouchCalibration(99, 0))
        assertEquals(original, repo.getDevices().first())
        assertEquals(TouchCalibration(99, 0), repo.getDevices().last().portraitCalibration)
    }

    @Test fun generatedNameFitsLimitWithoutCuttingUnicodePair() {
        val source = SavedDevice("one", "Phone", "127.0.0.1")
        val copy = source.duplicateDraft("x".repeat(99) + "\uD83D\uDE00")
        assertEquals(99, copy.name.length)
        assertFalse(copy.name.last().isHighSurrogate())
    }
}
