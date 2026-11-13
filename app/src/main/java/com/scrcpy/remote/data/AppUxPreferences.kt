package com.scrcpy.remote.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight store abstraction so startup UX flags can be unit-tested without Android framework
 * SharedPreferences mocks.
 */
interface UxPreferenceStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

internal class SharedPreferencesUxStore(
    private val preferences: SharedPreferences,
) : UxPreferenceStore {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String): String? =
        preferences.getString(key, null)

    override fun putString(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.apply()
    }
}

/**
 * Stores non-sensitive UX preferences inside the existing backup-excluded [PREFS_NAME] file:
 * - whether to reconnect to the last connected saved device on launch (default OFF)
 * - the ID of the last connected saved device
 * - whether the first-run setup guide has been completed
 *
 * Never stores pairing codes, passwords, or ADB keys.
 */
class AppUxPreferences(private val store: UxPreferenceStore) {

    var reconnectLastDeviceOnLaunch: Boolean
        get() = store.getBoolean(KEY_RECONNECT_LAST_DEVICE, false)
        set(value) = store.putBoolean(KEY_RECONNECT_LAST_DEVICE, value)

    var lastConnectedDeviceId: String?
        get() = store.getString(KEY_LAST_CONNECTED_DEVICE_ID)?.takeIf { it.isNotBlank() }
        set(value) = store.putString(KEY_LAST_CONNECTED_DEVICE_ID, value?.takeIf { it.isNotBlank() })

    var onboardingCompleted: Boolean
        get() = store.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = store.putBoolean(KEY_ONBOARDING_COMPLETED, value)

    fun resolveAutoReconnectTarget(devices: List<SavedDevice>): SavedDevice? {
        if (!reconnectLastDeviceOnLaunch) return null
        val targetId = lastConnectedDeviceId ?: return null
        return devices.firstOrNull { it.id == targetId }
    }

    companion object {
        const val PREFS_NAME = "scrcpy_remote"
        internal const val KEY_RECONNECT_LAST_DEVICE = "ux_reconnect_last_device"
        internal const val KEY_LAST_CONNECTED_DEVICE_ID = "ux_last_device_id"
        internal const val KEY_ONBOARDING_COMPLETED = "ux_onboarding_completed"

        fun create(context: Context): AppUxPreferences = AppUxPreferences(
            SharedPreferencesUxStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ),
        )
    }
}
