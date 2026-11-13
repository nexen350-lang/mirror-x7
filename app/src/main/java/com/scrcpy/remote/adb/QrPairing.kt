package com.scrcpy.remote.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CancellationException

/**
 * Ephemeral credentials for one ADB QR pairing attempt (`WIFI:T:ADB;S:<name>;P:<password>;;`).
 *
 * Short-lived, generated per attempt, never persisted, and redacted in [toString].
 */
class QrPairingCredentials private constructor(
    val serviceName: String,
    val password: String,
    val payload: String,
) {
    override fun toString(): String = "QrPairingCredentials(serviceName=$serviceName, password=[redacted])"

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        private const val SERVICE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun generate(): QrPairingCredentials {
            val nameSuffix = randomChars(SERVICE_ALPHABET, 10)
            val serviceName = "ADB_WIFI_$nameSuffix"
            val password = randomChars(ALPHABET, 12)
            val payload = "WIFI:T:ADB;S:$serviceName;P:$password;;"
            return QrPairingCredentials(serviceName, password, payload)
        }

        private fun randomChars(alphabet: String, length: Int): String = buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.length)])
            }
        }
    }
}

sealed interface QrMatchResult {
    data class Found(val host: String, val port: Int) : QrMatchResult
    data object TimedOut : QrMatchResult
    data object Unavailable : QrMatchResult
}

/**
 * Waits for an `_adb-tls-pairing._tcp` service whose instance name equals [expectedServiceName]
 * and supplies a verified numeric IPv4/IPv6 address and valid port.
 */
class QrPairingDiscovery(
    private val browser: AdbServiceBrowser,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) {
    init {
        require(timeoutMillis in 1..300_000)
    }

    suspend fun awaitMatchingService(
        expectedServiceName: String,
        onRemainingSeconds: (Int) -> Unit = {},
    ): QrMatchResult {
        currentCoroutineContext().ensureActive()
        if (expectedServiceName.isBlank()) return QrMatchResult.Unavailable

        val lock = Any()
        var active = true
        val outcome = CompletableDeferred<QrMatchResult>()

        val listener = object : AdbServiceBrowser.Listener {
            override fun onFound(id: String) = Unit

            override fun onResolved(id: String, service: DiscoveredAdbService) {
                synchronized(lock) {
                    if (!active || outcome.isCompleted) return
                    if (service.serviceName != expectedServiceName) return
                    val host = PairingServiceDiscovery.selectNumericHost(service.hosts, service.port) ?: return
                    outcome.complete(QrMatchResult.Found(host, service.port))
                }
            }

            override fun onLost(id: String) = Unit

            override fun onResolutionFailed(id: String) = Unit

            override fun onFailure(errorCode: Int?) {
                synchronized(lock) {
                    if (active && !outcome.isCompleted) {
                        outcome.complete(QrMatchResult.Unavailable)
                    }
                }
            }
        }

        var registration: Closeable? = null
        try {
            registration = browser.start(listener)
            val result = withTimeoutOrNull(timeoutMillis) {
                val ticker = launch {
                    var remaining = ((timeoutMillis + 999L) / 1000L).toInt()
                    while (remaining > 0) {
                        onRemainingSeconds(remaining)
                        delay(1_000L)
                        remaining--
                    }
                }
                try {
                    outcome.await()
                } finally {
                    ticker.cancel()
                }
            }
            currentCoroutineContext().ensureActive()
            return result ?: QrMatchResult.TimedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return QrMatchResult.Unavailable
        } catch (_: SecurityException) {
            return QrMatchResult.Unavailable
        } catch (_: IllegalStateException) {
            return QrMatchResult.Unavailable
        } catch (_: IllegalArgumentException) {
            return QrMatchResult.Unavailable
        } finally {
            synchronized(lock) { active = false }
            registration?.close()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
    }
}
