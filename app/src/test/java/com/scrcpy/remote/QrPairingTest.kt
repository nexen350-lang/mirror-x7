package com.scrcpy.remote

import com.scrcpy.remote.adb.AdbServiceBrowser
import com.scrcpy.remote.adb.DiscoveredAdbService
import com.scrcpy.remote.adb.QrMatchResult
import com.scrcpy.remote.adb.QrPairingCredentials
import com.scrcpy.remote.adb.QrPairingDiscovery
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class QrPairingTest {

    @Test fun generatesRandomStandardAdbQrPayloadAndRedactsToString() {
        val first = QrPairingCredentials.generate()
        val second = QrPairingCredentials.generate()

        assertEquals("WIFI:T:ADB;S:${first.serviceName};P:${first.password};;", first.payload)
        assertTrue(first.serviceName.startsWith("ADB_WIFI_"))
        assertTrue(first.password.length >= 12)
        assertNotEquals(first.serviceName, second.serviceName)
        assertNotEquals(first.password, second.password)
        assertFalse(first.toString().contains(first.password))
        assertFalse(first.toString().contains(first.payload))
    }

    @Test fun matchesOnlyExactServiceNameWithValidNumericHost() = runBlocking {
        val creds = QrPairingCredentials.generate()
        val closed = AtomicBoolean(false)
        val discovery = QrPairingDiscovery(
            AdbServiceBrowser { listener ->
                listener.onResolved("other-name", DiscoveredAdbService(listOf("192.168.1.10"), 37000, "ADB_WIFI_OTHER"))
                listener.onResolved("dns-only", DiscoveredAdbService(listOf("phone.local"), 37001, creds.serviceName))
                listener.onResolved("bad-port", DiscoveredAdbService(listOf("192.168.1.20"), 0, creds.serviceName))
                listener.onResolved("match", DiscoveredAdbService(listOf("192.168.1.20"), 37999, creds.serviceName))
                Closeable { closed.set(true) }
            },
            timeoutMillis = 500,
        )

        val result = discovery.awaitMatchingService(creds.serviceName)
        assertEquals(QrMatchResult.Found("192.168.1.20", 37999), result)
        assertTrue(closed.get())
    }

    @Test fun returnsTimedOutWhenNoMatchingServiceAppears() = runBlocking {
        val closed = AtomicBoolean(false)
        val discovery = QrPairingDiscovery(
            AdbServiceBrowser { Closeable { closed.set(true) } },
            timeoutMillis = 50,
        )

        assertEquals(QrMatchResult.TimedOut, discovery.awaitMatchingService("ADB_WIFI_TEST123"))
        assertTrue(closed.get())
    }

    @Test fun cancellingQrDiscoveryClosesBrowser() = runBlocking {
        val closed = AtomicBoolean(false)
        val discovery = QrPairingDiscovery(
            AdbServiceBrowser { Closeable { closed.set(true) } },
            timeoutMillis = 2_000,
        )

        val job = launch { discovery.awaitMatchingService("ADB_WIFI_TEST123") }
        delay(30)
        job.cancelAndJoin()
        assertTrue(closed.get())
    }
}
