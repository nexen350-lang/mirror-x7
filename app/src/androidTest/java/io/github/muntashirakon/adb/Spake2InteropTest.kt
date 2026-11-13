package io.github.muntashirakon.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * The app's own SPAKE2 against the native library it replaced, which ports the BoringSSL code adbd
 * runs. The native library is a test-only dependency: it is not part of the app.
 */
@RunWith(AndroidJUnit4::class)
class Spake2InteropTest {
    private val random = SecureRandom()

    @Test fun ourClientAndTheNativeServerAgreeOnTheKey() {
        repeat(RUNS) {
            val password = pairingPassword()
            val ours = Spake2(Spake2.Role.ALICE, CLIENT, SERVER)
            val native = Spake2Context(Spake2Role.Bob, SERVER, CLIENT)
            val ourMessage = ours.generateMessage(password)
            val nativeMessage = native.generateMessage(password)
            val ourKey = accepted(ours.processMessage(nativeMessage))
            assertEquals(Spake2.KEY_SIZE, ourKey.size)
            assertArrayEquals(ourKey, native.processMessage(ourMessage))
        }
    }

    @Test fun theNativeClientAndOurServerAgreeOnTheKey() {
        repeat(RUNS) {
            val password = pairingPassword()
            val native = Spake2Context(Spake2Role.Alice, CLIENT, SERVER)
            val ours = Spake2(Spake2.Role.BOB, SERVER, CLIENT)
            val nativeMessage = native.generateMessage(password)
            val ourMessage = ours.generateMessage(password)
            assertArrayEquals(native.processMessage(ourMessage), accepted(ours.processMessage(nativeMessage)))
        }
    }

    @Test fun aWrongPairingCodeNeverAgreesAcrossImplementations() {
        repeat(RUNS) {
            val password = pairingPassword()
            val wrong = password.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            val ours = Spake2(Spake2.Role.ALICE, CLIENT, SERVER)
            val native = Spake2Context(Spake2Role.Bob, SERVER, CLIENT)
            val ourMessage = ours.generateMessage(password)
            val nativeMessage = native.generateMessage(wrong)
            val ourKey = accepted(ours.processMessage(nativeMessage))
            assertFalse(ourKey.contentEquals(native.processMessage(ourMessage)))
        }
    }

    /** As adbd builds it: the six-digit code, then 64 bytes exported from the TLS session. */
    private fun pairingPassword(): ByteArray =
        ByteArray(6) { ('0' + random.nextInt(10)).code.toByte() } + ByteArray(64).also(random::nextBytes)

    private fun accepted(key: ByteArray?): ByteArray {
        assertNotNull("The message was refused", key)
        return key!!
    }

    private companion object {
        const val RUNS = 25
        val CLIENT = "adb pair client\u0000".toByteArray()
        val SERVER = "adb pair server\u0000".toByteArray()
    }
}
