package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbServiceBrowser
import com.scrcpy.remote.adb.DiscoveredAdbService
import com.scrcpy.remote.adb.NsdServiceTypes
import com.scrcpy.remote.adb.PairingDiscoveryState
import com.scrcpy.remote.adb.PairingServiceDiscovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PairingServiceDiscoveryTest {

    @Test fun discoversNumericAddressesOnlyAndNeverTrustsServiceNameOrDns() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        val closed = AtomicBoolean(false)
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { listener ->
                listener.onFound("dns-only")
                listener.onResolved(
                    "dns-only",
                    DiscoveredAdbService(listOf("phone.local"), 37000, serviceName = "192.168.1.99"),
                )
                listener.onFound("bad-port")
                listener.onResolved(
                    "bad-port",
                    DiscoveredAdbService(listOf("192.168.1.30"), 0, serviceName = "BadPort"),
                )
                listener.onFound("dual-stack")
                listener.onResolved(
                    "dual-stack",
                    DiscoveredAdbService(listOf("2001:db8::2", "192.168.1.20"), 37123, serviceName = "Pixel 8"),
                )
                listener.onFound("v6-only")
                listener.onResolved(
                    "v6-only",
                    DiscoveredAdbService(listOf("2001:db8::5"), 41234, serviceName = "Alpha"),
                )
                Closeable { closed.set(true) }
            },
            initialSearchMillis = 200,
        )

        val session = discovery.start(this) { states += it }
        try {
            val latest = states.last() as PairingDiscoveryState.Searching
            assertEquals(2, latest.devices.size)
            assertEquals("Alpha", latest.devices[0].name)
            assertEquals("2001:db8::5", latest.devices[0].host)
            assertEquals(41234, latest.devices[0].port)
            assertEquals("Pixel 8", latest.devices[1].name)
            assertEquals("192.168.1.20", latest.devices[1].host)
            assertEquals(37123, latest.devices[1].port)
        } finally {
            session.close()
        }
        assertTrue(closed.get())
    }

    @Test fun spoofingServiceNameFallsBackToVerifiedNumericHost() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { listener ->
                listener.onFound("spoof")
                listener.onResolved(
                    "spoof",
                    DiscoveredAdbService(listOf("192.168.1.44"), 38000, serviceName = "\u202Eevil-phone"),
                )
                Closeable {}
            },
            initialSearchMillis = 200,
        )

        val session = discovery.start(this) { states += it }
        try {
            val latest = states.last() as PairingDiscoveryState.Searching
            assertEquals(1, latest.devices.size)
            assertEquals("192.168.1.44", latest.devices[0].name)
            assertEquals("192.168.1.44:38000", latest.devices[0].address)
        } finally {
            session.close()
        }
    }

    @Test fun lostServiceAndTimeoutShowEmptyStateUntilDeviceAppears() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        lateinit var captured: AdbServiceBrowser.Listener
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { listener ->
                captured = listener
                Closeable {}
            },
            initialSearchMillis = 30,
        )

        val session = discovery.start(this) { states += it }
        try {
            assertEquals(PairingDiscoveryState.Searching(emptyList()), states.first())
            delay(80)
            assertEquals(PairingDiscoveryState.Empty(failed = false), states.last())

            captured.onFound("late-phone")
            captured.onResolved(
                "late-phone",
                DiscoveredAdbService(listOf("192.168.1.55"), 39000, serviceName = "Late Phone"),
            )
            val found = states.last() as PairingDiscoveryState.Searching
            assertEquals(1, found.devices.size)
            assertEquals("192.168.1.55", found.devices[0].host)

            captured.onLost("late-phone")
            assertEquals(PairingDiscoveryState.Empty(failed = false), states.last())
        } finally {
            session.close()
        }
    }

    @Test fun closingSessionStopsBrowserAndIgnoresLateCallbacks() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        val closed = AtomicBoolean(false)
        lateinit var captured: AdbServiceBrowser.Listener
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { listener ->
                captured = listener
                Closeable { closed.set(true) }
            },
            initialSearchMillis = 50,
        )

        val session = discovery.start(this) { states += it }
        val countBeforeClose = states.size
        session.close()
        assertTrue(closed.get())

        captured.onFound("stale")
        captured.onResolved("stale", DiscoveredAdbService(listOf("192.168.1.77"), 40001, "Stale"))
        captured.onFailure(1)
        delay(80)

        assertEquals(countBeforeClose, states.size)
    }

    @Test fun browserStartExceptionEmitsFailedEmptyState() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { throw IOException("NSD unavailable") },
            initialSearchMillis = 50,
        )

        val session = discovery.start(this) { states += it }
        session.close()
        assertEquals(PairingDiscoveryState.Empty(failed = true), states.last())
    }

    @Test fun platformFailureClosesRegistrationAndEmitsFailedEmptyState() = runBlocking {
        val states = CopyOnWriteArrayList<PairingDiscoveryState>()
        val closed = AtomicBoolean(false)
        lateinit var captured: AdbServiceBrowser.Listener
        val discovery = PairingServiceDiscovery(
            AdbServiceBrowser { listener ->
                captured = listener
                Closeable { closed.set(true) }
            },
            initialSearchMillis = 200,
        )

        val session = discovery.start(this) { states += it }
        captured.onFailure(2)
        assertTrue(closed.get())
        assertEquals(PairingDiscoveryState.Empty(failed = true), states.last())
        session.close()
    }

    @Test fun nsdServiceTypeMatchesPairingAdvertisements() {
        assertTrue(NsdServiceTypes.matches("_adb-tls-pairing._tcp", NsdServiceTypes.PAIRING_SERVICE_TYPE))
        assertTrue(NsdServiceTypes.matches("._adb-tls-pairing._tcp.", NsdServiceTypes.PAIRING_SERVICE_TYPE))
        assertFalse(NsdServiceTypes.matches("_adb-tls-connect._tcp", NsdServiceTypes.PAIRING_SERVICE_TYPE))
    }
}
