package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbServiceBrowser
import com.scrcpy.remote.adb.ConnectionPortDiscovery
import com.scrcpy.remote.adb.ConnectionPortResult
import com.scrcpy.remote.adb.ConnectionPortSelector
import com.scrcpy.remote.adb.DiscoveredAdbService
import com.scrcpy.remote.adb.EndpointHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionPortDiscoveryTest {
    private fun service(host: String, port: Int) = DiscoveredAdbService(listOf(host), port)

    @Test fun hostNormalizationIsExactAndDoesNotUseSubstringOrDnsMatching() {
        assertEquals(EndpointHost.normalize("PHONE.local."), EndpointHost.normalize("phone.local"))
        assertEquals(EndpointHost.normalize(" 192.168.1.2 "), EndpointHost.normalize("192.168.1.2"))
        assertNotEquals(EndpointHost.normalize("192.168.1.2"), EndpointHost.normalize("192.168.1.20"))
        assertNotEquals(EndpointHost.normalize("phone.local"), EndpointHost.normalize("other-phone.local"))
        assertNotEquals(EndpointHost.normalize("phone.local"), EndpointHost.normalize("phone.local.evil"))
        assertNotEquals(EndpointHost.normalize("phone.local"), EndpointHost.normalize("192.168.1.2"))
        for (invalid in listOf("", " ", "192.168.1.256", "192.168.001.2", "127.1",
            "[192.168.1.2]", "host:5555", "https://phone.local", "phone local", "a..local",
            "phone.local..", "[::1", "::1]", "::ffff:999.1.1.1", ":::1", "1::2::3")) {
            assertNull(invalid, EndpointHost.normalize(invalid))
        }
    }

    @Test fun ipv6BracketsCompressionAndScopeAreHandledWithoutChangingInterfaces() {
        assertEquals(EndpointHost.normalize("[2001:DB8::1]"),
            EndpointHost.normalize("2001:db8:0:0:0:0:0:1"))
        assertEquals(EndpointHost.normalize("[fe80::1%25wlan0]"),
            EndpointHost.normalize("fe80:0:0:0:0:0:0:1%wlan0"))
        assertEquals(EndpointHost.normalize("fe80::1%02"), EndpointHost.normalize("fe80::1%2"))
        assertNotEquals(EndpointHost.normalize("fe80::1%wlan0"), EndpointHost.normalize("fe80::1%wlan1"))
        assertNotEquals(EndpointHost.normalize("fe80::1"), EndpointHost.normalize("fe80::1%2"))
        assertEquals(EndpointHost.normalize("::ffff:192.168.1.2"), EndpointHost.normalize("192.168.1.2"))
        assertEquals(EndpointHost.normalize("::ffff:c0a8:102"), EndpointHost.normalize("192.168.1.2"))
    }

    @Test fun onlyAnExactlyMatchingHostCanSupplyANewPort() {
        assertEquals(ConnectionPortResult.Updated(40000), ConnectionPortSelector.select("192.168.1.2", 5555,
            listOf(service("192.168.1.20", 30000), service("192.168.1.2", 40000))))
        assertEquals(ConnectionPortResult.NotFound, ConnectionPortSelector.select("phone.local", 5555,
            listOf(service("192.168.1.2", 40000))))
        assertEquals(ConnectionPortResult.Updated(40000), ConnectionPortSelector.select("[2001:db8::1]", 5555,
            listOf(service("2001:db8:0:0:0:0:0:1", 40000))))
        assertEquals(ConnectionPortResult.NotFound, ConnectionPortSelector.select("192.168.1.2", 5555,
            listOf(service("192.168.1.2", -1), service("192.168.1.2", 65536))))
    }

    @Test fun ambiguityIncludesTheOldPortAndDuplicateAdvertisementsDoNotChooseArbitrarily() {
        assertEquals(ConnectionPortResult.Ambiguous(setOf(5555, 40000)),
            ConnectionPortSelector.select("phone.local", 5555,
                listOf(service("phone.local.", 5555), service("PHONE.local", 40000))))
        assertEquals(ConnectionPortResult.Unchanged(5555), ConnectionPortSelector.select("phone.local", 5555,
            listOf(service("phone.local", 5555), service("PHONE.local.", 5555))))
        assertEquals(ConnectionPortResult.InvalidEndpoint, ConnectionPortSelector.select("", 5555,
            listOf(service("192.168.1.2", 40000))))
        assertEquals(ConnectionPortResult.InvalidEndpoint, ConnectionPortSelector.select("phone.local", 0, emptyList()))
    }

    @Test fun optInAndAKnownEndpointAreRequiredBeforeBrowsing() = runBlocking {
        val discovery = ConnectionPortDiscovery(AdbServiceBrowser { error("Must not browse") }, 10)
        assertEquals(ConnectionPortResult.Disabled, discovery.resolveConnectionPort("192.168.1.2", 5555))
        assertEquals(ConnectionPortResult.InvalidEndpoint, discovery.resolveConnectionPort("", 5555, true))
        assertEquals(ConnectionPortResult.InvalidEndpoint, discovery.resolveConnectionPort("192.168.1.2", 0, true))
    }

    @Test fun deadlineClosesTheListenerAndDoesNotPickTheFirstMatch() = runBlocking {
        val closed = AtomicBoolean()
        val discovery = ConnectionPortDiscovery(AdbServiceBrowser { listener ->
            listener.onFound("first")
            listener.onResolved("first", service("192.168.1.2", 40000))
            listener.onFound("second")
            listener.onResolved("second", service("192.168.1.2", 40001))
            Closeable { closed.set(true) }
        }, 30)
        assertEquals(ConnectionPortResult.Ambiguous(setOf(40000, 40001)),
            discovery.resolveConnectionPort("192.168.1.2", 5555, true))
        assertTrue(closed.get())
    }

    @Test fun cancellationClosesTheListenerAndIgnoresLateCallbacks() = runBlocking {
        val started = CompletableDeferred<AdbServiceBrowser.Listener>()
        val closed = AtomicBoolean()
        val discovery = ConnectionPortDiscovery(AdbServiceBrowser { listener ->
            started.complete(listener)
            Closeable { closed.set(true) }
        })
        val job = launch { discovery.resolveConnectionPort("192.168.1.2", 5555, true) }
        val listener = started.await()
        job.cancelAndJoin()
        assertTrue(closed.get())
        listener.onResolved("late", service("192.168.1.2", 40000))
        listener.onFailure(1)
        assertTrue(job.isCancelled)
    }

    @Test fun unresolvedOrTooManyServicesNeverProduceASelection() = runBlocking {
        for (overflow in listOf(false, true)) {
            val closed = AtomicBoolean()
            val discovery = ConnectionPortDiscovery(AdbServiceBrowser { listener ->
                listener.onResolved("known", service("192.168.1.2", 40000))
                if (overflow) {
                    repeat(ConnectionPortDiscovery.MAX_SERVICES) { listener.onFound("extra$it") }
                } else {
                    listener.onFound("unresolved")
                    listener.onResolutionFailed("unresolved")
                }
                Closeable { closed.set(true) }
            }, 20)
            assertEquals(ConnectionPortResult.Incomplete, discovery.resolveConnectionPort("192.168.1.2", 5555, true))
            assertTrue(closed.get())
        }
    }

    @Test fun lostServicesAreRemovedAndPlatformFailureClosesTheListener() = runBlocking {
        val lost = ConnectionPortDiscovery(AdbServiceBrowser { listener ->
            listener.onResolved("keep", service("192.168.1.2", 40000))
            listener.onResolved("lost", service("192.168.1.2", 40001))
            listener.onLost("lost")
            Closeable {}
        }, 20)
        assertEquals(ConnectionPortResult.Updated(40000), lost.resolveConnectionPort("192.168.1.2", 5555, true))
        val closed = AtomicBoolean()
        val failed = ConnectionPortDiscovery(AdbServiceBrowser { listener ->
            listener.onFailure(3)
            Closeable { closed.set(true) }
        }, 20)
        assertEquals(ConnectionPortResult.Unavailable(3), failed.resolveConnectionPort("192.168.1.2", 5555, true))
        assertTrue(closed.get())
    }
}
