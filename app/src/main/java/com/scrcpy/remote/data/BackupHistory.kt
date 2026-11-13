package com.scrcpy.remote.data

import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class BackupHistoryKind { SNAPSHOT, LEGACY_PARTIAL, RECOVERY, UNREADABLE }

data class BackupHistoryEntry(
    val id: String,
    val name: String,
    val createdAtMillis: Long?,
    val deviceCount: Int?,
    val deviceNames: List<String>,
    val details: String,
    val kind: BackupHistoryKind,
) {
    val isComplete: Boolean get() = kind == BackupHistoryKind.SNAPSHOT
}

internal data class StoredHistory(
    val entry: BackupHistoryEntry,
    val snapshot: ConfigurationSnapshot? = null,
    val sourceKey: String? = null,
) {
    val keys: Set<String> get() = setOfNotNull(entry.id, sourceKey)
}

/** Portable snapshots never contain preference dumps or pairing identities. Raw recovery stays local. */
internal object BackupHistoryStorage {
    const val LIMIT = 5
    private const val PREFIX = "profileHistory."
    private val recordKey = Regex("profileHistory\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val sourceKey = Regex("(devices|settings)\\.(importBackup|recovery)\\.[1-9][0-9]*")
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Record(
        val version: Int = 1,
        val name: String,
        val createdAtMillis: Long? = null,
        val backup: String? = null,
        val sourceKey: String? = null,
    )

    fun read(preferences: Map<String, *>): List<StoredHistory> {
        val records = preferences.keys.filter(recordKey::matches).map { key ->
            decode(key, preferences[key], preferences)
        }
        val indexed = records.mapNotNull { it.sourceKey }.toSet()
        val legacy = preferences.keys.filter { sourceKey.matches(it) && it !in indexed }.map {
            partial(it, it, preferences[it])
        }
        return (records + legacy).sortedWith(
            compareByDescending<StoredHistory> { it.entry.createdAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.entry.id },
        )
    }

    /**
     * Old counters were independent for each preference. Equal suffixes do NOT identify an import.
     * Index each original separately, retaining its exact type/value until explicit deletion.
     */
    fun migration(preferences: Map<String, *>): Map<String, Any?> {
        val changes = mutableMapOf<String, Any?>()
        val indexed = read(preferences).mapNotNull { it.sourceKey }.toSet()
        preferences.keys.filter { sourceKey.matches(it) && it !in indexed }.forEach { source ->
            val preferred = PREFIX + UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8))
            val key = if (preferred !in preferences && preferred !in changes) preferred
            else uniqueKey(preferences + changes)
            changes[key] = json.encodeToString(Record(name = partialName(source), sourceKey = source))
        }
        return changes
    }

    fun snapshotChanges(
        preferences: Map<String, *>,
        snapshot: ConfigurationSnapshot,
        now: Long,
    ): Map<String, Any?> {
        val backup = ProfileBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        val changes = migration(preferences).toMutableMap()
        val key = uniqueKey(preferences + changes)
        val complete = read(preferences).filter { it.entry.isComplete }
        val last = complete.firstOrNull()?.entry?.createdAtMillis ?: -1
        val timestamp = maxOf(now, if (last == Long.MAX_VALUE) last else last + 1)
        changes[key] = json.encodeToString(Record(name = "Before import", createdAtMillis = timestamp, backup = backup))
        // Only successfully validated complete snapshots are eligible for automatic removal.
        complete.drop(LIMIT - 1).forEach {
            changes[it.entry.id] = null
        }
        return changes
    }

    fun recoveryReference(
        preferences: Map<String, *>,
        source: String,
        now: Long,
    ): Pair<String, String> {
        require(sourceKey.matches(source))
        return uniqueKey(preferences) to json.encodeToString(
            Record(name = partialName(source), createdAtMillis = now, sourceKey = source),
        )
    }

    fun applied(preferences: Map<String, *>, changes: Map<String, Any?>): Map<String, Any?> =
        preferences.toMutableMap().apply {
            changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
        }

    private fun decode(key: String, value: Any?, preferences: Map<String, *>): StoredHistory {
        return try {
            if (value !is String || value.length > ProfileBackupCodec.MAX_BYTES * 2 + 4096) {
                throw IOException("Invalid stored history value")
            }
            val record = json.decodeFromString<Record>(value)
            if (record.version != 1 || record.name.isBlank() || record.name.length > 160 ||
                record.createdAtMillis?.let { it < 0 } == true ||
                (record.backup == null) == (record.sourceKey == null)
            ) throw IOException("Unsupported or incomplete history record")
            val source = record.sourceKey
            if (source != null) {
                if (!sourceKey.matches(source) || source !in preferences) {
                    throw IOException("The original partial backup is missing or unrecognized")
                }
                partial(key, source, preferences[source], record.name, record.createdAtMillis)
            } else {
                val snapshot = ProfileBackupCodec.decode(checkNotNull(record.backup).toByteArray(Charsets.UTF_8))
                StoredHistory(
                    BackupHistoryEntry(key, record.name, record.createdAtMillis, snapshot.devices.size,
                        snapshot.devices.map { it.name },
                        "Complete saved profiles and global defaults. Pairing keys and credentials are not included.",
                        BackupHistoryKind.SNAPSHOT),
                    snapshot = snapshot,
                )
            }
        } catch (error: SerializationException) {
            unreadable(key, "History metadata is damaged or uses an unsupported format.")
        } catch (error: IOException) {
            unreadable(key, error.message ?: "History data could not be validated.")
        } catch (error: IllegalArgumentException) {
            unreadable(key, "History contains invalid configuration data.")
        }
    }

    private fun partial(
        id: String,
        source: String,
        raw: Any?,
        name: String = partialName(source),
        createdAtMillis: Long? = null,
    ): StoredHistory {
        val isDevices = source.startsWith("devices.")
        val devices = if (isDevices && raw is String) SettingsCodec.decodeDevices(raw) else null
        val settings = if (!isDevices && raw is String) SettingsCodec.decodeSettings(raw) else null
        val problems = devices?.issues ?: settings?.issues
        val condition = when {
            raw !is String -> "The original value has an unexpected type."
            problems?.isNotEmpty() == true -> "The original value has ${problems.size} validation issue(s)."
            else -> "The stored ${if (isDevices) "profiles" else "defaults"} can be read."
        }
        return StoredHistory(
            BackupHistoryEntry(id, name, createdAtMillis, devices?.value?.size,
                devices?.value?.map { it.name }.orEmpty(),
                "$condition Source: $source. This is a partial record, not a complete snapshot. " +
                    "It is retained for recovery and cannot be restored or exported as a complete backup. " +
                    "Only explicit deletion removes the original.",
                if (".recovery." in source) BackupHistoryKind.RECOVERY else BackupHistoryKind.LEGACY_PARTIAL),
            sourceKey = source.takeIf { id != source },
        )
    }

    private fun unreadable(key: String, reason: String) = StoredHistory(
        BackupHistoryEntry(key, "Unrecognized history record", null, null, emptyList(),
            "$reason Original data is retained; only explicit deletion removes it.", BackupHistoryKind.UNREADABLE),
    )

    private fun partialName(source: String): String =
        "${if (source.startsWith("devices.")) "Profiles" else "Defaults"} " +
            if (".recovery." in source) "recovery record" else "legacy backup (partial)"

    private fun uniqueKey(preferences: Map<String, *>): String {
        var key: String
        do { key = PREFIX + UUID.randomUUID() } while (key in preferences)
        return key
    }
}
