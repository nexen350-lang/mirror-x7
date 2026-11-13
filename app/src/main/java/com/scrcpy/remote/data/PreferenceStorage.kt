package com.scrcpy.remote.data

import android.content.SharedPreferences
import java.io.IOException

/** Writes must be atomic: recovery copies and their replacement are committed together. */
interface PreferenceStorage {
    @Throws(IOException::class)
    fun read(): Map<String, *>

    @Throws(IOException::class)
    fun write(values: Map<String, Any?>): Boolean
}

internal class SharedPreferencesStorage(private val preferences: SharedPreferences) : PreferenceStorage {
    private var unconfirmed = emptyMap<String, Any?>()

    @Synchronized
    override fun read(): Map<String, *> = preferences.all.toMutableMap().apply {
        unconfirmed.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }

    @Synchronized
    override fun write(values: Map<String, Any?>): Boolean {
        val before = read()
        val changes = unconfirmed + values
        // Not the KTX edit {} helper: it returns Unit, and this class reports storage failures
        // from the commit() result below. Losing it would silently turn failed saves into
        // successful ones.
        @Suppress("UseKtx")
        val editor = preferences.edit()
        changes.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    require(value.all { it is String }) { "Unsupported preference set for $key" }
                    editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                else -> throw IllegalArgumentException("Unsupported preference type for $key")
            }
        }
        // Android publishes Editor changes in memory even when commit() returns false. Mask those
        // unconfirmed values and repair them in the next checked commit, including removed keys.
        unconfirmed = changes.keys.associateWith { before[it] }
        return editor.commit().also { if (it) unconfirmed = emptyMap() }
    }
}
