package com.scrcpy.remote

import android.content.SharedPreferences
import com.scrcpy.remote.data.SharedPreferencesStorage
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class PreferenceStorageTest {
    private class Preferences(initial: Map<String, Any?>) {
        val memory = initial.toMutableMap()
        val disk = initial.toMutableMap()
        var fail = false
        var denied = false
        val value = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getAll" -> memory.toMap()
                "edit" -> editor()
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when {
                    method.name.startsWith("put") -> { changes[args!![0] as String] = args[1]; proxy }
                    method.name == "remove" -> { changes[args!![0] as String] = null; proxy }
                    method.name == "commit" -> {
                        changes.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value }
                        if (denied) throw SecurityException("denied")
                        if (!fail) { disk.clear(); disk.putAll(memory) }
                        !fail
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun failedCommitDoesNotBecomeTheNextReadOrLeaveUncommittedValuesInTheNextSave() {
        val original = mapOf("settings" to "original", "retained" to "keep")
        val preferences = Preferences(original)
        val storage = SharedPreferencesStorage(preferences.value)
        preferences.fail = true
        assertFalse(storage.write(mapOf("settings" to "uncommitted", "added" to "uncommitted", "retained" to null)))
        assertNotEquals(original, preferences.memory)
        assertEquals(original, storage.read())
        assertEquals(original, preferences.disk)
        preferences.fail = false
        assertTrue(storage.write(mapOf("devices" to "saved")))
        assertEquals(original + ("devices" to "saved"), preferences.disk)
        assertEquals(preferences.disk, storage.read())
        assertFalse(preferences.memory.containsKey("added"))
    }

    @Test fun thrownCommitFailureIsAlsoMaskedAndRepaired() {
        val preferences = Preferences(mapOf("settings" to "original"))
        val storage = SharedPreferencesStorage(preferences.value)
        preferences.denied = true
        assertThrows(SecurityException::class.java) { storage.write(mapOf("settings" to "denied")) }
        assertEquals("original", storage.read()["settings"])
        preferences.denied = false
        assertTrue(storage.write(mapOf("devices" to "saved")))
        assertEquals("original", preferences.disk["settings"])
        assertEquals("saved", preferences.disk["devices"])
    }
}
