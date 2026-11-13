package com.scrcpy.remote.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.ProfileBackupCodec
import com.scrcpy.remote.data.ProfileDocuments
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProfileTransferStateTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val repository = DeviceRepository.get(app)
    private val defaults = repository.getSettings()
    private val documents = ProfileDocuments(app)
    private val ids = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val stores = mutableListOf<ViewModelStore>()
    private val tokens = mutableListOf<String>()

    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            stores.forEach { it.clear() }
            ids.forEach { assertTrue(repository.deleteDevice(it)) }
            assertTrue(repository.updateSettings { defaults })
        }
        tokens.forEach { documents.discard(it) }
        files.forEach { if (it.exists()) assertTrue(it.delete()) }
    }

    @Test fun selectingAValidDocumentOnlyPreviewsAndCancelKeepsConfiguration() = runBlocking {
        val before = repository.configurationSnapshot()
        val incoming = ConfigurationSnapshot(listOf(device()), defaults.copy(maxFps = 30))
        val saved = SavedStateHandle()
        val vm = model(saved)
        select(vm, incoming)
        assertEquals(before, repository.configurationSnapshot())
        assertFalse(vm.state.value.replaceExisting)
        assertFalse(vm.state.value.importDefaults)
        assertEquals(1, vm.state.value.preview!!.added)
        val token = checkNotNull(saved.get<String>("profileTransfer.token"))
        withContext(Dispatchers.Main) { vm.cancel() }
        assertEquals(before, repository.configurationSnapshot())
        awaitDiscard(token)
    }

    @Test fun defaultImportAddsProfilesButKeepsConflictsAndGlobalDefaults() = runBlocking {
        val original = device()
        assertTrue(repository.saveDevice(original))
        val added = device()
        val vm = model()
        select(vm, ConfigurationSnapshot(listOf(original.copy(name = "Changed"), added), defaults.copy(maxFps = 30)))
        assertEquals(1, vm.state.value.preview!!.skipped)
        withContext(Dispatchers.Main) { vm.confirmImport() }
        awaitIdle(vm)
        assertEquals(original, repository.getDevices().single { it.id == original.id })
        assertEquals(added, repository.getDevices().single { it.id == added.id })
        assertEquals(defaults, repository.getSettings())
    }

    @Test fun recreatedPreviewRestoresOptionsButNeedsExplicitConfirmation() = runBlocking {
        val original = device()
        assertTrue(repository.saveDevice(original))
        val replacement = original.copy(name = "Restored", mirroringSettings = DeviceMirroringSettings(maxSize = 800))
        val incoming = ConfigurationSnapshot(listOf(replacement), defaults.copy(maxFps = 30))
        val token = documents.cache(incoming).also { tokens += it }
        val saved = SavedStateHandle(mapOf("profileTransfer.stage" to "preview", "profileTransfer.token" to token,
            "profileTransfer.replace" to true, "profileTransfer.defaults" to true))
        val vm = model(saved)
        awaitIdle(vm)
        assertEquals(original, repository.getDevices().single { it.id == original.id })
        assertEquals(defaults, repository.getSettings())
        assertTrue(vm.state.value.replaceExisting)
        assertTrue(vm.state.value.importDefaults)
        assertNull(withContext(Dispatchers.Main) { vm.takePickerRequest() })
        withContext(Dispatchers.Main) { vm.confirmImport() }
        awaitIdle(vm)
        assertEquals(replacement, repository.getDevices().single { it.id == original.id })
        assertEquals(incoming.settings, repository.getSettings())
        awaitDiscard(token)
    }

    @Test fun exportWritesTheStagedSnapshotAndLaunchesOnlyOnePicker() = runBlocking {
        val original = device()
        assertTrue(repository.saveDevice(original))
        val expected = repository.configurationForExport()
        val vm = model()
        withContext(Dispatchers.Main) { vm.beginExport() }
        withTimeout(5_000) { vm.state.first { it.notice != null } }
        assertTrue(withContext(Dispatchers.Main) { vm.takePickerRequest() } is ProfilePicker.Export)
        assertNull(withContext(Dispatchers.Main) { vm.takePickerRequest() })
        assertTrue(repository.saveDevice(original.copy(name = "Changed after staging")))
        val destination = file()
        withContext(Dispatchers.Main) { vm.exportSelected(Uri.fromFile(destination)) }
        awaitIdle(vm)
        assertEquals(app.getString(R.string.transfer_exported), vm.state.value.notice)
        assertEquals(expected, ProfileBackupCodec.decode(destination.readBytes()))
    }

    @Test fun recreatedExportAcceptsReturnedPickerResultWithoutLaunchingAnother() = runBlocking {
        val expected = repository.configurationForExport()
        val token = documents.cache(expected).also { tokens += it }
        val saved = SavedStateHandle(mapOf("profileTransfer.stage" to "chooseExport",
            "profileTransfer.token" to token, "profileTransfer.picker" to true))
        val destination = file()
        val vm = withContext(Dispatchers.Main) {
            val model = modelOnMain(saved)
            assertNull(model.takePickerRequest())
            model.exportSelected(Uri.fromFile(destination))
            model
        }
        awaitIdle(vm)
        assertEquals(expected, ProfileBackupCodec.decode(destination.readBytes()))
        assertEquals(app.getString(R.string.transfer_exported), vm.state.value.notice)
        awaitDiscard(token)
    }

    @Test fun malformedImportAndFailedExportAreReportedWithoutChangingSettings() = runBlocking {
        val before = repository.configurationSnapshot()
        val source = file().apply { writeText("{invalid") }
        val vm = model()
        withContext(Dispatchers.Main) { vm.beginImport(); vm.importSelected(Uri.fromFile(source)) }
        awaitIdle(vm)
        assertNull(vm.state.value.preview)
        assertNotNull(vm.state.value.notice)
        assertEquals(before, repository.configurationSnapshot())
        withContext(Dispatchers.Main) { vm.beginExport() }
        withTimeout(5_000) { vm.state.first { it.notice != null } }
        val invalidDestination = File(app.cacheDir, "${UUID.randomUUID()}/cannot-create.json")
        withContext(Dispatchers.Main) { vm.exportSelected(Uri.fromFile(invalidDestination)) }
        awaitIdle(vm)
        assertTrue(vm.state.value.notice!!.startsWith("Could not save the backup"))
        assertEquals(before, repository.configurationSnapshot())
        assertFalse(invalidDestination.exists())
    }

    @Test fun missingRestoredTokenFailsWithoutLaunchingPickerOrWaitingForever() = runBlocking {
        val vm = model(SavedStateHandle(mapOf("profileTransfer.stage" to "chooseExport",
            "profileTransfer.token" to UUID.randomUUID().toString())))
        awaitIdle(vm)
        assertNotNull(vm.state.value.notice)
        assertNull(withContext(Dispatchers.Main) { vm.takePickerRequest() })
    }

    @Test fun cancellingRecreatedExportCannotResurrectPendingState() = runBlocking {
        val token = documents.cache(repository.configurationForExport()).also { tokens += it }
        val vm = withContext(Dispatchers.Main) {
            modelOnMain(SavedStateHandle(mapOf("profileTransfer.stage" to "chooseExport",
                "profileTransfer.token" to token))).also { it.cancel() }
        }
        awaitDiscard(token)
        delay(100)
        assertEquals(ProfileTransferState(), vm.state.value)
        assertNull(withContext(Dispatchers.Main) { vm.takePickerRequest() })
    }

    private fun device(): SavedDevice = SavedDevice(name = "Profile test", host = "127.0.0.1",
        portraitCalibration = TouchCalibration(-5, -40)).also { ids += it.id }

    private fun file() = File(app.cacheDir, "profile-test-${UUID.randomUUID()}.json").also { files += it }

    private suspend fun model(saved: SavedStateHandle = SavedStateHandle()) =
        withContext(Dispatchers.Main) { modelOnMain(saved) }

    private fun modelOnMain(saved: SavedStateHandle): ProfileTransferViewModel =
        ProfileTransferViewModel(app, saved).also { vm ->
            stores += ViewModelStore().apply { put("transfer", vm) }
        }

    private suspend fun select(vm: ProfileTransferViewModel, incoming: ConfigurationSnapshot) {
        val source = file().apply { writeBytes(ProfileBackupCodec.encode(incoming)) }
        withContext(Dispatchers.Main) { vm.beginImport(); vm.importSelected(Uri.fromFile(source)) }
        awaitIdle(vm)
        assertNotNull(vm.state.value.preview)
    }

    private suspend fun awaitIdle(vm: ProfileTransferViewModel) {
        withTimeout(5_000) { vm.state.first { !it.busy } }
    }

    private suspend fun awaitDiscard(token: String) {
        withTimeout(5_000) {
            while (File(app.cacheDir, "profile-transfers/$token.json").exists()) delay(10)
        }
    }
}
