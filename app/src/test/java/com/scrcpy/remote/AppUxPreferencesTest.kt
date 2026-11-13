package com.scrcpy.remote

import com.scrcpy.remote.data.AppUxPreferences
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.UxPreferenceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemoryUxStore : UxPreferenceStore {
    private val booleans = mutableMapOf<String, Boolean>()
    private val strings = mutableMapOf<String, String>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
}

class AppUxPreferencesTest {

    private val pixel = SavedDevice(id = "dev-1", name = "Pixel", host = "192.168.1.10", port = 5555)
    private val galaxy = SavedDevice(id = "dev-2", name = "Galaxy", host = "192.168.1.20", port = 5555)

    @Test
    fun reconnectOnLaunchIsOffByDefault() {
        val prefs = AppUxPreferences(InMemoryUxStore())
        assertFalse(prefs.reconnectLastDeviceOnLaunch)
        assertFalse(prefs.onboardingCompleted)
        prefs.lastConnectedDeviceId = pixel.id
        assertNull(prefs.resolveAutoReconnectTarget(listOf(pixel, galaxy)))
    }

    @Test
    fun resolveAutoReconnectTargetMatchesSavedDeviceWhenEnabled() {
        val prefs = AppUxPreferences(InMemoryUxStore())
        prefs.reconnectLastDeviceOnLaunch = true
        prefs.lastConnectedDeviceId = galaxy.id

        assertEquals(galaxy, prefs.resolveAutoReconnectTarget(listOf(pixel, galaxy)))
    }

    @Test
    fun resolveAutoReconnectTargetReturnsNullIfSavedDeviceWasDeleted() {
        val prefs = AppUxPreferences(InMemoryUxStore())
        prefs.reconnectLastDeviceOnLaunch = true
        prefs.lastConnectedDeviceId = "deleted-id"

        assertNull(prefs.resolveAutoReconnectTarget(listOf(pixel, galaxy)))
    }

    @Test
    fun onboardingCompletedPersistsAcrossReads() {
        val store = InMemoryUxStore()
        val first = AppUxPreferences(store)
        assertFalse(first.onboardingCompleted)
        first.onboardingCompleted = true

        val second = AppUxPreferences(store)
        assertTrue(second.onboardingCompleted)
    }
}
