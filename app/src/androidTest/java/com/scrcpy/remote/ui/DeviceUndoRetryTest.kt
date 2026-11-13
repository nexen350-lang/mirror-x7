package com.scrcpy.remote.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.DeviceIcon
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.ConnectionHistoryStore
import com.scrcpy.remote.session.CheckupStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Undo is the only way back for a removed device, so an Undo that could not be written must not
 * use it up. The offer used to be dropped before the write, whatever the write did, and the device
 * went with its settings, calibration and look.
 */
@RunWith(AndroidJUnit4::class)
class DeviceUndoRetryTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private class Storage : PreferenceStorage {
        private val values = mutableMapOf<String, Any?>("settings" to SettingsCodec.encodeSettings(MirrorSettings()))
        @Volatile var failWrites = false
        override fun read(): Map<String, *> = synchronized(values) { values.toMap() }
        override fun write(values: Map<String, Any?>): Boolean {
            if (failWrites) return false
            synchronized(this.values) {
                values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            }
            return true
        }
    }

    @Test fun anUndoThatCouldNotBeWrittenIsOfferedAgainUntilItIs() = runBlocking {
        val storage = Storage()
        val repository = DeviceRepository(storage)
        val device = SavedDevice(id = "undo-retry", name = "Kept phone", host = "10.0.0.7", avatarColor = 2,
            avatarIcon = DeviceIcon.PHONE)
        assertTrue(repository.saveDevice(device))
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) { MainViewModel(app, repository).also { store.put("main", it) } }
        try {
            withContext(Dispatchers.Main) { model.deleteDevice(device.id) }
            val removed = checkNotNull(withTimeout(5_000) { model.recentlyRemoved.first { it != null } })
            storage.failWrites = true
            withContext(Dispatchers.Main) { model.undoRemoval(removed.serial) }
            val again = checkNotNull(withTimeout(5_000) {
                model.recentlyRemoved.first { it != null && it.serial != removed.serial }
            })
            assertEquals(removed.device, again.device)
            assertEquals(removed.index, again.index)
            assertTrue("The second offer says the first did not work", again.restoreFailed)
            assertTrue(repository.devices.value.none { it.id == device.id })

            storage.failWrites = false
            withContext(Dispatchers.Main) { model.undoRemoval(again.serial) }
            withTimeout(5_000) { repository.devices.first { list -> list.any { it.id == device.id } } }
            assertEquals(device, repository.devices.value.single { it.id == device.id })
            withTimeout(5_000) { model.recentlyRemoved.first { it == null } }
            assertNull(model.recentlyRemoved.value)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun aRemovalStillOfferedWhenTheListGoesAwayTakesItsHistoryWithIt() = runBlocking {
        // Leaving the list during the Undo message, as Back does on Android 8 to 11, ends the offer
        // with the screen, and the removed device's history stayed behind.
        val repository = DeviceRepository(Storage())
        val device = SavedDevice(id = "undo-closed", name = "Closed list", host = "10.0.0.8")
        assertTrue(repository.saveDevice(device))
        val directory = java.io.File(app.cacheDir, "undo-history-${System.nanoTime()}").apply { mkdirs() }
        val history = ConnectionHistoryStore(java.io.File(directory, "history.json"), writer = { it.run() })
        history.recordFailure(device.id, CheckupStage.REACH, FailureKind.OTHER, "10.0.0.8:5555")
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) { MainViewModel(app, repository, history).also { store.put("main", it) } }
        try {
            withContext(Dispatchers.Main) { model.deleteDevice(device.id) }
            withTimeout(5_000) { model.recentlyRemoved.first { it != null } }
            assertTrue("Undo can still bring it back with its history", device.id in history.history.value)
            withContext(Dispatchers.Main) { store.clear() }
            assertTrue("The list went with the offer: the history goes too", device.id !in history.history.value)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            directory.deleteRecursively()
        }
    }
}