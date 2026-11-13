package com.scrcpy.remote.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Persistent ADB identity (RSA key pair + self-signed certificate).
 *
 * The same identity is reused for every connection so the target device only has to authorise
 * this app once ("Always allow from this computer"), and so a wireless-debugging pairing stays
 * valid across app restarts.
 */
class AdbIdentity private constructor(
    private val privateKeyBytes: ByteArray,
    val certificate: Certificate,
) {

    /**
     * Returns a fresh [PrivateKey] instance on every call.
     *
     * `AbsAdbConnectionManager.close()` calls `destroy()` on the key it was handed, so handing
     * out a shared instance would invalidate the identity for the rest of the process lifetime.
     */
    fun newPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))

    companion object {
        private const val KEY_FILE = "adbkey"
        private const val CERT_FILE = "adbcert"
        private const val SUBJECT = "CN=Scrcpy Remote, OU=Scrcpy Remote, O=Scrcpy Remote"
        private const val VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: AdbIdentity? = null

        fun get(context: Context): AdbIdentity {
            return instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): AdbIdentity =
            loadFrom(File(context.filesDir, "adb"))

        /** Split from [load] so the stored material can be exercised without an app context. */
        @androidx.annotation.VisibleForTesting
        internal fun loadFrom(directory: File): AdbIdentity {
            val dir = directory.apply { mkdirs() }
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)

            if (keyFile.exists() && certFile.exists()) {
                try {
                    val encoded = keyFile.readBytes()
                    // Validate the stored material before trusting it.
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(encoded))
                    val certificate = certFile.inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it)
                    }
                    // Each half parsing is not enough: they are two files, replaced by two
                    // renames, so a failure between them leaves a new key beside an old
                    // certificate. Both would still parse, and the mismatched pair would be
                    // offered to every device as proof of an identity it cannot verify — which
                    // looks exactly like the app having never been authorised at all.
                    check(belongTogether(privateKey, certificate)) {
                        "Stored ADB key and certificate are not a pair"
                    }
                    return AdbIdentity(encoded, certificate)
                } catch (e: Throwable) {
                    // Only material that is genuinely unusable is thrown away. Catching everything
                    // here and deleting meant one unlucky read — a full disk, a moment without
                    // memory — destroyed the identity every target device has already authorised,
                    // so every one of them asked to allow debugging again and every wireless
                    // pairing had to be redone, with nothing on screen to explain why.
                    if (!isUnusableMaterial(e)) {
                        throw IOException(
                            "Could not read the stored ADB identity. It has been left alone; " +
                                "try connecting again.",
                            e,
                        )
                    }
                    keyFile.delete()
                    certFile.delete()
                }
            }

            val keyPair = generateKeyPair()
            val certificate = generateCertificate(keyPair)
            // Both written aside and moved into place only once both are complete: a key and a
            // certificate that do not belong together authorise nothing, and half a pair written
            // over a good one would be exactly that.
            writeAtomically(keyFile, keyPair.private.encoded, certFile, certificate.encoded)
            return AdbIdentity(keyPair.private.encoded, certificate)
        }

        /** Whether [certificate] certifies [privateKey], rather than merely parsing beside it. */
        private fun belongTogether(privateKey: PrivateKey, certificate: Certificate): Boolean {
            val private = privateKey as? RSAPrivateKey ?: return false
            val public = certificate.publicKey as? RSAPublicKey ?: return false
            // The modulus is shared by both halves of an RSA pair and by nothing else, so it
            // identifies the pair without needing a signature check.
            return private.modulus == public.modulus
        }

        /** Whether [error] means the stored identity itself is bad, rather than unreadable today. */
        private fun isUnusableMaterial(error: Throwable): Boolean = when (error) {
            is InvalidKeySpecException, is CertificateException, is NoSuchAlgorithmException -> true
            // A key that does not match its certificate is unusable in exactly the way a corrupt
            // one is: nothing can be done with it but replace it.
            is IllegalStateException -> true
            // An empty or truncated file reads without error and fails to parse, which the cases
            // above already cover; a failure to read at all is a different matter.
            is IOException, is OutOfMemoryError -> false
            else -> error.cause?.let { isUnusableMaterial(it) } ?: true
        }

        private fun writeAtomically(
            keyFile: File,
            keyBytes: ByteArray,
            certFile: File,
            certBytes: ByteArray,
        ) {
            val keyTemp = File(keyFile.parentFile, "$KEY_FILE.tmp")
            val certTemp = File(certFile.parentFile, "$CERT_FILE.tmp")
            // Kept so a failure partway can put back what was there. Two files cannot be replaced
            // in one step, so the next best thing is to leave the old pair intact when the second
            // replacement does not happen.
            val keyBackup = keyFile.takeIf { it.exists() }?.readBytes()
            try {
                keyTemp.writeBytes(keyBytes)
                certTemp.writeBytes(certBytes)
                if (!keyTemp.renameTo(keyFile)) {
                    throw IOException("Could not store the ADB identity")
                }
                if (!certTemp.renameTo(certFile)) {
                    // The key has already been replaced; putting the old one back is what stops
                    // the next load finding a new key beside an old certificate.
                    if (keyBackup != null) runCatching { keyFile.writeBytes(keyBackup) }
                    else runCatching { keyFile.delete() }
                    throw IOException("Could not store the ADB identity")
                }
            } finally {
                keyTemp.delete()
                certTemp.delete()
            }
        }

        private fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            return generator.generateKeyPair()
        }

        private fun generateCertificate(keyPair: KeyPair): Certificate {
            val now = System.currentTimeMillis()
            val subject = X500Name(SUBJECT)
            val holder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - VALIDITY_MILLIS),
                Date(now + VALIDITY_MILLIS),
                subject,
                keyPair.public,
            ).build(JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private))

            return JcaX509CertificateConverter().getCertificate(holder)
        }
    }
}
