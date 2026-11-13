package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbServiceBrowser
import com.scrcpy.remote.adb.ConnectEndpointResult
import com.scrcpy.remote.adb.ConnectServiceDiscovery
import com.scrcpy.remote.adb.DiscoveredAdbService
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class ConnectServiceDiscoveryTest {

    @Test fun findsConnectPortOnlyOnMatchingNumericPairedHost() = runBlocking {
        val closed = AtomicBoolean(false)
        val discovery = ConnectServiceDiscovery(
            AdbServiceBrowser { listener ->
                listener.onResolved("other", DiscoveredAdbService(listOf("192.168.1.99"), 40000, "Other"))
                listener.onResolved("dns", DiscoveredAdbService(listOf("phone.local"), 40001, "192.168.1.20"))
                listener.onResolved("bad-port", DiscoveredAdbService(listOf("192.168.1.20"), 0, "Bad"))
                listener.onResolved("match", DiscoveredAdbService(listOf("192.168.1.20"), 41234, "Pixel 8"))
                Closeable { closed.set(true) }
            },
            deadlineMillis = 200,
        )

        val result = discovery.findEndpoint("192.168.1.20")
        assertEquals(ConnectEndpointResult.Found("192.168.1.20", 41234, "Pixel 8"), result)
        assertTrue(closed.get())
    }

    @Test fun ipv6NormalizationMatchesEquivalentHost() = runBlocking {
        val discovery = ConnectServiceDiscovery(
            AdbServiceBrowser { listener ->
                listener.onResolved("v6", DiscoveredAdbService(listOf("2001:db8:0:0:0:0:0:1"), 42222, ""))
                Closeable {}
            },
            deadlineMillis = 200,
        )

        val result = discovery.findEndpoint("2001:DB8::1", fallbackName = "Work Phone")
        assertEquals(ConnectEndpointResult.Found("2001:DB8::1", 42222, "Work Phone"), result)
    }

    @Test fun returnsNotFoundWhenDeadlineExpiresWithoutMatch() = runBlocking {
        val closed = AtomicBoolean(false)
        val discovery = ConnectServiceDiscovery(
            AdbServiceBrowser { Closeable { closed.set(true) } },
            deadlineMillis = 30,
        )

        val result = discovery.findEndpoint("192.168.1.20")
        assertEquals(ConnectEndpointResult.NotFound, result)
        assertTrue(closed.get())
    }

    @Test fun returnsUnavailableOnBrowserFailureAndClosesOnCancel() = runBlocking {
        val failed = ConnectServiceDiscovery(
            AdbServiceBrowser { listener ->
                listener.onFailure(1)
                Closeable {}
            },
            deadlineMillis = 200,
        )
        assertEquals(ConnectEndpointResult.Unavailable, failed.findEndpoint("192.168.1.20"))

        val closed = AtomicBoolean(false)
        val slow = ConnectServiceDiscovery(
            AdbServiceBrowser { Closeable { closed.set(true) } },
            deadlineMillis = 2_000,
        )
        val job = launch { slow.findEndpoint("192.168.1.20") }
        delay(30)
        job.cancelAndJoin()
        assertTrue(closed.get())
    }
}
