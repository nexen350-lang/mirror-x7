package com.scrcpy.remote.adb

import android.content.Context
import com.scrcpy.remote.data.SettingsValidation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException

sealed interface ConnectEndpointResult {
    data class Found(val host: String, val port: Int, val name: String) : ConnectEndpointResult
    data object NotFound : ConnectEndpointResult
    data object Unavailable : ConnectEndpointResult
}

/**
 * Looks up `_adb-tls-connect._tcp` for a specific already-paired numeric host.
 *
 * Fail-closed: only accepts advertisements whose resolved numeric address matches [pairedHost]
 * exactly via [EndpointHost.normalize]. Never trusts a service name as a host address and never
 * performs DNS or reverse-DNS lookups.
 */
class ConnectServiceDiscovery(
    private val browser: AdbServiceBrowser,
    private val deadlineMillis: Long = DEFAULT_DEADLINE_MS,
) {
    init {
        require(deadlineMillis in 1..15_000)
    }

    suspend fun findEndpoint(
        pairedHost: String,
        fallbackName: String? = null,
    ): ConnectEndpointResult {
        currentCoroutineContext().ensureActive()
        val trimmedHost = pairedHost.trim()
        if (!EndpointHost.isNumeric(trimmedHost)) return ConnectEndpointResult.NotFound
        val targetIdentity = EndpointHost.normalize(trimmedHost) ?: return ConnectEndpointResult.NotFound
        if (SettingsValidation.endpointError(trimmedHost, 5555) != null) return ConnectEndpointResult.NotFound

        val lock = Any()
        var active = true
        val outcome = CompletableDeferred<ConnectEndpointResult>()

        val listener = object : AdbServiceBrowser.Listener {
            override fun onFound(id: String) = Unit

            override fun onResolved(id: String, service: DiscoveredAdbService) {
                synchronized(lock) {
                    if (!active || outcome.isCompleted) return
                    if (service.port !in 1..65535) return
                    val matchesHost = service.hosts.any { candidate ->
                        val clean = candidate.trim()
                        EndpointHost.isNumeric(clean) && EndpointHost.normalize(clean) == targetIdentity
                    }
                    if (!matchesHost) return
                    val preferredFallback = fallbackName?.trim().orEmpty()
                    val name = PairingServiceDiscovery.safeServiceDisplayName(
                        service.serviceName,
                        PairingServiceDiscovery.safeServiceDisplayName(preferredFallback, trimmedHost),
                    )
                    outcome.complete(ConnectEndpointResult.Found(trimmedHost, service.port, name))
                }
            }

            override fun onLost(id: String) = Unit

            override fun onResolutionFailed(id: String) = Unit

            override fun onFailure(errorCode: Int?) {
                synchronized(lock) {
                    if (active && !outcome.isCompleted) {
                        outcome.complete(ConnectEndpointResult.Unavailable)
                    }
                }
            }
        }

        var registration: Closeable? = null
        try {
            registration = browser.start(listener)
            val result = withTimeoutOrNull(deadlineMillis) { outcome.await() }
            currentCoroutineContext().ensureActive()
            return result ?: ConnectEndpointResult.NotFound
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return ConnectEndpointResult.Unavailable
        } catch (_: SecurityException) {
            return ConnectEndpointResult.Unavailable
        } catch (_: IllegalStateException) {
            return ConnectEndpointResult.Unavailable
        } catch (_: IllegalArgumentException) {
            return ConnectEndpointResult.Unavailable
        } finally {
            synchronized(lock) { active = false }
            registration?.close()
        }
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 4_000L
    }
}

internal object NsdConnectServiceDiscovery {
    fun createBrowser(context: Context): AdbServiceBrowser =
        NsdAdbServiceBrowser(
            context = context,
            serviceType = NsdServiceTypes.CONNECT_SERVICE_TYPE,
            multicastLockTag = "scrcpy-adb-connect-discovery",
        )
}
