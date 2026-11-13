package com.scrcpy.remote.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.IDN
import java.util.Locale
import java.util.concurrent.CancellationException

/** An identity key, not a replacement dial address. No DNS or reverse-DNS lookup is performed. */
object EndpointHost {
    fun isNumeric(host: String): Boolean {
        val normalized = normalize(host) ?: return false
        return normalized.startsWith("ip4:") || normalized.startsWith("ip6:")
    }

    fun normalize(host: String): String? {
        var value = host.trim()
        if (value.isEmpty() || value.length > 253) return null
        val bracketed = value.startsWith("[") && value.endsWith("]")
        if (bracketed) {
            value = value.substring(1, value.length - 1)
            if (value.contains("%25") && value.substringAfter("%25").isNotEmpty()) {
                value = value.replaceFirst("%25", "%")
            }
        }
        if (value.any { it.isWhitespace() || it in "[]/\\@?#" }) return null
        if (':' in value) return normalizeV6(value)
        if (bracketed || '%' in value) return null
        if (value.all { it in '0'..'9' || it == '.' }) {
            return ipv4(value)?.joinToString(".")?.let { "ip4:$it" }
        }
        val name = try {
            IDN.toASCII(value.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) { return null }
        if (name.length !in 1..253 || name.split('.').any {
                it.length !in 1..63 || it.first() == '-' || it.last() == '-' ||
                    it.any { char -> char !in 'a'..'z' && char !in '0'..'9' && char != '-' }
            }) return null
        return "dns:$name"
    }

    private fun ipv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        return parts.map {
            if (it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') ||
                it.any { char -> char !in '0'..'9' }) return null
            it.toInt().takeIf { octet -> octet in 0..255 } ?: return null
        }
    }

    private fun normalizeV6(value: String): String? {
        val pieces = value.split('%')
        if (pieces.size > 2) return null
        val address = pieces[0]
        var zone = pieces.getOrNull(1)
        if (zone != null) {
            if (zone.isEmpty() || zone.length > 64 || zone.any {
                    it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it !in "_.-"
                }) return null
            if (zone.all { it in '0'..'9' }) zone = zone.trimStart('0').ifEmpty { "0" }
            if (zone == "0") zone = null
        }
        val compression = address.indexOf("::")
        val words = if (compression >= 0) {
            if (compression != address.lastIndexOf("::")) return null
            val left = v6Words(address.substring(0, compression), allowV4 = false) ?: return null
            val right = v6Words(address.substring(compression + 2), allowV4 = true) ?: return null
            if (left.size + right.size >= 8) return null
            left + List(8 - left.size - right.size) { 0 } + right
        } else {
            (v6Words(address, allowV4 = true) ?: return null).takeIf { it.size == 8 } ?: return null
        }
        if (words.take(5).all { it == 0 } && words[5] == 0xffff) {
            if (zone != null) return null
            return "ip4:${words[6] shr 8}.${words[6] and 255}.${words[7] shr 8}.${words[7] and 255}"
        }
        val key = words.joinToString("") { it.toString(16).padStart(4, '0') }
        return "ip6:$key" + (zone?.let { "%$it" } ?: "")
    }

    private fun v6Words(value: String, allowV4: Boolean): List<Int>? {
        if (value.isEmpty()) return emptyList()
        val parts = value.split(':')
        val words = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            if ('.' in part) {
                if (!allowV4 || index != parts.lastIndex) return null
                val octets = ipv4(part) ?: return null
                words += (octets[0] shl 8) or octets[1]
                words += (octets[2] shl 8) or octets[3]
            } else {
                if (part.length !in 1..4 || part.any { it !in "0123456789abcdefABCDEF" }) return null
                words += part.toInt(16)
            }
        }
        return words
    }
}

data class DiscoveredAdbService(val hosts: List<String>, val port: Int, val serviceName: String = "")

sealed interface ConnectionPortResult {
    /** Keep the original requested host; only this port has been selected. */
    data class Updated(val port: Int) : ConnectionPortResult
    data class Unchanged(val port: Int) : ConnectionPortResult
    data class Ambiguous(val ports: Set<Int>) : ConnectionPortResult
    data class Unavailable(val errorCode: Int? = null) : ConnectionPortResult
    data object Disabled : ConnectionPortResult
    data object InvalidEndpoint : ConnectionPortResult
    data object NotFound : ConnectionPortResult
    data object Incomplete : ConnectionPortResult
}

object ConnectionPortSelector {
    fun select(
        host: String,
        previousPort: Int,
        services: Collection<DiscoveredAdbService>,
    ): ConnectionPortResult {
        val requested = EndpointHost.normalize(host) ?: return ConnectionPortResult.InvalidEndpoint
        if (previousPort !in 1..65535) return ConnectionPortResult.InvalidEndpoint
        val ports = services.asSequence()
            .filter { it.port in 1..65535 && it.hosts.any { candidate ->
                EndpointHost.normalize(candidate) == requested
            } }
            .map { it.port }.toSortedSet()
        return when (ports.size) {
            0 -> ConnectionPortResult.NotFound
            1 -> if (ports.first() == previousPort) ConnectionPortResult.Unchanged(previousPort)
                else ConnectionPortResult.Updated(ports.first())
            else -> ConnectionPortResult.Ambiguous(ports.toSet())
        }
    }
}

/** Implementations must clean up a partially registered listener if [start] throws. */
fun interface AdbServiceBrowser {
    fun start(listener: Listener): Closeable

    interface Listener {
        fun onFound(id: String)
        fun onResolved(id: String, service: DiscoveredAdbService)
        fun onLost(id: String)
        fun onResolutionFailed(id: String)
        fun onFailure(errorCode: Int?)
    }
}

/**
 * Waits for the whole discovery window instead of picking the first advertisement.
 * Unresolved advertisements prevent selection; a lost advertisement is removed.
 */
class ConnectionPortDiscovery(
    private val browser: AdbServiceBrowser,
    private val deadlineMillis: Long = 3_000,
) {
    init { require(deadlineMillis in 1..10_000) }

    suspend fun resolveConnectionPort(
        host: String,
        previousPort: Int,
        enabled: Boolean = false,
    ): ConnectionPortResult {
        currentCoroutineContext().ensureActive()
        if (!enabled) return ConnectionPortResult.Disabled
        if (EndpointHost.normalize(host) == null || previousPort !in 1..65535) {
            return ConnectionPortResult.InvalidEndpoint
        }
        val lock = Any()
        var active = true
        val services = linkedMapOf<String, MutableSet<DiscoveredAdbService>>()
        val pending = mutableSetOf<String>()
        val failure = CompletableDeferred<ConnectionPortResult>()

        fun track(id: String): Boolean {
            if (id !in services && services.size == MAX_SERVICES) {
                failure.complete(ConnectionPortResult.Incomplete)
                return false
            }
            services.getOrPut(id) { linkedSetOf() }
            return true
        }

        val listener = object : AdbServiceBrowser.Listener {
            override fun onFound(id: String) = synchronized(lock) {
                if (active && track(id)) pending.add(id)
                Unit
            }

            override fun onResolved(id: String, service: DiscoveredAdbService) = synchronized(lock) {
                if (!active || !track(id)) return@synchronized
                if (service.port !in 1..65535 || service.hosts.isEmpty() ||
                    service.hosts.size > MAX_HOSTS || service.hosts.any { EndpointHost.normalize(it) == null }) {
                    pending.add(id)
                    return@synchronized
                }
                val entries = checkNotNull(services[id])
                if (entries.size >= MAX_HOSTS) {
                    failure.complete(ConnectionPortResult.Incomplete)
                    return@synchronized
                }
                entries += service.copy(hosts = service.hosts.toList())
                pending.remove(id)
                Unit
            }

            override fun onLost(id: String) = synchronized(lock) {
                if (active) { services.remove(id); pending.remove(id) }
                Unit
            }

            override fun onResolutionFailed(id: String) = synchronized(lock) {
                if (active && track(id)) pending.add(id)
                Unit
            }

            override fun onFailure(errorCode: Int?) {
                synchronized(lock) {
                    if (active) failure.complete(ConnectionPortResult.Unavailable(errorCode))
                }
            }
        }
        var registration: Closeable? = null
        try {
            val earlyFailure = withTimeoutOrNull(deadlineMillis) {
                registration = browser.start(listener)
                failure.await()
            }
            currentCoroutineContext().ensureActive()
            if (earlyFailure != null) return earlyFailure
            return synchronized(lock) {
                val result = ConnectionPortSelector.select(host, previousPort, services.values.flatten())
                if (pending.isNotEmpty() && result !is ConnectionPortResult.Ambiguous) {
                    ConnectionPortResult.Incomplete
                } else result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return ConnectionPortResult.Unavailable()
        } catch (_: SecurityException) {
            return ConnectionPortResult.Unavailable()
        } catch (_: IllegalStateException) {
            return ConnectionPortResult.Unavailable()
        } catch (_: IllegalArgumentException) {
            return ConnectionPortResult.Unavailable()
        } finally {
            synchronized(lock) { active = false }
            registration?.close()
        }
    }

    companion object {
        const val MAX_SERVICES = 64
        private const val MAX_HOSTS = 16
    }
}
