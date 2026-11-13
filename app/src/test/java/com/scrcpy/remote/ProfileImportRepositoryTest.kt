package com.scrcpy.remote

import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProfileImportRepositoryTest {
    private class MemoryStorage(initial: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = initial.toMutableMap()
        var fail = false
        var writes = 0
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            if (fail) return false
            writes++
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }
    private val original = SavedDevice("one", "Original", "10.0.0.1",
        portraitCalibration = TouchCalibration(7, -20), isFavorite = true,
        mirroringSettings = DeviceMirroringSettings(maxSize = 1280))
    private val added = SavedDevice("two", "Added", "10.0.0.2")
    private val incompleteGlobalSettings = listOf(
        """{"schemaVersion":1,"maxFps":45,"futureOption":37}""",
        """{"schemaVersion":1,"maxFps":45,"camera":{"id":"0","futureOption":37}}""",
    )

    @Test fun importingOnlyNewDevicesPreservesExistingSettingsAndPairingMaterial() {
        val storage = MemoryStorage(mapOf("identitySentinel" to "not part of a backup"))
        val repo = DeviceRepository(storage)
        repo.saveDevice(original)
        repo.updateSettings { it.copy(touchOffsetYDp = -25) }
        val before = repo.configurationSnapshot()
        val imported = ConfigurationSnapshot(listOf(original.copy(name = "Updated"), added),
            MirrorSettings(audioEnabled = false))
        assertTrue(repo.importConfiguration(imported, before, replaceExisting = false, importDefaults = false))
        assertEquals(listOf(original, added), repo.getDevices())
        assertEquals(before.settings, repo.getSettings())
        assertEquals("not part of a backup", storage.values["identitySentinel"])
    }

    @Test fun explicitReplacementAndDefaultsCommitTogetherWithPreImportBackup() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        repo.saveDevice(original)
        repo.updateSettings { it.copy(maxFps = 30) }
        val before = repo.configurationSnapshot()
        val imported = ConfigurationSnapshot(listOf(original.copy(name = "Restored")),
            MirrorSettings(maxSize = 800, audioEnabled = false))
        val writes = storage.writes
        assertTrue(repo.importConfiguration(imported, before, true, true))
        assertEquals(writes + 1, storage.writes)
        assertEquals(before, repo.backupSnapshot(repo.backupHistory.value.single().id))
        assertTrue(repo.backupHistory.value.single().isComplete)
        assertFalse(storage.values.keys.any { ".importBackup." in it })
        assertEquals(imported, repo.configurationSnapshot())
        assertEquals(imported, DeviceRepository(storage).configurationSnapshot())
    }

    @Test fun failedCommitLeavesBothDevicesAndDefaultsUnchanged() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        repo.saveDevice(original)
        val before = repo.configurationSnapshot()
        val disk = storage.values.toMap()
        storage.fail = true
        assertFalse(repo.importConfiguration(ConfigurationSnapshot(listOf(added), MirrorSettings(maxFps = 30)), before, true, true))
        assertEquals(before, repo.configurationSnapshot())
        assertEquals(disk, storage.values)
    }

    @Test fun stalePreviewCannotOverwriteLiveCalibration() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(original)
        val before = repo.configurationSnapshot()
        repo.setCalibration(original.id, false, TouchCalibration(-5, -40))
        assertFalse(repo.importConfiguration(ConfigurationSnapshot(listOf(original), MirrorSettings()), before, true, true))
        assertEquals(TouchCalibration(-5, -40), repo.getDevices().single().portraitCalibration)
        assertTrue(repo.errors.value.any { it.key == "import" })
    }

    @Test fun repeatingImportIsIdempotentAndDoesNotCreateExtraBackups() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        val imported = ConfigurationSnapshot(listOf(original, added), MirrorSettings())
        assertTrue(repo.importConfiguration(imported, repo.configurationSnapshot(), false, false))
        val writes = storage.writes
        val disk = storage.values.toMap()
        assertTrue(repo.importConfiguration(imported, repo.configurationSnapshot(), false, false))
        assertEquals(writes, storage.writes)
        assertEquals(disk, storage.values)
    }

    @Test fun damagedSourceCannotBeMisrepresentedAsACompleteExport() {
        val storage = MemoryStorage(mapOf("devices" to "[broken", "settings" to "{broken"))
        val repo = DeviceRepository(storage)
        assertThrows(IOException::class.java) { repo.configurationForExport() }
        val incoming = ConfigurationSnapshot(listOf(added), MirrorSettings(maxFps = 30))
        assertTrue(repo.importConfiguration(incoming, repo.configurationSnapshot(), false, true))
        assertEquals("[broken", storage.values["devices.recovery.1"])
        assertEquals("{broken", storage.values["settings.recovery.1"])
        assertTrue(repo.backupHistory.value.none { it.isComplete })
        assertEquals(incoming, repo.configurationForExport())
    }

    @Test fun unknownGlobalFieldsCannotBeMisrepresentedAsACompleteExport() {
        for (raw in incompleteGlobalSettings) {
            val storage = MemoryStorage(mapOf("settings" to raw))
            val repo = DeviceRepository(storage)
            assertTrue(repo.errors.value.toString(), repo.errors.value.isEmpty())
            val before = storage.values.toMap()

            assertThrows(IOException::class.java) { repo.configurationForExport() }

            assertEquals(before, storage.values)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun devicesOnlyImportDoesNotLabelUnknownGlobalFieldsAsCompleteHistory() {
        for (raw in incompleteGlobalSettings) {
            val storage = MemoryStorage(mapOf("settings" to raw))
            val repo = DeviceRepository(storage)
            assertTrue(repo.saveDevice(original))
            val before = repo.configurationSnapshot()
            val originalDevices = storage.values.getValue("devices")

            assertTrue(repo.importConfiguration(
                ConfigurationSnapshot(listOf(added), MirrorSettings()), before, false, false))

            assertEquals(listOf(original, added), repo.getDevices())
            assertEquals(before.settings, repo.getSettings())
            assertEquals(raw, storage.values["settings"])
            assertTrue(repo.backupHistory.value.isNotEmpty())
            assertTrue(repo.backupHistory.value.toString(), repo.backupHistory.value.none { it.isComplete })
            assertTrue("Original profiles must remain available for recovery",
                storage.values.values.any { it == originalDevices })
            repo.backupHistory.value.forEach { entry ->
                assertThrows(IOException::class.java) { repo.backupSnapshot(entry.id) }
            }
        }
    }

    @Test fun preservingAnInvalidFutureFieldDoesNotMakeItACompleteExport() {
        val storage = MemoryStorage(mapOf("settings" to """{"schemaVersion":99,"videoCodec":"FUTURE"}"""))
        val repo = DeviceRepository(storage)
        assertTrue(repo.updateSettings { it.copy(touchOffsetYDp = 5) })
        assertThrows(IOException::class.java) { repo.configurationForExport() }
        assertTrue(repo.importConfiguration(ConfigurationSnapshot(listOf(added), MirrorSettings()), repo.configurationSnapshot(), false, false))
        assertTrue(repo.backupHistory.value.none { it.isComplete })
        assertTrue(storage.values.containsKey("settings.recovery.1"))
    }

    @Test fun importingOverAFieldThisVersionCannotReadKeepsTheOriginalForRecovery() {
        // An ordinary save patches the retained source, so a field written by a newer version
        // survives. An import replaces the whole value instead, and calling that a complete
        // snapshot would throw the field away with nothing left to recover it from.
        val storage = MemoryStorage(mapOf("settings" to """{"schemaVersion":1,"futureOption":37}"""))
        val repo = DeviceRepository(storage)
        // It reads cleanly: this is not damaged data, only data this build cannot fully write.
        assertTrue(repo.errors.value.toString(), repo.errors.value.isEmpty())

        assertTrue(repo.importConfiguration(
            ConfigurationSnapshot(listOf(added), MirrorSettings(maxFps = 30)),
            repo.configurationSnapshot(), false, true))

        assertEquals(30, repo.settings.value.maxFps)
        assertTrue(repo.backupHistory.value.none { it.isComplete })
        assertTrue(storage.values.keys.toString(), storage.values.containsKey("settings.recovery.1"))
        assertTrue(storage.values["settings.recovery.1"].toString().contains("futureOption"))
    }

    @Test fun importingOverANestedCameraFieldRetainsOriginalGlobalBytes() {
        val raw = """{"schemaVersion":1,"maxFps":60,
            "camera":{"id":"0","futureOption":{"mode":"steady","levels":[1,2]}}}"""
        val storage = MemoryStorage(mapOf("settings" to raw))
        val repo = DeviceRepository(storage)
        val before = repo.configurationSnapshot()
        assertEquals("0", before.settings.camera?.id)
        assertTrue(repo.errors.value.toString(), repo.errors.value.isEmpty())
        assertEquals(0, storage.writes)

        val incoming = before.copy(settings = before.settings.copy(maxFps = 30))
        assertTrue(repo.importConfiguration(incoming, before, false, true))

        assertEquals("Import must retain camera fields absent from the portable snapshot",
            raw, storage.values["settings.recovery.1"])
        assertTrue(repo.backupHistory.value.none { it.isComplete })
        assertEquals(incoming, repo.configurationSnapshot())
        assertEquals(incoming, DeviceRepository(storage).configurationSnapshot())
    }

    @Test fun importingOverAnUnreadableSourceRepairsItEvenWhenTheValuesAlreadyMatch() {
        // Loading substitutes defaults for a source it cannot parse, so importing those same
        // defaults looked like a no-op: it returned success and left the broken text in place,
        // with exports still refused. An import is the user's chance to replace it.
        val storage = MemoryStorage(mapOf("settings" to "{broken"))
        val repo = DeviceRepository(storage)
        assertThrows(IOException::class.java) { repo.configurationForExport() }

        assertTrue(repo.importConfiguration(
            ConfigurationSnapshot(emptyList(), MirrorSettings()),
            repo.configurationSnapshot(), false, true))

        assertNotEquals("{broken", storage.values["settings"])
        // Readable again, so the configuration can be exported rather than refused.
        repo.configurationForExport()
    }
}
