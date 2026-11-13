package com.scrcpy.remote.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque
import java.util.Locale

/**
 * Shared NSD service browser with MulticastLock handling for Android 13 and below.
 *
 * NSD supplies numeric addresses; callers must never trust service names as hosts or perform
 * reverse DNS lookups. Requires INTERNET, plus CHANGE_WIFI_MULTICAST_STATE on Android 13 and below.
 */
internal class NsdAdbServiceBrowser(
    context: Context,
    private val serviceType: String,
    private val multicastLockTag: String,
) : AdbServiceBrowser {
    private val application = context.applicationContext

    override fun start(listener: AdbServiceBrowser.Listener): Closeable {
        val manager = checkNotNull(application.getSystemService(NsdManager::class.java))
        val multicast = if (Build.VERSION.SDK_INT < 34) {
            checkNotNull(application.getSystemService(WifiManager::class.java))
                .createMulticastLock(multicastLockTag).apply { setReferenceCounted(false) }
        } else null
        val registration = Registration(manager, serviceType, listener)
        try {
            multicast?.acquire()
            registration.start()
            return Closeable {
                try { registration.close() } finally { releaseMulticast(multicast) }
            }
        } catch (error: Throwable) {
            registration.close()
            releaseMulticast(multicast)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private class Registration(
        private val manager: NsdManager,
        private val serviceType: String,
        listener: AdbServiceBrowser.Listener,
    ) : Closeable {
        private class Service(val key: String, val info: NsdServiceInfo)
        private class Resolution(val service: Service, val listener: NsdManager.ResolveListener)

        private val lock = Any()
        private var closed = false
        private var discoveryStarted = false
        private var callback: AdbServiceBrowser.Listener? = listener
        private val services = mutableMapOf<String, Service>()
        private val pending = ArrayDeque<Service>()
        private var resolution: Resolution? = null
        private val canCancelResolution = Build.VERSION.SDK_INT >= 34 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7)

        private val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) { callback?.onFailure(errorCode) }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                synchronized(lock) {
                    if (!closed) callback?.onFailure(null)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(lock) { callback?.onFailure(errorCode) }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (closed || !isTargetService(serviceInfo)) return
                    val key = serviceKey(serviceInfo)
                    if (key in services) return
                    if (services.size == ConnectionPortDiscovery.MAX_SERVICES) {
                        callback?.onFailure(NsdManager.FAILURE_MAX_LIMIT)
                        return
                    }
                    val service = Service(key, serviceInfo)
                    services[key] = service
                    pending.addLast(service)
                    callback?.onFound(key)
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (closed || !isTargetService(serviceInfo)) return
                    val key = serviceKey(serviceInfo)
                    services.remove(key)
                    pending.removeAll { it.key == key }
                    callback?.onLost(key)
                    val active = resolution
                    if (active?.service?.key == key && canCancelResolution) {
                        resolution = null
                        cancelResolution(active)
                        resolveNext()
                    }
                }
            }
        }

        fun start() {
            synchronized(lock) {
                check(!closed)
                discoveryStarted = true
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }
        }

        /** Legacy NSD allows only one outstanding resolve, so resolve sequentially on every API. */
        private fun resolveNext() {
            if (closed || resolution != null) return
            while (pending.isNotEmpty()) {
                val service = pending.removeFirst()
                if (services[service.key] !== service) continue
                val resolver = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        finishResolution(this, null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        finishResolution(this, serviceInfo)
                    }
                }
                resolution = Resolution(service, resolver)
                try {
                    manager.resolveService(service.info, resolver)
                    return
                } catch (error: IllegalArgumentException) {
                    resolution = null
                    Log.w(TAG, "Invalid NSD resolution request", error)
                    callback?.onResolutionFailed(service.key)
                } catch (error: IllegalStateException) {
                    resolution = null
                    Log.w(TAG, "NSD resolution unavailable", error)
                    callback?.onResolutionFailed(service.key)
                } catch (error: SecurityException) {
                    resolution = null
                    Log.w(TAG, "NSD resolution denied", error)
                    callback?.onResolutionFailed(service.key)
                }
            }
        }

        private fun finishResolution(resolver: NsdManager.ResolveListener, resolved: NsdServiceInfo?) {
            synchronized(lock) {
                val active = resolution ?: return
                if (closed || active.listener !== resolver) return
                resolution = null
                val service = active.service
                if (services[service.key] === service) {
                    if (resolved != null && isTargetService(resolved) &&
                        resolved.serviceName.equals(service.info.serviceName, ignoreCase = true)) {
                        val hosts = if (Build.VERSION.SDK_INT >= 34) {
                            resolved.hostAddresses.mapNotNull { it.hostAddress }
                        } else listOfNotNull(resolved.host?.hostAddress)
                        callback?.onResolved(
                            service.key,
                            DiscoveredAdbService(hosts, resolved.port, resolved.serviceName.orEmpty()),
                        )
                    } else callback?.onResolutionFailed(service.key)
                }
                resolveNext()
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                // Without the newer resolve-cancellation API, clear captured state and ignore
                // that one outstanding callback; discovery itself is always unregistered.
                callback = null
                services.clear()
                pending.clear()
                val active = resolution
                resolution = null
                if (active != null) cancelResolution(active)
                if (discoveryStarted) {
                    try { manager.stopServiceDiscovery(discoveryListener) }
                    catch (error: IllegalArgumentException) { Log.d(TAG, "NSD discovery already unregistered", error) }
                    catch (error: IllegalStateException) { Log.w(TAG, "Could not stop NSD discovery", error) }
                }
            }
        }

        private fun serviceKey(info: NsdServiceInfo): String {
            val network = if (Build.VERSION.SDK_INT >= 33) info.network?.networkHandle else null
            return "${info.serviceName.lowercase(Locale.ROOT)}|${NsdServiceTypes.canonical(info.serviceType)}|$network"
        }

        private fun isTargetService(info: NsdServiceInfo): Boolean =
            !info.serviceName.isNullOrBlank() &&
                NsdServiceTypes.matches(info.serviceType, serviceType)

        @SuppressLint("NewApi") // Available on API 34, or Android 13 with T extension 7.
        private fun cancelResolution(active: Resolution) {
            if (canCancelResolution) {
                try { manager.stopServiceResolution(active.listener) }
                catch (error: IllegalArgumentException) { Log.d(TAG, "NSD resolution already finished", error) }
                catch (error: IllegalStateException) { Log.w(TAG, "Could not stop NSD resolution", error) }
            }
        }
    }

    private companion object {
        const val TAG = "AdbPortDiscovery"

        fun releaseMulticast(lock: WifiManager.MulticastLock?) {
            if (lock?.isHeld == true) lock.release()
        }
    }
}

/**
 * Optional ADB connection-port recovery, never pairing-port discovery or device selection.
 *
 * NSD supplies numeric addresses, so a DNS-only request fails closed rather than trusting a
 * service name or doing a reverse lookup. Keep the original host when applying an Updated port.
 * Requires INTERNET, plus CHANGE_WIFI_MULTICAST_STATE for discovery on Android 13 and below.
 */
class NsdConnectionPortDiscovery(context: Context, deadlineMillis: Long = 3_000) {
    private val discovery = ConnectionPortDiscovery(
        NsdAdbServiceBrowser(
            context = context,
            serviceType = NsdServiceTypes.CONNECT_SERVICE_TYPE,
            multicastLockTag = "scrcpy-adb-port-recovery",
        ),
        deadlineMillis,
    )

    suspend fun resolveConnectionPort(
        host: String,
        previousPort: Int,
        enabled: Boolean = false,
    ): ConnectionPortResult = discovery.resolveConnectionPort(host, previousPort, enabled)
}
