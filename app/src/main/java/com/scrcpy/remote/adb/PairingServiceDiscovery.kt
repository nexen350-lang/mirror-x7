package com.scrcpy.remote.adb

import android.content.Context
import com.scrcpy.remote.data.DeviceNameRules
import com.scrcpy.remote.data.SettingsValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/** A pairing service discovered over mDNS with a verified numeric address. */
data class DiscoveredPairingDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
) {
    val address: String get() = "$host:$port"
}

sealed interface PairingDiscoveryState {
    data object Idle : PairingDiscoveryState
    data class Searching(val devices: List<DiscoveredPairingDevice>) : PairingDiscoveryState
    data class Empty(val failed: Boolean = false) : PairingDiscoveryState
}

/**
 * Discovers `_adb-tls-pairing._tcp` advertisements via [AdbServiceBrowser].
 *
 * Fail-closed: only resolved services with a valid numeric IPv4/IPv6 address and valid port
 * are surfaced. Service names are never treated as host addresses and no DNS or reverse-DNS
 * lookup is performed.
 */
class PairingServiceDiscovery(
    private val browser: AdbServiceBrowser,
    private val initialSearchMillis: Long = DEFAULT_INITIAL_SEARCH_MS,
) {
    init {
        require(initialSearchMillis in 1..60_000)
    }

    fun start(scope: CoroutineScope, onState: (PairingDiscoveryState) -> Unit): Closeable {
        val lock = Any()
        var active = true
        var initialWindowElapsed = false
        val trackedIds = mutableSetOf<String>()
        val devices = linkedMapOf<String, DiscoveredPairingDevice>()
        var registration: Closeable? = null
        var timerJob: Job? = null

        fun sortedDevices(): List<DiscoveredPairingDevice> =
            devices.values.sortedWith(
                compareBy<DiscoveredPairingDevice>(
                    { it.name.lowercase(Locale.ROOT) },
                    { it.host },
                    { it.port },
                ),
            )

        fun currentState(): PairingDiscoveryState = when {
            devices.isNotEmpty() -> PairingDiscoveryState.Searching(sortedDevices())
            initialWindowElapsed -> PairingDiscoveryState.Empty(failed = false)
            else -> PairingDiscoveryState.Searching(emptyList())
        }

        onState(PairingDiscoveryState.Searching(emptyList()))

        val timer = scope.launch {
            delay(initialSearchMillis)
            synchronized(lock) {
                if (!active) return@launch
                initialWindowElapsed = true
                if (devices.isEmpty()) {
                    onState(PairingDiscoveryState.Empty(failed = false))
                }
            }
        }
        synchronized(lock) {
            timerJob = timer
        }

        val listener = object : AdbServiceBrowser.Listener {
            override fun onFound(id: String) = synchronized(lock) {
                if (!active) return@synchronized
                if (id !in trackedIds && trackedIds.size < ConnectionPortDiscovery.MAX_SERVICES) {
                    trackedIds += id
                }
            }

            override fun onResolved(id: String, service: DiscoveredAdbService) = synchronized(lock) {
                if (!active) return@synchronized
                if (id !in trackedIds && trackedIds.size >= ConnectionPortDiscovery.MAX_SERVICES) {
                    return@synchronized
                }
                val host = selectNumericHost(service.hosts, service.port) ?: return@synchronized
                trackedIds += id
                val displayName = safeServiceDisplayName(service.serviceName, host)
                devices[id] = DiscoveredPairingDevice(id, displayName, host, service.port)
                onState(PairingDiscoveryState.Searching(sortedDevices()))
            }

            override fun onLost(id: String) = synchronized(lock) {
                if (!active) return@synchronized
                trackedIds.remove(id)
                if (devices.remove(id) != null) {
                    onState(currentState())
                }
            }

            override fun onResolutionFailed(id: String) = Unit

            override fun onFailure(errorCode: Int?) {
                val toClose: Closeable?
                val timerToCancel: Job?
                synchronized(lock) {
                    if (!active) return
                    active = false
                    toClose = registration
                    registration = null
                    timerToCancel = timerJob
                    timerJob = null
                    if (devices.isEmpty()) {
                        onState(PairingDiscoveryState.Empty(failed = true))
                    }
                }
                timerToCancel?.cancel()
                toClose?.close()
            }
        }

        val started = try {
            browser.start(listener)
        } catch (_: IOException) {
            failStart(lock, { active = false; val j = timerJob; timerJob = null; j }, onState)
            return Closeable {}
        } catch (_: SecurityException) {
            failStart(lock, { active = false; val j = timerJob; timerJob = null; j }, onState)
            return Closeable {}
        } catch (_: IllegalStateException) {
            failStart(lock, { active = false; val j = timerJob; timerJob = null; j }, onState)
            return Closeable {}
        } catch (_: IllegalArgumentException) {
            failStart(lock, { active = false; val j = timerJob; timerJob = null; j }, onState)
            return Closeable {}
        }

        var closeImmediately = false
        synchronized(lock) {
            if (active) {
                registration = started
            } else {
                closeImmediately = true
            }
        }
        if (closeImmediately) {
            started.close()
        }

        return Closeable {
            val toClose: Closeable?
            val timerToCancel: Job?
            synchronized(lock) {
                if (!active) return@Closeable
                active = false
                toClose = registration
                registration = null
                timerToCancel = timerJob
                timerJob = null
            }
            timerToCancel?.cancel()
            toClose?.close()
        }
    }

    private inline fun failStart(
        lock: Any,
        cancelAndDeactivate: () -> Job?,
        onState: (PairingDiscoveryState) -> Unit,
    ) {
        val timerToCancel = synchronized(lock) {
            val job = cancelAndDeactivate()
            onState(PairingDiscoveryState.Empty(failed = true))
            job
        }
        timerToCancel?.cancel()
    }

    internal companion object {
        const val DEFAULT_INITIAL_SEARCH_MS = 6_000L

        internal fun selectNumericHost(hosts: List<String>, port: Int): String? {
            if (port !in 1..65535) return null
            var ipv6Fallback: String? = null
            for (raw in hosts) {
                val candidate = raw.trim()
                val normalized = EndpointHost.normalize(candidate) ?: continue
                val isIp4 = normalized.startsWith("ip4:")
                val isIp6 = normalized.startsWith("ip6:")
                if (!isIp4 && !isIp6) continue
                if (SettingsValidation.endpointError(candidate, port) != null) continue
                if (isIp4) return candidate
                if (ipv6Fallback == null) ipv6Fallback = candidate
            }
            return ipv6Fallback
        }

        internal fun safeServiceDisplayName(serviceName: String, verifiedHost: String): String {
            val trimmed = serviceName.trim()
            if (trimmed.isEmpty() || trimmed.length > 100 || DeviceNameRules.hasInvisibleOrSpoofing(trimmed)) {
                return verifiedHost
            }
            return trimmed
        }
    }
}

internal object NsdPairingServiceDiscovery {
    fun createBrowser(context: Context): AdbServiceBrowser =
        NsdAdbServiceBrowser(
            context = context,
            serviceType = NsdServiceTypes.PAIRING_SERVICE_TYPE,
            multicastLockTag = "scrcpy-adb-pairing-discovery",
        )
}
