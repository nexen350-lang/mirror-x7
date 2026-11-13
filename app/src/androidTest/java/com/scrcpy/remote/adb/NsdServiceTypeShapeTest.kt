package com.scrcpy.remote.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks how this Android build actually spells a resolved service type.
 *
 * Wireless debugging moves to a new port whenever it is switched off and on, so rediscovery is the
 * only way a saved device still connects afterwards. That path is otherwise tested entirely
 * through fakes that hand back ready-made results, which cannot show what the platform really
 * produces — and Android reports the type of a *resolved* service differently from the way it
 * reports the same type when discovering it.
 *
 * This registers a real service and resolves it through the real NsdManager, so the shape of that
 * string is observed rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class NsdServiceTypeShapeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun aResolvedServiceTypeIsAcceptedHoweverThePlatformSpellsIt() {
        val manager = context.getSystemService(NsdManager::class.java)
        assertNotNull("No NsdManager on this device", manager)
        val socket = ServerSocket(0)
        val name = "scrcpyprobe${System.nanoTime() % 100000}"
        val registered = CountDownLatch(1)
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = registered.countDown()
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = registered.countDown()
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = PROBE_TYPE
            port = socket.localPort
        }
        try {
            @Suppress("DEPRECATION")
            manager!!.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            // Some images have no working mDNS responder; there is then nothing to observe and
            // nothing to conclude, which is not the same as a pass.
            org.junit.Assume.assumeTrue(
                "No mDNS responder on this image, so nothing could be registered",
                registered.await(20, TimeUnit.SECONDS),
            )

            val discoveredType = AtomicReference<String?>(null)
            val resolvedType = AtomicReference<String?>(null)
            val done = CountDownLatch(1)
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, code: Int) { done.countDown() }
                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceName.orEmpty().equals(name, ignoreCase = true)) return
                    discoveredType.set(service.serviceType)
                    @Suppress("DEPRECATION")
                    manager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(failed: NsdServiceInfo, code: Int) { done.countDown() }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            resolvedType.set(resolved.serviceType)
                            done.countDown()
                        }
                    })
                }
            }
            @Suppress("DEPRECATION")
            manager.discoverServices(PROBE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            val finished = done.await(40, TimeUnit.SECONDS)
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            val resolved = resolvedType.get()
            android.util.Log.i(
                "NsdProbe",
                "asked='$PROBE_TYPE' found='${discoveredType.get()}' resolved='$resolved' " +
                    "finished=$finished matches=${NsdServiceTypes.matches(resolved, PROBE_TYPE)}",
            )
            // Reported as skipped, not passed. An image whose mDNS responder never answers has
            // told us nothing about how Android spells a resolved type, and a test that passes
            // on no observation is worse than no test: it looks like evidence.
            org.junit.Assume.assumeTrue(
                "No resolution observed on this image, so the service-type shape was not exercised",
                finished && resolved != null,
            )

            // Whatever shape it came back in, the matcher has to accept it: this is the exact
            // comparison rediscovery depends on, and getting it wrong means a device whose port
            // has moved can never be found again.
            assertTrue(
                "Discovered as '${discoveredType.get()}' but resolved as '$resolved', " +
                    "which the service matcher does not accept",
                NsdServiceTypes.matches(resolved, PROBE_TYPE),
            )
        } finally {
            runCatching { manager?.unregisterService(registrationListener) }
            runCatching { socket.close() }
        }
    }

    private companion object {
        /** The same shape as adb's own type, so the platform treats it identically. */
        const val PROBE_TYPE = "_scrcpyprobe._tcp"
    }
}
