package com.scrcpy.remote.adb

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.session.ConnectionRecoveryPolicy
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Wireless debugging runs ADB over TLS, which libadb layers onto the TCP socket with the bundled
 * Conscrypt provider. A connection lost after that handshake is network loss; it must not be
 * classified as the device rejecting this app's key, which would stop automatic recovery and
 * tell the user to pair again.
 */
@RunWith(AndroidJUnit4::class)
class TlsTransportFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aConnectionResetAfterTheTlsHandshakeIsTransportLossNotAuthentication() {
        val identity = AdbIdentity.get(context)
        // The same provider and protocol libadb's SslUtils selects when Conscrypt is bundled.
        val provider = Class.forName("org.conscrypt.OpenSSLProvider")
            .getDeclaredConstructor().newInstance() as Provider
        val tls = SSLContext.getInstance("TLSv1.3", provider).apply {
            init(arrayOf(keyManager(identity.newPrivateKey(), identity.certificate as X509Certificate)),
                arrayOf(trustAll()), SecureRandom())
        }
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val reset = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable>()
        val server = Thread {
            try {
                val plain = listener.accept()
                val secure = tls.socketFactory.createSocket(plain, "127.0.0.1", listener.localPort, false)
                    as SSLSocket
                secure.useClientMode = false
                secure.startHandshake()
                // Application data has flowed, so the session is fully established.
                check(secure.inputStream.read() == 1)
                plain.setSoLinger(true, 0)
                plain.close()
                reset.countDown()
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }.apply { isDaemon = true; start() }
        val client = tls.socketFactory.createSocket(
            Socket(InetAddress.getByName("127.0.0.1"), listener.localPort),
            "127.0.0.1", listener.localPort, true,
        ) as SSLSocket
        try {
            client.startHandshake()
            client.outputStream.apply { write(1); flush() }
            assertTrue("The fixture must reset the established connection", reset.await(5, TimeUnit.SECONDS))
            assertNull(serverFailure.get())

            var writeFailure: IOException? = null
            for (attempt in 0 until 64) {
                try {
                    client.outputStream.write(ByteArray(16 * 1024))
                    client.outputStream.flush()
                } catch (error: IOException) {
                    writeFailure = error
                    break
                }
                SystemClock.sleep(10)
            }
            assertNotNull("Writing to a reset TLS connection must fail", writeFailure)
            val written = writeFailure!!
            Log.i(TAG, "TLS write after reset: ${describe(written)}")
            assertEquals(describe(written), FailureKind.TRANSIENT_TRANSPORT,
                ConnectionRecoveryPolicy.classify(written))

            // libadb's reader keeps its own failure and reports it as the cause of a later
            // "Connection failed", so the read side must classify the same way.
            val readFailure = try {
                client.inputStream.read()
                null
            } catch (error: IOException) {
                error
            }
            if (readFailure != null) {
                Log.i(TAG, "TLS read after reset: ${describe(readFailure)}")
                assertEquals(describe(readFailure), FailureKind.TRANSIENT_TRANSPORT,
                    ConnectionRecoveryPolicy.classify(IOException("Connection failed", readFailure)))
            }
        } finally {
            client.close()
            listener.close()
            server.join(2_000)
            assertFalse(server.isAlive)
        }
    }

    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.take(8).joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }

    private fun keyManager(key: PrivateKey, certificate: X509Certificate) = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
            ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
    }

    private fun trustAll() = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "key"
        const val TAG = "TlsTransportFailure"
    }
}
