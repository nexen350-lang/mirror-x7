package com.scrcpy.remote.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.adb.ConnectionPortResult
import com.scrcpy.remote.adb.NsdConnectionPortDiscovery
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NsdDiscoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun discoveryIsOptInAndCanBeCancelledRepeatedly() = runBlocking {
        val discovery = NsdConnectionPortDiscovery(context, deadlineMillis = 300)
        assertEquals(ConnectionPortResult.Disabled, discovery.resolveConnectionPort("192.0.2.1", 5555))
        repeat(3) {
            val pending = launch { discovery.resolveConnectionPort("192.0.2.1", 5555, enabled = true) }
            delay(50)
            withTimeout(2_000) { pending.cancelAndJoin() }
        }
        val result = withTimeout(2_000) {
            discovery.resolveConnectionPort("192.0.2.1", 5555, enabled = true)
        }
        assertFalse("Never select an unrelated advertised endpoint", result is ConnectionPortResult.Updated)
    }
}
