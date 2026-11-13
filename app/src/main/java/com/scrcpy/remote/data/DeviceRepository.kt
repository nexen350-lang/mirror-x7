package com.scrcpy.remote.data

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** One application-scoped writer; transforms and dialog patches use the latest committed state. */
class DeviceRepository(
    private val storage: PreferenceStorage,
    private val reportError: (PersistenceIssue) -> Unit = {},
    /**
     * Where the one-time schema upgrade runs.
     *
     * Inline by default, so a directly constructed repository is fully migrated by the time the
     * constructor returns. The application factory hands it to the shared writer instead, which
     * keeps a synchronous disk commit off the startup path while staying ordered ahead of any
     * save the user makes later.
     */
    private val runMigration: (() -> Unit) -> Unit = { it() },
) {
    private val lock = Any()
    private val _settings = MutableStateFlow(MirrorSettings())
    private val _devices = MutableStateFlow<List<SavedDevice>>(emptyList())
    private val _errors = MutableStateFlow<List<PersistenceIssue>>(emptyList())
    private val _backupHistory = MutableStateFlow<List<BackupHistoryEntry>>(emptyList())
    private var settingsSource: JsonObject? = null
    private var settingsNeedRecovery = false
    private var settingsDamaged = false
    private var devicesNeedRecovery = false
    private var readable = true

    @get:JvmName("getSettingsFlow")
    val settings: StateFlow<MirrorSettings> = _settings.asStateFlow()
    @get:JvmName("getDevicesFlow")
    val devices: StateFlow<List<SavedDevice>> = _devices.asStateFlow()
    val errors: StateFlow<List<PersistenceIssue>> = _errors.asStateFlow()
    val backupHistory: StateFlow<List<BackupHistoryEntry>> = _backupHistory.asStateFlow()
    val writing: StateFlow<Boolean> = PreferenceWriter.writing

    /**
     * UI callers enqueue synchronous repository operations here and await their checked result.
     * Accepted operations are FIFO on application-owned IO; cancelling the caller does not cancel
     * a commit. Keep UI state (especially SavedStateHandle) outside this background block.
     */
    suspend fun <T> mutate(operation: DeviceRepository.() -> T): T =
        PreferenceWriter.submit { operation(this) }

    init {
        // Read synchronously on purpose: callers read `devices` the moment they construct this,
        // for example to resolve a launcher shortcut inside onCreate, so an asynchronous load
        // would hand them an empty list. The file holds two keys, and the expensive part of
        // startup persistence — the migration commit below — is what gets moved off this thread.
        val preferences = readStorage()
        if (preferences != null) {
            val decoded = if (KEY_SETTINGS in preferences) {
                decodeSettingsPreference(preferences[KEY_SETTINGS])
            } else SettingsCodec.decodeLegacy(preferences)
            _settings.value = decoded.value
            settingsSource = decoded.source
            settingsNeedRecovery = decoded.issues.isNotEmpty()
            settingsDamaged = settingsNeedRecovery
            decoded.issues.forEach(::report)

            val devices = if (KEY_DEVICES !in preferences || preferences[KEY_DEVICES] is String) {
                SettingsCodec.decodeDevices(preferences[KEY_DEVICES] as? String)
            } else DecodedValue(emptyList(), listOf(PersistenceIssue(KEY_DEVICES, "Expected a JSON string; source retained")))
            _devices.value = devices.value
            devicesNeedRecovery = devices.issues.isNotEmpty()
            devices.issues.forEach(::report)

            if (decoded.source != null && decoded.schemaVersion == 0 && decoded.issues.isEmpty()) {
                val migrated = SettingsCodec.patchSettings(decoded.source, decoded.value, decoded.value)
                // A one-time schema upgrade with nobody waiting on it, so the application keeps
                // it off the startup thread.
                runMigration {
                    synchronized(lock) {
                        if (persist(KEY_SETTINGS, migrated, preserveSource = false)) {
                            settingsSource = SettingsCodec.decodeSettings(migrated).source
                        }
                    }
                }
            }
        } else readable = false
    }

    fun getDevices(): List<SavedDevice> = devices.value
    fun getSettings(): MirrorSettings = settings.value

    /**
     * Whether every saved device was read. False when storage could not be read or an entry could
     * not be loaded, so something tidying up after removed devices knows the list is incomplete.
     */
    val devicesFullyLoaded: Boolean get() = synchronized(lock) { readable && !devicesNeedRecovery }

    fun configurationSnapshot(): ConfigurationSnapshot = synchronized(lock) {
        ConfigurationSnapshot(_devices.value.toList(), _settings.value)
    }

    /** Capture repair eligibility with the decoded values the user will preview. */
    internal fun importPreviewBaseline(): Pair<ConfigurationSnapshot, Boolean> = synchronized(lock) {
        configurationSnapshot() to (readable && (settingsDamaged || devicesNeedRecovery))
    }

    /** Do not claim a complete backup of damaged or only partly understood data. */
    fun configurationForExport(): ConfigurationSnapshot = synchronized(lock) {
        if (!readable || settingsDamaged || devicesNeedRecovery) {
            throw IOException("Saved data needs recovery. Resolve the reported storage issues before exporting.")
        }
        if (settingsSource?.let(SettingsCodec::unreadableSettingsFields)?.isNotEmpty() == true) {
            throw IOException("Saved settings contain fields this version cannot export. The original data has not been changed.")
        }
        configurationSnapshot()
    }

    /** All chosen changes and pre-import values are committed together, or none are published. */
    fun importConfiguration(
        incoming: ConfigurationSnapshot,
        expectedCurrent: ConfigurationSnapshot,
        replaceExisting: Boolean,
        importDefaults: Boolean,
    ): Boolean = synchronized(lock) {
        val current = configurationSnapshot()
        if (current != expectedCurrent) {
            report(PersistenceIssue("import", "Settings changed after preview. Review the updated preview before importing."))
            return@synchronized false
        }
        val plan = try {
            planImport(current, incoming, replaceExisting, importDefaults)
        } catch (e: IOException) {
            report(PersistenceIssue("import", e.message ?: "Invalid backup"))
            return@synchronized false
        }
        // Damaged storage is still worth rewriting when the values themselves already match: the
        // recovered value stands in for a source that cannot be parsed, so returning here would
        // report success and leave it exactly as unreadable as before.
        if (plan.after == current && !settingsDamaged && !devicesNeedRecovery) return@synchronized true
        if (!readable) {
            report(PersistenceIssue("import", "Cannot import while saved storage is unreadable"))
            return@synchronized false
        }
        val previous = readStorage() ?: return@synchronized false
        val changes = BackupHistoryStorage.migration(previous).toMutableMap()
        fun replace(key: String, encoded: String) {
            changes[key] = encoded
        }
        // Rewritten when the stored text needs repair even if the decoded value is unchanged,
        // because what is being replaced is the unreadable source, not the value it stood in for.
        if (plan.after.devices != current.devices || devicesNeedRecovery) {
            replace(KEY_DEVICES, SettingsCodec.encodeDevices(plan.after.devices))
        }
        if (plan.after.settings != current.settings || settingsDamaged) {
            replace(KEY_SETTINGS, SettingsCodec.encodeSettings(plan.after.settings))
        }
        // A complete snapshot must include every setting, even when this import leaves defaults
        // unchanged. Unreadable fields stay in the original source, or in recovery if replaced.
        val unreadableSettings = settingsSource?.let(SettingsCodec::unreadableSettingsFields).orEmpty()
        var completeBackup = !settingsDamaged && !devicesNeedRecovery &&
            unreadableSettings.isEmpty()
        if (completeBackup) {
            try {
                changes.putAll(BackupHistoryStorage.snapshotChanges(previous, current, System.currentTimeMillis()))
            } catch (error: IOException) {
                // An oversized/nonportable current configuration still needs lossless local recovery.
                completeBackup = false
            }
        }
        val recoveryKeys = if (completeBackup) emptyList() else {
            listOf(KEY_DEVICES, KEY_SETTINGS).filter { it in changes && it in previous }.map {
                retainRecovery(previous, changes, it)
            }
        }
        if (!commit(changes, "import")) return@synchronized false
        if (KEY_SETTINGS in changes) {
            settingsSource = SettingsCodec.decodeSettings(changes.getValue(KEY_SETTINGS) as String).source
            settingsNeedRecovery = false
            settingsDamaged = false
        }
        if (KEY_DEVICES in changes) devicesNeedRecovery = false
        _settings.value = plan.after.settings
        _devices.value = plan.after.devices.toList()
        publishHistory(BackupHistoryStorage.applied(previous, changes))
        if (!completeBackup) {
            report(PersistenceIssue("import", "The previous data was not a complete portable snapshot. Original values are retained for recovery."))
            recoveryKeys.forEach { report(PersistenceIssue("import", "Original data retained for recovery", recoveryKey = it)) }
        }
        true
    }

    /** Run through [mutate] from UI. Migration indexes old partial records without combining them. */
    fun refreshBackupHistory(): Boolean = synchronized(lock) {
        val previous = readStorage() ?: return@synchronized false
        val changes = BackupHistoryStorage.migration(previous).toMutableMap()
        BackupHistoryStorage.read(previous).filter { it.entry.isComplete }
            .drop(BackupHistoryStorage.LIMIT).forEach { changes[it.entry.id] = null }
        if (changes.isNotEmpty() && !commit(changes, "history")) {
            publishHistory(previous)
            return@synchronized false
        }
        publishHistory(BackupHistoryStorage.applied(previous, changes))
        true
    }

    /** Only complete validated snapshots can enter the portable preview/export pipeline. */
    fun backupSnapshot(id: String): ConfigurationSnapshot = synchronized(lock) {
        val previous = readStorage() ?: throw IOException("Backup history could not be read.")
        val record = BackupHistoryStorage.read(previous).firstOrNull { it.entry.id == id }
            ?: throw IOException("This backup no longer exists. Refresh the history list.")
        record.snapshot?.frozenCopy()
            ?: throw IOException("This is partial or damaged recovery data, not a complete restorable snapshot.")
    }

    /** Deletes precisely the confirmed record and, for a partial record, its original source. */
    fun deleteBackupHistory(id: String): Boolean = synchronized(lock) {
        val previous = readStorage() ?: return@synchronized false
        val record = BackupHistoryStorage.read(previous).firstOrNull { it.entry.id == id }
        if (record == null) {
            report(PersistenceIssue("history", "This backup no longer exists. Refresh the history list."))
            return@synchronized false
        }
        val changes = record.keys.associateWith { null }
        if (!commit(changes, "history")) return@synchronized false
        _errors.update { current -> current.filterNot { it.recoveryKey in record.keys || it.key == id } }
        publishHistory(BackupHistoryStorage.applied(previous, changes))
        true
    }

    fun saveDevice(device: SavedDevice): Boolean = synchronized(lock) {
        val problem = SettingsValidation.deviceError(device)
        if (problem != null) {
            report(PersistenceIssue(KEY_DEVICES, problem))
            return@synchronized false
        }
        val updated = _devices.value.toMutableList()
        val index = updated.indexOfFirst { it.id == device.id }
        // The editor owns identity, address and look only. Live calibration and favorite changes
        // may have happened since its draft opened and must not be overwritten by that draft.
        if (index >= 0) {
            updated[index] = updated[index].copy(name = device.name, host = device.host, port = device.port,
                avatarColor = device.avatarColor, avatarIcon = device.avatarIcon)
        } else updated += device
        if (updated == _devices.value) return@synchronized true
        saveDevices(updated)
    }

    fun deleteDevice(id: String): Boolean = synchronized(lock) {
        if (_devices.value.none { it.id == id }) return@synchronized true
        saveDevices(_devices.value.filterNot { it.id == id })
    }

    /**
     * Puts back a device just removed, as it was and where it was: [index] is its place in the
     * saved order, which is also what its automatic avatar colour follows. A place the list no
     * longer has puts it at the end, and a device that is already back is left alone, so an Undo
     * delivered twice restores it once.
     */
    fun restoreDevice(device: SavedDevice, index: Int): Boolean = synchronized(lock) {
        if (_devices.value.any { it.id == device.id }) return@synchronized true
        val problem = SettingsValidation.deviceError(device)
        if (problem != null) {
            report(PersistenceIssue(KEY_DEVICES, problem))
            return@synchronized false
        }
        val updated = _devices.value.toMutableList()
        updated.add(index.coerceIn(0, updated.size), device)
        saveDevices(updated)
    }

    /**
     * Applies an editor's changes to the device it was opened on.
     *
     * Two device lists can be open at once (the share sheet starts one in the sharing app's task),
     * so the device may have been deleted or changed since [baseline] was read. A deleted device is
     * reported rather than recreated from the stale draft, and only the fields this editor actually
     * changed are written, so another editor's change to a different field survives.
     */
    fun editDevice(baseline: SavedDevice, edited: SavedDevice): Boolean = synchronized(lock) {
        if (_devices.value.none { it.id == baseline.id }) {
            report(PersistenceIssue(KEY_DEVICES, "The saved device no longer exists"))
            return@synchronized false
        }
        updateDevice(baseline.id) { current ->
            current.copy(
                name = if (edited.name != baseline.name) edited.name else current.name,
                host = if (edited.host != baseline.host) edited.host else current.host,
                port = if (edited.port != baseline.port) edited.port else current.port,
                avatarColor = if (edited.avatarColor != baseline.avatarColor) edited.avatarColor else current.avatarColor,
                avatarIcon = if (edited.avatarIcon != baseline.avatarIcon) edited.avatarIcon else current.avatarIcon,
            )
        }
    }

    fun setFavorite(id: String, favorite: Boolean): Boolean =
        updateDevice(id) { it.copy(isFavorite = favorite) }

    /** Null removes the orientation's override and restores inherited global calibration. */
    fun setCalibration(id: String, landscape: Boolean, calibration: TouchCalibration?): Boolean =
        updateDevice(id) {
            if (landscape) it.copy(landscapeCalibration = calibration)
            else it.copy(portraitCalibration = calibration)
        }

    /** Capture preferences and the device profile atomically for a new connection attempt. */
    fun connectionSettings(deviceId: String?): MirrorSettings = synchronized(lock) {
        if (deviceId == null) return@synchronized _settings.value
        val device = _devices.value.firstOrNull { it.id == deviceId }
            ?: throw IllegalArgumentException("The saved device no longer exists. Return to the device list.")
        device.effectiveSettings(_settings.value)
    }

    fun saveDeviceSettings(
        id: String,
        baseline: DeviceMirroringSettings?,
        edited: DeviceMirroringSettings?,
    ): Boolean = synchronized(lock) {
        val device = _devices.value.firstOrNull { it.id == id }
        if (device == null) {
            report(PersistenceIssue(KEY_DEVICES, "The saved device no longer exists"))
            return@synchronized false
        }
        if (edited != null && SettingsValidation.settingsErrors(edited.applyTo(MirrorSettings())).isNotEmpty()) {
            report(PersistenceIssue(KEY_DEVICES, "Device settings are outside the supported ranges", "mirroringSettings"))
            return@synchronized false
        }
        if (baseline == edited) return@synchronized true
        val current = device.mirroringSettings
        // A reset/enable changes inheritance policy. Do not overwrite another editor's mode change.
        if ((current == null) != (baseline == null) ||
            (edited == null && current != baseline)
        ) {
            report(PersistenceIssue(KEY_DEVICES, "Device settings changed elsewhere. Reopen the editor before saving.", "mirroringSettings"))
            return@synchronized false
        }
        val merged = if (current != null && baseline != null && edited != null) {
            current.mergeChanges(baseline, edited)
        } else edited
        updateDevice(id) { it.copy(mirroringSettings = merged) }
    }

    private fun updateDevice(id: String, transform: (SavedDevice) -> SavedDevice): Boolean = synchronized(lock) {
        val devices = _devices.value.toMutableList()
        val index = devices.indexOfFirst { it.id == id }
        if (index < 0) {
            report(PersistenceIssue(KEY_DEVICES, "The saved device no longer exists"))
            return@synchronized false
        }
        val updated = transform(devices[index])
        val invalid = SettingsValidation.deviceError(updated)
        if (invalid != null) {
            report(PersistenceIssue(KEY_DEVICES, invalid))
            return@synchronized false
        }
        if (devices[index] == updated) return@synchronized true
        devices[index] = updated
        saveDevices(devices)
    }

    private fun saveDevices(updated: List<SavedDevice>): Boolean {
        if (!persist(KEY_DEVICES, SettingsCodec.encodeDevices(updated), devicesNeedRecovery)) return false
        _devices.value = updated.toList()
        devicesNeedRecovery = false
        return true
    }

    /** Explicit full-field replacement. Prefer updateSettings for a live control. */
    fun saveSettings(settings: MirrorSettings): Boolean = synchronized(lock) {
        saveSettingsLocked(settings, baseline = null)
    }

    fun updateSettings(transform: (MirrorSettings) -> MirrorSettings): Boolean = synchronized(lock) {
        val current = _settings.value
        saveSettingsLocked(transform(current), baseline = current)
    }

    /**
     * Only fields edited since the dialog opened win over intervening live updates. Fields that
     * form one choice (a codec and its encoder, the capture source) move together, for the reason
     * given at [DeviceMirroringSettings.mergeChanges].
     */
    fun mergeSettings(baseline: MirrorSettings, edited: MirrorSettings): Boolean = updateSettings { current ->
        val before = mergeJson.encodeToJsonElement(baseline) as JsonObject
        val after = mergeJson.encodeToJsonElement(edited) as JsonObject
        val latest = mergeJson.encodeToJsonElement(current) as JsonObject
        val changed = after.keys.filter { before[it] != after[it] }.toMutableSet()
        MERGE_GROUPS.filter { group -> group.any { it in changed } }.forEach { changed += it }
        mergeJson.decodeFromJsonElement<MirrorSettings>(JsonObject(latest + after.filterKeys { it in changed }))
    }

    private fun saveSettingsLocked(updated: MirrorSettings, baseline: MirrorSettings?): Boolean {
        val invalid = SettingsValidation.settingsErrors(updated)
        if (invalid.isNotEmpty()) {
            invalid.forEach { (field, problem) -> report(PersistenceIssue(KEY_SETTINGS, problem, field)) }
            return false
        }
        // A save deliberately does not rewrite fields it did not change, even when the stored copy
        // has values this build cannot read. Those may belong to a newer schema, and discarding
        // them would silently destroy a future version's settings. Export refusing to run while
        // anything is unreadable is the same decision seen from the other side: the app will not
        // claim a complete backup of data it only partly understands.
        if (baseline != null && updated == baseline) return true
        val previousIssues = settingsSource?.let { SettingsCodec.decodeSettings(it.toString()).issues }.orEmpty()
        val raw = SettingsCodec.patchSettings(settingsSource, baseline, updated)
        if (!persist(KEY_SETTINGS, raw, settingsNeedRecovery)) return false
        val decoded = SettingsCodec.decodeSettings(raw)
        settingsSource = decoded.source
        // Preserve one original per load, not another full backup on every calibration tick.
        settingsNeedRecovery = false
        settingsDamaged = decoded.issues.isNotEmpty()
        _settings.value = decoded.value
        // Only what this save newly broke: re-reporting an unchanged problem on every save brought
        // back issues the user had already dismissed.
        decoded.issues.filter { it !in previousIssues }.forEach(::report)
        return true
    }

    private fun persist(key: String, value: String, preserveSource: Boolean): Boolean {
        if (!readable) {
            report(PersistenceIssue(key, "Storage could not be read; refusing to overwrite it"))
            return false
        }
        val previous = readStorage() ?: return false
        // Nothing to keep a copy of when the value is not actually changing. Without this, data
        // flagged as needing recovery had its original copied aside again on every write that
        // touched it, so the same bytes accumulated under recovery.1, recovery.2, recovery.3 and
        // on — each with a history record of its own — until the preference file was mostly
        // duplicates of one value.
        if (preserveSource && previous[key] == value) return true
        val changes = mutableMapOf<String, Any?>(key to value)
        val recoveryKey = if (preserveSource && key in previous) retainRecovery(previous, changes, key) else null
        if (!commit(changes, key)) return false
        if (recoveryKey != null) {
            publishHistory(BackupHistoryStorage.applied(previous, changes))
            report(PersistenceIssue(key, "Original data retained for recovery", recoveryKey = recoveryKey))
        }
        return true
    }

    private fun retainRecovery(previous: Map<String, *>, changes: MutableMap<String, Any?>, key: String): String {
        // An identical copy already set aside is the copy this one would be. Making a second is
        // not a second safeguard, only a second thing for the user to be told about and to have
        // to delete.
        val existing = previous.entries.firstOrNull {
            it.key.startsWith("$key.recovery.") && it.value == previous[key]
        }
        if (existing != null) return existing.key
        var index = 1
        while ("$key.recovery.$index" in previous || "$key.recovery.$index" in changes) index++
        val recoveryKey = "$key.recovery.$index"
        changes[recoveryKey] = previous[key]
        val (recordKey, record) = BackupHistoryStorage.recoveryReference(previous + changes, recoveryKey, System.currentTimeMillis())
        changes[recordKey] = record
        return recoveryKey
    }

    private fun publishHistory(preferences: Map<String, *>) {
        val entries = BackupHistoryStorage.read(preferences).map { it.entry }
        _backupHistory.value = entries
        entries.filter { it.kind == BackupHistoryKind.UNREADABLE }.forEach {
            report(PersistenceIssue(it.id, it.details))
        }
    }

    private fun commit(changes: Map<String, Any?>, key: String): Boolean {
        val saved = try {
            storage.write(changes)
        } catch (_: IOException) {
            report(PersistenceIssue(key, "Storage write failed; changes were not committed"))
            return false
        } catch (_: SecurityException) {
            report(PersistenceIssue(key, "Storage write denied; changes were not committed"))
            return false
        } catch (_: IllegalArgumentException) {
            report(PersistenceIssue(key, "Storage contains an unsupported value; changes were not committed"))
            return false
        }
        if (!saved) {
            report(PersistenceIssue(key, "Storage write failed; changes were not committed"))
            return false
        }
        return true
    }

    private fun readStorage(): Map<String, *>? = try {
        storage.read()
    } catch (_: IOException) {
        report(PersistenceIssue("storage", "Storage read failed; persisted data was not changed"))
        null
    } catch (_: SecurityException) {
        report(PersistenceIssue("storage", "Storage read denied; persisted data was not changed"))
        null
    }

    private fun report(issue: PersistenceIssue) {
        var added = false
        _errors.update { current ->
            added = issue !in current
            if (added) current + issue else current
        }
        if (added) reportError(issue)
    }

    /**
     * Takes issues off the list once the user has been shown them.
     *
     * The list lives as long as the process, and kept there an old problem was shown again by
     * every screen opened later: the mirror screen led every session with it. One that happens
     * again after this is reported, and shown, again. Lock-free, as a screen calls this on its
     * main thread while a save may hold the storage lock.
     */
    fun acknowledge(shown: Collection<PersistenceIssue>) {
        _errors.update { current -> current.filterNot { it in shown } }
    }

    private fun decodeSettingsPreference(value: Any?): DecodedSettings =
        if (value is String) SettingsCodec.decodeSettings(value)
        else DecodedSettings(
            MirrorSettings(),
            listOf(PersistenceIssue(KEY_SETTINGS, "Expected a JSON string; source retained")),
        )

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_SETTINGS = "settings"
        private val mergeJson = Json { encodeDefaults = true }

        /** Settings fields that together make one choice; see [mergeSettings]. */
        private val MERGE_GROUPS = listOf(
            setOf("videoCodec", "videoEncoder"),
            setOf("audioCodec", "audioEncoder"),
            setOf("displayId", "virtualDisplay", "camera"),
        )

        @Volatile
        private var instance: DeviceRepository? = null

        fun get(context: Context): DeviceRepository = instance ?: synchronized(this) {
            instance ?: DeviceRepository(
                SharedPreferencesStorage(
                    context.applicationContext.getSharedPreferences("scrcpy_remote", Context.MODE_PRIVATE),
                ),
                reportError = { Log.e("DeviceRepository", "${it.key}${it.field?.let { field -> ".$field" } ?: ""}: ${it.message}") },
                runMigration = PreferenceWriter::enqueue,
            ).also { instance = it }
        }
    }
}
