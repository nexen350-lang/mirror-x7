package com.scrcpy.remote.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Observer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.ProfileDocuments
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.session.TestShareProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityStorageStateTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private class Storage(private val slow: Boolean = false) : PreferenceStorage {
        val values = mutableMapOf<String, Any?>("settings" to SettingsCodec.encodeSettings(MirrorSettings()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (slow) 1 else 0)
        var writes = 0
        var usedMain = false
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            usedMain = usedMain || Looper.myLooper() == Looper.getMainLooper()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            writes++
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    @Test fun slowSaveKeepsMainResponsiveAndRejectsDoubleSubmission() = runBlocking {
        val storage = Storage(slow = true)
        val repository = DeviceRepository(storage)
        val store = ViewModelStore()
        val device = SavedDevice(id = "first", name = "First", host = "phone.local")
        val vm = withContext(Dispatchers.Main) {
            MainViewModel(app, repository).also { store.put("main", it) }
        }
        try {
            withContext(Dispatchers.Main) {
                vm.saveDevice(device)
                vm.saveDevice(device.copy(id = "second"))
                assertTrue(vm.mutation.value.busy)
            }
            assertTrue(storage.entered.await(5, TimeUnit.SECONDS))
            withTimeout(1_000) { withContext(Dispatchers.Main) { assertTrue(vm.mutation.value.busy) } }
            assertTrue(repository.devices.value.isEmpty())
            storage.release.countDown()
            withTimeout(5_000) { vm.mutation.first { !it.busy && it.success != null } }
            assertEquals(true, vm.mutation.value.success)
            assertEquals(listOf(device), repository.devices.value)
            assertEquals(1, storage.writes)
            assertFalse(storage.usedMain)
            withContext(Dispatchers.Main) { vm.saveDevice(device.copy(id = "unconsumed-result")) }
            assertEquals(1, storage.writes)
            assertEquals(listOf(device), repository.devices.value)
        } finally {
            storage.release.countDown()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun cancelledShareHandoffDeletesTheCompletedCacheCopy() = runBlocking {
        val sourceName = "cancelled-share-${UUID.randomUUID()}.bin"
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val uri = TestShareProvider.offer(app, sourceName, payload)
        val cache = File(app.cacheDir, "shared-uploads")
        val existing = cache.listFiles().orEmpty().map { it.name }.toSet()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            MainViewModel(app, DeviceRepository(Storage())).also { store.put("main", it) }
        }
        var ingest: Job? = null
        var staged: File? = null
        TestShareProvider.beforeQuery = { requested ->
            if (requested == uri) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }

        try {
            withContext(Dispatchers.Main) {
                vm.ingestShare(uri)
                ingest = checkNotNull(vm.viewModelScope.coroutineContext[Job]).children.single()
            }
            assertTrue("The copy must reach its final metadata lookup", entered.await(5, TimeUnit.SECONDS))
            val copied = cache.listFiles().orEmpty().single { it.name !in existing }
            staged = copied
            assertArrayEquals(payload, copied.readBytes())
            assertNull(vm.shareIngest.value)

            withContext(Dispatchers.Main) { store.clear() }
            assertTrue(checkNotNull(ingest).isCancelled)
            release.countDown()
            withTimeout(5_000) { checkNotNull(ingest).join() }

            assertFalse("A copy whose cancelled owner never accepted it must be deleted", copied.exists())
            assertNull(vm.shareIngest.value)
        } finally {
            release.countDown()
            TestShareProvider.beforeQuery = null
            withContext(Dispatchers.Main) { store.clear() }
            try {
                withTimeout(5_000) { ingest?.join() }
            } finally {
                staged?.delete()
                File(TestShareProvider.directory(app), sourceName).delete()
            }
        }
    }

    @Test fun historyRestoreOnlyPreviewsAndSavedStateStaysOnMain() = runBlocking {
        val storage = Storage()
        val repository = DeviceRepository(storage)
        val oldDevice = SavedDevice(id = "phone", name = "Before import", host = "phone.local")
        assertTrue(repository.saveDevice(oldDevice))
        val original = repository.configurationSnapshot()
        val incoming = ConfigurationSnapshot(listOf(oldDevice.copy(name = "After import")), MirrorSettings(maxFps = 30))
        assertTrue(repository.importConfiguration(incoming, original, true, true))
        val before = repository.configurationSnapshot()
        val record = repository.backupHistory.value.single()
        val store = ViewModelStore()
        val stages = mutableListOf<String?>()
        val observer = Observer<String?> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper())
            stages += it
        }
        val saved = withContext(Dispatchers.Main) { SavedStateHandle() }
        val (history, transfer) = withContext(Dispatchers.Main) {
            saved.getLiveData<String?>("profileTransfer.stage").observeForever(observer)
            val history = BackupHistoryViewModel(app, SavedStateHandle(), repository)
            val transfer = ProfileTransferViewModel(app, saved, repository)
            store.put("history", history)
            store.put("transfer", transfer)
            history to transfer
        }
        try {
            withContext(Dispatchers.Main) { history.open() }
            withTimeout(5_000) { history.state.first { !it.busy } }
            withContext(Dispatchers.Main) { history.prepareSnapshot(record.id, transfer::previewSnapshot) }
            withTimeout(5_000) { transfer.state.first { !it.busy && it.preview != null } }
            assertEquals(before, repository.configurationSnapshot())
            assertEquals(incoming.settings, transfer.state.value.preview!!.after.settings)
            val token = withContext(Dispatchers.Main) { checkNotNull(saved.get<String>("profileTransfer.token")) }
            assertEquals(original, withContext(Dispatchers.IO) { ProfileDocuments(app).restore(token) })
            withContext(Dispatchers.Main) { transfer.updateOptions(replace = true, defaults = true) }
            withTimeout(5_000) { transfer.state.first { !it.busy } }
            assertEquals(before, repository.configurationSnapshot())
            withContext(Dispatchers.Main) { transfer.confirmImport(); transfer.confirmImport() }
            withTimeout(5_000) { transfer.state.first { !it.busy && it.preview == null } }
            assertEquals(original, repository.configurationSnapshot())
            assertEquals(before, repository.backupSnapshot(repository.backupHistory.value.first().id))
            assertFalse(storage.usedMain)
            assertTrue(stages.contains("loading"))
            assertTrue(stages.contains("preview"))
        } finally {
            withContext(Dispatchers.Main) {
                saved.getLiveData<String?>("profileTransfer.stage").removeObserver(observer)
                store.clear()
            }
        }
    }

    @Test fun chosenHistorySnapshotExportsFromTheExistingCacheWithoutChangingCurrentData() = runBlocking {
        val repository = DeviceRepository(Storage())
        val original = repository.configurationSnapshot()
        val incoming = ConfigurationSnapshot(listOf(SavedDevice(id = "phone", name = "Phone", host = "phone.local")), MirrorSettings())
        assertTrue(repository.importConfiguration(incoming, original, false, false))
        val saved = withContext(Dispatchers.Main) { SavedStateHandle() }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ProfileTransferViewModel(app, saved, repository).also { store.put("transfer", it); it.beginExport(original) }
        }
        try {
            withTimeout(5_000) { vm.state.first { it.notice != null } }
            val token = withContext(Dispatchers.Main) {
                assertTrue(vm.takePickerRequest() is ProfilePicker.Export)
                assertNull(vm.takePickerRequest())
                checkNotNull(saved.get<String>("profileTransfer.token"))
            }
            assertEquals(original, withContext(Dispatchers.IO) { ProfileDocuments(app).restore(token) })
            assertEquals(incoming, repository.configurationSnapshot())
            withContext(Dispatchers.Main) { vm.cancel() }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun supersededPreviewOptionsPublishOnlyTheLatestRevisionWithoutBlockingMain() = runBlocking {
        val repository = DeviceRepository(Storage())
        val existing = SavedDevice(id = "phone", name = "Current phone", host = "phone.local")
        assertTrue(repository.saveDevice(existing))
        val before = repository.configurationSnapshot()
        val incoming = ConfigurationSnapshot(listOf(existing.copy(name = "Backup phone")), MirrorSettings(maxFps = 30))
        val store = ViewModelStore()
        val saved = withContext(Dispatchers.Main) { SavedStateHandle() }
        val vm = withContext(Dispatchers.Main) {
            ProfileTransferViewModel(app, saved, repository).also {
                store.put("transfer", it)
                it.previewSnapshot(incoming)
            }
        }
        withTimeout(5_000) { vm.state.first { !it.busy && it.preview != null } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.mutate { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(1_000) {
                withContext(Dispatchers.Main) {
                    vm.updateOptions(replace = true, defaults = false)
                    vm.updateOptions(replace = false, defaults = true)
                    assertTrue(vm.state.value.busy)
                    vm.confirmImport()
                }
            }
            assertEquals(before, repository.configurationSnapshot())
            release.countDown()
            withTimeout(5_000) { vm.state.first { !it.busy } }
            assertFalse(vm.state.value.replaceExisting)
            assertTrue(vm.state.value.importDefaults)
            assertEquals(before.devices, vm.state.value.preview!!.after.devices)
            assertEquals(incoming.settings, vm.state.value.preview!!.after.settings)
            assertEquals(before, repository.configurationSnapshot())
            withContext(Dispatchers.Main) {
                assertEquals(false, saved.get<Boolean>("profileTransfer.replace"))
                assertEquals(true, saved.get<Boolean>("profileTransfer.defaults"))
                vm.cancel()
            }
        } finally {
            release.countDown()
            blocker.join()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    @Test fun cancelledPreviewCalculationCannotOverwriteANewIncomingSnapshot() = runBlocking {
        val repository = DeviceRepository(Storage())
        val original = repository.configurationSnapshot()
        val first = ConfigurationSnapshot(listOf(SavedDevice(id = "first", name = "First", host = "first.local")), MirrorSettings())
        val second = ConfigurationSnapshot(listOf(SavedDevice(id = "second", name = "Second", host = "second.local")), MirrorSettings())
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            ProfileTransferViewModel(app, SavedStateHandle(), repository).also {
                store.put("transfer", it)
                it.previewSnapshot(first)
            }
        }
        withTimeout(5_000) { vm.state.first { !it.busy && it.preview != null } }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.mutate { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) {
                vm.updateOptions(replace = true, defaults = true)
                vm.cancel()
                vm.previewSnapshot(second)
            }
            release.countDown()
            withTimeout(5_000) { vm.state.first { !it.busy && it.preview != null } }
            assertEquals(listOf("second"), vm.state.value.preview!!.entries.map { it.incoming.id })
            assertFalse(vm.state.value.replaceExisting)
            assertFalse(vm.state.value.importDefaults)
            assertEquals(original, repository.configurationSnapshot())
            withContext(Dispatchers.Main) { vm.cancel() }
        } finally {
            release.countDown()
            blocker.join()
            withContext(Dispatchers.Main) { store.clear() }
        }
    }
}
