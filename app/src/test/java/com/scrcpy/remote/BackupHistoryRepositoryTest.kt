package com.scrcpy.remote

import com.scrcpy.remote.data.BackupHistoryKind
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BackupHistoryRepositoryTest {
    private class MemoryStorage(extra: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = (mapOf<String, Any?>("settings" to SettingsCodec.encodeSettings(MirrorSettings())) + extra).toMutableMap()
        var failRead = false
        var failWrite = false
        var writes = 0
        override fun read(): Map<String, *> {
            if (failRead) throw IOException("read denied")
            return values.toMap()
        }
        override fun write(values: Map<String, Any?>): Boolean {
            writes++
            if (failWrite) return false
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    private fun incoming(index: Int) = ConfigurationSnapshot(
        listOf(SavedDevice(id = "history-device", name = "Version $index", host = "phone.local")),
        MirrorSettings(maxFps = 30),
    )

    @Test fun importsKeepOnlyTheLatestFiveCompleteSnapshotsIncludingUnchangedDefaults() {
        val storage = MemoryStorage(mapOf("identitySentinel" to "private-material-not-for-export"))
        val repository = DeviceRepository(storage)
        val snapshots = mutableListOf<ConfigurationSnapshot>()
        repeat(12) { index ->
            val before = repository.configurationSnapshot()
            snapshots += before
            assertTrue(repository.importConfiguration(incoming(index), before, true, false))
            assertTrue(repository.backupHistory.value.size <= 5)
        }
        assertEquals(snapshots.takeLast(5).reversed(), repository.backupHistory.value.map { repository.backupSnapshot(it.id) })
        assertEquals(5, storage.values.keys.count { it.startsWith("profileHistory.") })
        assertFalse(storage.values.keys.any { ".importBackup." in it })
        assertTrue(storage.values.filterKeys { it.startsWith("profileHistory.") }.values.none {
            it.toString().contains("identitySentinel") || it.toString().contains("private-material-not-for-export")
        })
        assertEquals("private-material-not-for-export", storage.values["identitySentinel"])
        val reloaded = DeviceRepository(storage)
        assertTrue(reloaded.refreshBackupHistory())
        assertEquals(repository.backupHistory.value, reloaded.backupHistory.value)
    }

    @Test fun legacyCountersAreMigratedIndividuallyAndNeverFabricateCompleteSnapshots() {
        val originals = mapOf<String, Any?>(
            "devices.importBackup.1" to SettingsCodec.encodeDevices(incoming(1).devices),
            "settings.importBackup.1" to SettingsCodec.encodeSettings(MirrorSettings(maxFps = 90)),
            "devices.importBackup.2" to "[broken",
            "settings.recovery.4" to 73,
        )
        val storage = MemoryStorage(originals)
        val repository = DeviceRepository(storage)
        assertTrue(repository.refreshBackupHistory())
        assertEquals(4, repository.backupHistory.value.size)
        repository.backupHistory.value.forEach {
            assertFalse(it.isComplete)
            assertNull(it.createdAtMillis)
            assertThrows(IOException::class.java) { repository.backupSnapshot(it.id) }
        }
        originals.forEach { (key, value) -> assertEquals(value, storage.values[key]) }
        assertEquals(4, storage.values.keys.count { it.startsWith("profileHistory.") })
        val disk = storage.values.toMap()
        val writes = storage.writes
        assertTrue(DeviceRepository(storage).refreshBackupHistory())
        assertEquals(disk, storage.values)
        assertEquals(writes, storage.writes)
    }

    @Test fun retentionDoesNotDropDamagedRecoveryLegacyOrUnreadableHistory() {
        val badRecord = "profileHistory.${UUID.randomUUID()}"
        val storage = MemoryStorage(mapOf(
            "settings.recovery.1" to "{broken",
            "devices.importBackup.6" to "wrong",
            badRecord to "unreadable history",
        ))
        val repository = DeviceRepository(storage)
        repeat(9) {
            assertTrue(repository.importConfiguration(incoming(it), repository.configurationSnapshot(), true, false))
        }
        assertEquals(5, repository.backupHistory.value.count { it.isComplete })
        assertEquals(3, repository.backupHistory.value.count { !it.isComplete })
        assertEquals("{broken", storage.values["settings.recovery.1"])
        assertEquals("wrong", storage.values["devices.importBackup.6"])
        assertEquals("unreadable history", storage.values[badRecord])
        assertTrue(repository.errors.value.any { it.key == badRecord })
    }

    @Test fun failedImportCannotPruneHistoryOrPublishAnUncommittedSnapshot() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        repeat(5) { assertTrue(repository.importConfiguration(incoming(it), repository.configurationSnapshot(), true, false)) }
        val before = repository.configurationSnapshot()
        val history = repository.backupHistory.value
        val disk = storage.values.toMap()
        storage.failWrite = true
        assertFalse(repository.importConfiguration(incoming(9), before, true, true))
        assertEquals(before, repository.configurationSnapshot())
        assertEquals(history, repository.backupHistory.value)
        assertEquals(disk, storage.values)
        storage.failWrite = false
        assertTrue(repository.importConfiguration(incoming(9), before, true, true))
        assertEquals(before, repository.backupSnapshot(repository.backupHistory.value.first().id))
        assertEquals(5, repository.backupHistory.value.size)
    }

    @Test fun explicitDeletionRemovesOnlyItsRecordAndNeverCurrentConfiguration() {
        val storage = MemoryStorage(mapOf("devices.importBackup.1" to "[broken", "identitySentinel" to "untouched"))
        val repository = DeviceRepository(storage)
        assertTrue(repository.importConfiguration(incoming(1), repository.configurationSnapshot(), true, false))
        val current = repository.configurationSnapshot()
        val complete = repository.backupHistory.value.single { it.isComplete }
        val partial = repository.backupHistory.value.single { !it.isComplete }
        assertTrue(repository.deleteBackupHistory(complete.id))
        assertEquals(current, repository.configurationSnapshot())
        assertEquals("[broken", storage.values["devices.importBackup.1"])
        assertTrue(repository.deleteBackupHistory(partial.id))
        assertFalse(storage.values.containsKey(partial.id))
        assertFalse(storage.values.containsKey("devices.importBackup.1"))
        assertFalse(repository.deleteBackupHistory("identitySentinel"))
        assertEquals("untouched", storage.values["identitySentinel"])
        assertEquals(current, repository.configurationSnapshot())
    }

    @Test fun failedMigrationAndDeletionKeepAllOriginalsAndReportFailure() {
        val storage = MemoryStorage(mapOf("settings.importBackup.1" to 15))
        val repository = DeviceRepository(storage)
        storage.failWrite = true
        assertFalse(repository.refreshBackupHistory())
        assertEquals(15, storage.values["settings.importBackup.1"])
        assertTrue(storage.values.keys.none { it.startsWith("profileHistory.") })
        assertFalse(repository.deleteBackupHistory(repository.backupHistory.value.single().id))
        assertEquals(15, storage.values["settings.importBackup.1"])
        storage.failWrite = false
        assertTrue(repository.refreshBackupHistory())
        val disk = storage.values.toMap()
        storage.failRead = true
        assertFalse(repository.refreshBackupHistory())
        assertFalse(repository.deleteBackupHistory(repository.backupHistory.value.single().id))
        assertEquals(disk, storage.values)
        assertTrue(repository.errors.value.any { it.key == "storage" })
    }

    @Test fun untrustedHistoryReferenceCannotCauseDeletionOfUnrelatedPreferences() {
        val key = "profileHistory.${UUID.randomUUID()}"
        val storage = MemoryStorage(mapOf("identitySentinel" to "untouched",
            key to """{"version":1,"name":"Invalid","sourceKey":"identitySentinel"}"""))
        val repository = DeviceRepository(storage)
        assertTrue(repository.refreshBackupHistory())
        assertEquals(BackupHistoryKind.UNREADABLE, repository.backupHistory.value.single().kind)
        assertTrue(repository.deleteBackupHistory(key))
        assertEquals("untouched", storage.values["identitySentinel"])
    }
}
