package com.scrcpy.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointAndShortcutSignatureTest {
    @Test
    fun underscoreAllowedInHostLabels() {
        assertNull(SettingsValidation.endpointError("Galaxy_S21.lan", 5555))
        assertNull(SettingsValidation.endpointError("_dmarc.example.com", 5555))
    }

    @Test
    fun otherHostRulesUnchanged() {
        for (host in listOf("-phone", "a b", "a..b", "a/path", "user@host", "a".repeat(64) + ".lan", "a.".repeat(127) + "a")) {
            assertNotNull(host, SettingsValidation.endpointError(host, 5555))
        }
        assertNull(SettingsValidation.endpointError("phone.local", 5555))
    }

    private fun dev(id: String, name: String, host: String = "h$id", fav: Boolean = false) =
        SavedDevice(id = id, name = name, host = host, port = 5555, isFavorite = fav)

    @Test
    fun signatureChangesWhenDeviceOutsideTopFourIsRenamed() {
        val before = (1..6).map { dev("$it", "n$it") }
        val after = before.map { if (it.id == "6") it.copy(name = "renamed") else it }
        assertEquals(4, DeviceShortcuts.select(before).size)
        assertNotEquals(DeviceShortcuts.signature(before), DeviceShortcuts.signature(after))
    }

    @Test
    fun signatureIgnoresUnrelatedFields() {
        val a = listOf(dev("1", "n"))
        assertEquals(DeviceShortcuts.signature(a), DeviceShortcuts.signature(a.map { it.copy(avatarColor = 3) }))
    }

    @Test
    fun signatureChangesWhenFavouriteReordersTop() {
        val a = (1..6).map { dev("$it", "n$it") }
        val b = a.map { if (it.id == "6") it.copy(isFavorite = true) else it }
        assertNotEquals(DeviceShortcuts.signature(a), DeviceShortcuts.signature(b))
    }

    @Test
    fun selectHonoursSmallerLimit() {
        assertEquals(1, DeviceShortcuts.select((1..6).map { dev("$it", "n") }, 1).size)
    }
}
