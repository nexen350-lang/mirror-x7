package com.scrcpy.remote.ui

import android.content.Intent
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ShortcutTrustTest {
    private val nonce = ShortcutTrust.newNonce()

    @Test fun publishedShortcutIsTrusted() {
        assertTrue(ShortcutTrust.isTrusted(Intent.ACTION_VIEW, nonce, nonce))
    }

    @Test fun missingWrongOrEmptyNonceIsNotTrusted() {
        assertFalse(ShortcutTrust.isTrusted(Intent.ACTION_VIEW, null, nonce))
        assertFalse(ShortcutTrust.isTrusted(Intent.ACTION_VIEW, ShortcutTrust.newNonce(), nonce))
        assertFalse(ShortcutTrust.isTrusted(Intent.ACTION_VIEW, "", ""))
    }

    @Test fun wrongActionIsNotTrusted() {
        assertFalse(ShortcutTrust.isTrusted(null, nonce, nonce))
        assertFalse(ShortcutTrust.isTrusted(Intent.ACTION_SEND, nonce, nonce))
    }

    @Test fun noncesAreRandomHex() {
        assertEquals(32, nonce.length)
        assertTrue(nonce.all { it in "0123456789abcdef" })
        assertNotEquals(nonce, ShortcutTrust.newNonce())
    }

    private class Fake(var failCommits: Int) {
        val map = HashMap<String, String>()
        val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending = args[0] as String to args[1] as String; proxy }
                "commit" -> if (failCommits > 0) { failCommits--; false } else { map += pending!!; true }
                else -> proxy
            }
        } as SharedPreferences.Editor
        var pending: Pair<String, String>? = null
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> map[args[0] as String]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    @Test fun nonceStaysStableWhenFirstPersistFailsAndIsPersistedLater() {
        ShortcutTrust.resetForTest()
        val fake = Fake(failCommits = 1)
        val first = ShortcutTrust.nonce(fake.prefs, SecureRandom())
        assertTrue(fake.map.isEmpty())
        assertEquals(first, ShortcutTrust.nonce(fake.prefs))
        assertEquals(first, fake.map["nonce"])
        ShortcutTrust.resetForTest()
    }
}
