package com.scrcpy.remote.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * The stored ADB identity is what every target device has already authorised. Losing it means
 * re-approving every device and redoing every wireless pairing, and using a broken one means being
 * refused by all of them with nothing on screen to explain it — so what is accepted, what is
 * replaced, and what is left alone all matter more than for ordinary stored data.
 */
@RunWith(AndroidJUnit4::class)
class AdbIdentityTest {

    private fun scratch(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "identity-${System.nanoTime()}",
    )

    @Test fun anIdentityIsCreatedOnceAndReusedAfterwards() {
        val dir = scratch()
        try {
            val first = AdbIdentity.loadFrom(dir)
            val second = AdbIdentity.loadFrom(dir)
            assertArrayEquals(
                "A second load must not mint a new identity",
                first.certificate.encoded, second.certificate.encoded,
            )
            assertEquals(
                (first.newPrivateKey() as RSAPrivateKey).modulus,
                (second.newPrivateKey() as RSAPrivateKey).modulus,
            )
        } finally { dir.deleteRecursively() }
    }

    @Test fun aKeyThatDoesNotMatchItsCertificateIsReplacedRatherThanOffered() {
        // The two halves live in separate files replaced by separate renames, so a failure between
        // them leaves a new key beside an old certificate. Both still parse on their own, and the
        // mismatched pair would be presented to every device as proof of an identity it cannot
        // verify — indistinguishable, from the user's side, from never having been authorised.
        val dir = scratch()
        val other = scratch()
        try {
            AdbIdentity.loadFrom(dir)
            AdbIdentity.loadFrom(other)
            val strangerKey = File(other, "adbkey").readBytes()
            File(dir, "adbkey").writeBytes(strangerKey)

            val repaired = AdbIdentity.loadFrom(dir)
            val privateModulus = (repaired.newPrivateKey() as RSAPrivateKey).modulus
            val certModulus = (repaired.certificate.publicKey as RSAPublicKey).modulus
            assertEquals("The repaired identity must be a real pair", privateModulus, certModulus)
            assertNotEquals(
                "The mismatched key must not have been kept",
                privateModulus,
                java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(strangerKey))
                    .let { (it as RSAPrivateKey).modulus },
            )
        } finally { dir.deleteRecursively(); other.deleteRecursively() }
    }

    @Test fun corruptMaterialIsReplacedButAnUnreadableStoreIsLeftAlone() {
        val dir = scratch()
        try {
            AdbIdentity.loadFrom(dir)
            val goodCert = File(dir, "adbcert").readBytes()
            // Nonsense in the key file cannot be anything but corruption, so it is replaced.
            File(dir, "adbkey").writeBytes(ByteArray(64) { 0x7f })
            val replaced = AdbIdentity.loadFrom(dir)
            assertTrue("A corrupt identity must be regenerated",
                !replaced.certificate.encoded.contentEquals(goodCert))

            // A directory where the key file cannot be read at all is a different matter: the
            // material may be perfectly good, and destroying it would cost every authorisation.
            val unreadable = scratch()
            AdbIdentity.loadFrom(unreadable)
            val keptCert = File(unreadable, "adbcert").readBytes()
            val key = File(unreadable, "adbkey")
            val kept = key.readBytes()
            key.delete()
            key.mkdir() // A directory where a file is expected: readBytes fails with IOException.
            assertThrows(IOException::class.java) { AdbIdentity.loadFrom(unreadable) }
            key.delete()
            key.writeBytes(kept)
            assertArrayEquals(
                "The identity must survive a read that merely failed",
                keptCert, File(unreadable, "adbcert").readBytes(),
            )
            unreadable.deleteRecursively()
        } finally { dir.deleteRecursively() }
    }
}
