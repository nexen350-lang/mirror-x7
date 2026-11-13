package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.planImport
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileTransferDialogTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Storage(rawSettings: String) : PreferenceStorage {
        private val values = mutableMapOf<String, Any?>("settings" to rawSettings)
        @Volatile var writes = 0
            private set

        @Synchronized
        override fun read(): Map<String, *> = values.toMap()

        @Synchronized
        override fun write(values: Map<String, Any?>): Boolean {
            values.forEach { (key, value) ->
                if (value == null) this.values.remove(key) else this.values[key] = value
            }
            writes++
            return true
        }
    }

    @Test fun replacementAndGlobalRestoreAreIndependentExplicitChoices() {
        val current = ConfigurationSnapshot(listOf(SavedDevice("same", "Existing", "127.0.0.1")), MirrorSettings())
        val incoming = ConfigurationSnapshot(listOf(current.devices.single().copy(name = "Backup")), MirrorSettings(maxFps = 30))
        val state = mutableStateOf(ProfileTransferState(preview = planImport(current, incoming)))
        var confirmed = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    ProfileTransferDialogs(state.value, { replace, defaults ->
                        state.value = ProfileTransferState(preview = planImport(current, incoming, replace, defaults),
                            replaceExisting = replace, importDefaults = defaults)
                    }, { confirmed = true }, {})
                }
            }
        }
        compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsNotEnabled()
        scrollTo(R.string.transfer_defaults)
        compose.onNodeWithText(text(R.string.transfer_defaults)).performClick()
        compose.runOnIdle {
            assertFalse(confirmed)
            assertFalse(state.value.replaceExisting)
            assertEquals(current.devices, state.value.preview!!.after.devices)
            assertEquals(incoming.settings, state.value.preview!!.after.settings)
        }
        scrollTo(R.string.transfer_replace)
        compose.onNodeWithText(text(R.string.transfer_replace)).performClick()
        compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(confirmed)
            assertEquals(incoming, state.value.preview!!.after)
        }
    }

    @Test fun longPreviewKeepsCancelAndConfirmAccessibleWhileListScrolls() {
        val incoming = ConfigurationSnapshot((1..100).map { SavedDevice("id-$it", "Device $it", "127.0.0.1") }, MirrorSettings())
        val current = ConfigurationSnapshot(emptyList(), MirrorSettings())
        var cancelled = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    ProfileTransferDialogs(ProfileTransferState(preview = planImport(current, incoming)),
                        { _, _ -> }, {}, { cancelled = true })
                }
            }
        }
        compose.onNodeWithTag("profile-import-preview")
            .performScrollToNode(hasText("${text(R.string.transfer_device_add)}: Device 100"))
        compose.onNodeWithText("${text(R.string.transfer_device_add)}: Device 100").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }

    @Test fun sameValueImportCanRepairDamagedStorageAfterExplicitConfirmation() {
        val raw = "{broken"
        val storage = Storage(raw)
        val repository = DeviceRepository(storage)
        val store = ViewModelStore()
        assertThrows(IOException::class.java) { repository.configurationForExport() }

        try {
            val vm = showSameValuePreview(repository, store)
            scrollTo(R.string.transfer_defaults)
            compose.onNodeWithText(text(R.string.transfer_defaults)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                !vm.state.value.busy && vm.state.value.importDefaults
            }
            val preview = checkNotNull(vm.state.value.preview)
            assertEquals(preview.before, preview.after)
            assertEquals(raw, storage.read()["settings"])
            assertEquals(0, storage.writes)

            compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                !vm.state.value.busy && vm.state.value.preview == null
            }

            assertEquals(preview.after, repository.configurationForExport())
            assertEquals(raw, storage.read()["settings.recovery.1"])
            assertEquals(1, storage.writes)
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    @Test fun cleanSameValueImportStaysDisabledEvenWithBothRestoreOptionsSelected() {
        val storage = Storage(SettingsCodec.encodeSettings(MirrorSettings()))
        val repository = DeviceRepository(storage)
        val before = storage.read()
        val store = ViewModelStore()

        try {
            val vm = showSameValuePreview(repository, store)
            compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsNotEnabled()
            scrollTo(R.string.transfer_defaults)
            compose.onNodeWithText(text(R.string.transfer_defaults)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                !vm.state.value.busy && vm.state.value.importDefaults
            }
            scrollTo(R.string.transfer_replace)
            compose.onNodeWithText(text(R.string.transfer_replace)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                !vm.state.value.busy && vm.state.value.replaceExisting
            }

            compose.onNodeWithText(text(R.string.transfer_confirm)).assertIsNotEnabled()
            assertEquals(before, storage.read())
            assertEquals(0, storage.writes)
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }

    private fun showSameValuePreview(repository: DeviceRepository, store: ViewModelStore): ProfileTransferViewModel {
        lateinit var vm: ProfileTransferViewModel
        compose.runOnUiThread {
            vm = ProfileTransferViewModel(compose.activity.application, SavedStateHandle(), repository)
            store.put("transfer", vm)
            compose.activity.setContent {
                val state by vm.state.collectAsState()
                ScrcpyRemoteTheme {
                    ProfileTransferDialogs(state, vm::updateOptions, vm::confirmImport, vm::cancel)
                }
            }
            vm.previewSnapshot(ConfigurationSnapshot(emptyList(), MirrorSettings()))
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            !vm.state.value.busy && vm.state.value.preview != null
        }
        return vm
    }

    private fun scrollTo(resource: Int) =
        compose.onNodeWithTag("profile-import-preview").performScrollToNode(hasText(text(resource)))
    private fun text(resource: Int) = compose.activity.getString(resource)
}
