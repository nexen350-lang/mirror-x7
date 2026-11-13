package com.scrcpy.remote.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ctrl+V on a keyboard attached to this phone pastes this phone's clipboard on the device, as it
 * does on scrcpy's desktop client, while clipboards are synced. It used to paste whatever the
 * device had copied last, although the setting promised that copying crossed both ways.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardKeysActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val clip = ClipData.newPlainText("phone", "phone text")

    @Test fun ctrlVSendsThisPhonesClipboardFirstOnlyWhileClipboardsAreSynced() {
        session { model ->
            forceConnected(model, autosync = true)
            var reads = 0
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl, ::now) { reads++; clip } }
            assertEquals("Ctrl+V sends this phone's clipboard first", 1, reads)
            main { model.sendKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, ctrl, ::now) { reads++; clip } }
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, 0, ::now) { reads++; clip } }
            assertEquals("Neither its release nor a plain V reads the clipboard", 1, reads)

            forceConnected(model, autosync = false)
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl, ::now) { reads++; clip } }
            assertEquals("With each clipboard kept private, nothing is read", 1, reads)
        }
    }

    @Test fun aCopyMadeOnTheDeviceWhileTheMirrorWasHiddenIsWhatCtrlVPastes() {
        session { model ->
            forceConnected(model, autosync = true)
            val before = System.currentTimeMillis() - 60_000
            main { model.onVisibilityChanged(false) }
            // The device's copy arrives while hidden, and is not put on this phone's clipboard.
            main { receiveClipboard(model, "device text") }
            main { model.onVisibilityChanged(true) }
            var reads = 0
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl, { before }) { reads++; clip } }
            assertEquals("An older clip on this phone is not sent over the device's newer copy", 0, reads)
            val after = System.currentTimeMillis() + 60_000
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl, { after }) { reads++; clip } }
            assertEquals("A clip copied on this phone since is", 1, reads)
        }
    }

    @Test fun aCopyLeftOnTheLastDeviceDoesNotHoldBackTheNextOnesCtrlV() {
        session { model ->
            forceConnected(model, autosync = true)
            main { model.onVisibilityChanged(false) }
            main { receiveClipboard(model, "device text") }
            main { model.onVisibilityChanged(true) }
            // Left, as for another device: what was copied on that one is not on the next.
            main { model.disconnect() }
            forceConnected(model, autosync = true)
            val before = System.currentTimeMillis() - 60_000
            var reads = 0
            main { model.sendKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, ctrl, { before }) { reads++; clip } }
            assertEquals("This phone's clip goes to the device connected now", 1, reads)
        }
    }

    private fun session(block: (MirrorViewModel) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, INVALID_PORT)).use { scenario ->
            lateinit var model: MirrorViewModel
            scenario.onActivity { model = ViewModelProvider(it)[MirrorViewModel::class.java] }
            try {
                block(model)
            } finally {
                main { model.disconnect() }
                MirrorSessionService.stop(context)
            }
        }
    }

    private fun receiveClipboard(model: MirrorViewModel, text: String) {
        MirrorViewModel::class.java.getDeclaredMethod("receiveClipboard", String::class.java)
            .apply { isAccessible = true }.invoke(model, text)
    }

    /**
     * The invalid endpoint has failed, or the session was left; a screen session is then shown
     * with the clipboard setting given.
     */
    private fun forceConnected(model: MirrorViewModel, autosync: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            main {
                ready = model.state.value.let {
                    it is MirrorUiState.Failed || it is MirrorUiState.Connected || it is MirrorUiState.Disconnected
                }
            }
            if (ready) break
            SystemClock.sleep(20)
        }
        val configured = DeviceRepository.get(context).settings.value
        main {
            if (model.state.value is MirrorUiState.Failed) model.disconnect()
            flow<MirrorSettings?>(model, "_activeSettings").value = configured.copy(
                audioEnabled = false, backgroundAudio = false, autoReconnect = false, clipboardAutosync = autosync)
            flow<MirrorUiState>(model, "_state").value = MirrorUiState.Connected("Clipboard fixture", 720, 1280, 1, 1)
        }
        var connected = false
        main { connected = model.state.value is MirrorUiState.Connected }
        assertTrue(connected)
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun now() = System.currentTimeMillis()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(owner: Any, name: String): MutableStateFlow<T> =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as MutableStateFlow<T>

    private companion object {
        const val HOST = "127.0.0.1"
        const val INVALID_PORT = 0
    }
}
