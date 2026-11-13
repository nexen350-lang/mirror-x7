package com.scrcpy.remote.session

import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each scrcpy server the app carries is the one the device runs when chosen, read on the device
 * itself, through the loopback adbd of a disposable emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 */
@RunWith(AndroidJUnit4::class)
class ServerVersionLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun onlyTheCurrentBundledServerRunsOnTheDevice() {
        val port = livePort()
        for (server in ScrcpyServerVersion.entries) {
            // Pushed afresh, so what runs is this build's jar rather than one an earlier session left.
            shell("rm -f ${ScrcpyServerVersion.entries.joinToString(" ") { it.remotePath }}")
            withModel { model ->
                main {
                    model.connect(HOST, port, MirrorSettings(maxSize = 640, audioEnabled = false,
                        autoReconnect = false, serverVersion = server))
                }
                await("connected with ${server.version}") { model.state.value as? MirrorUiState.Connected }
                assertEquals(server, model.activeSettings.value?.serverVersion)
                val running = await("only ${server.version} running") {
                    serverProcesses().takeIf { it.isNotEmpty() && it.all { line -> "scrcpy.Server ${server.version} " in line } }
                }
                Log.i(TAG, "${server.version}: $running")
                // Listing uses the same official current server as streaming.
                main { model.loadEncoders() }
                val encoders = await("encoders listed") { model.encoders.value.takeIf { it.loaded && !it.loading } }
                assertNull(encoders.error)
                assertTrue("The device lists encoders through ${server.version}", encoders.encoders.isNotEmpty())
                val jars = shell("ls /data/local/tmp/").lines().map { it.trim() }.filter { it.startsWith("scrcpy-server-") }
                assertEquals("Only ${server.version}'s jar was pushed: $jars", listOf("scrcpy-server-${server.version}.jar"), jars)
            }
            await("the ${server.version} session ended") { true.takeIf { serverProcesses().isEmpty() } }
        }
    }

    /** The running scrcpy servers' own command lines, without the shells that started them. */
    private fun serverProcesses(): List<String> =
        shell("ps -A -o ARGS").lines().map { it.trim() }
            .filter { it.startsWith("app_process") && "com.genymobile.scrcpy.Server " in it }
            .map { "$it " }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    private fun withModel(block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("server-version-live-test", model)
        }
        try {
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> await(description: String, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("$description: timed out")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val TAG = "ServerVersionLive"
    }
}
