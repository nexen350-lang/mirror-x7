package com.scrcpy.remote.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tells an app-published launcher shortcut from an intent any other app could send to the
 * exported [MainActivity]. Published shortcuts carry a per-install random nonce that other apps
 * cannot read; only an intent with the right action and nonce connects without confirmation.
 */
internal object ShortcutTrust {
    private const val PREFS = "shortcut_trust"
    private const val KEY = "nonce"

    fun isTrusted(action: String?, presented: String?, expected: String): Boolean =
        action == Intent.ACTION_VIEW && presented != null && expected.isNotEmpty() &&
            MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    fun newNonce(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    @Volatile private var cached: String? = null
    @Volatile private var persisted = false

    @Synchronized
    fun nonce(context: Context): String =
        nonce(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Stable for the process even when persisting fails; persistence is retried on later calls. */
    @Synchronized
    internal fun nonce(prefs: SharedPreferences, random: SecureRandom = SecureRandom()): String {
        val value = cached ?: prefs.getString(KEY, null)?.takeIf { it.length == 32 }?.also { persisted = true }
            ?: newNonce(random)
        cached = value
        if (!persisted) persisted = prefs.edit().putString(KEY, value).commit()
        return value
    }

    internal fun resetForTest() { cached = null; persisted = false }
}
