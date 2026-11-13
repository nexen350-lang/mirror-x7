package com.scrcpy.remote.adb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.muntashirakon.adb.AdbConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pairs with the device's own adbd through Wireless debugging, then connects over TLS, as a user
 * does with a pairing code. Opt-in: the device must show **Pair device with pairing code**, and the
 * test is given that code, its port and the Wireless debugging port.
 */
@RunWith(AndroidJUnit4::class)
class WirelessDebuggingLiveTest {
    @Test fun pairingWithTheDevicesCodeThenConnectingRunsACommandOverTls() {
        val arguments = InstrumentationRegistry.getArguments()
        val pairPort = arguments.getString("qaPairPort")?.toIntOrNull()
        val code = arguments.getString("qaPairCode")
        val tlsPort = arguments.getString("qaTlsPort")?.toIntOrNull()
        assumeTrue("Opt in with qaPairPort, qaPairCode and qaTlsPort while the device shows its pairing code",
            pairPort != null && code != null && tlsPort != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val pairing = AdbDeviceConnection.create(context)
        try {
            assertTrue("The device accepted the pairing code", pairing.pair(HOST, requireNotNull(pairPort), requireNotNull(code)))
        } finally {
            pairing.disconnect()
        }

        val connection = AdbDeviceConnection.create(context)
        try {
            assertTrue("Connected over Wireless debugging", connection.connect(HOST, requireNotNull(tlsPort)))
            val adb = requireNotNull(connection.adbConnection)
            val tls = AdbConnection::class.java.getDeclaredField("mIsTls").apply { isAccessible = true }
            assertTrue("The connection was upgraded to TLS", tls.getBoolean(adb))
            assertEquals("paired over tls", connection.runShellCommand("echo paired over tls").trim())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
