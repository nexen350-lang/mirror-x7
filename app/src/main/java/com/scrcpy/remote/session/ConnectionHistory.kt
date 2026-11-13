package com.scrcpy.remote.session

import android.content.Context
import android.util.Log
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * One problem a saved device had connecting, or staying connected: the step that failed, and how.
 *
 * Attempts that fail the same way with no successful connection between them are one problem with
 * a [count]: automatic recovery alone can fail four times in a row, which would otherwise push
 * every earlier problem out of the history.
 */
data class ConnectionProblem(
    val stage: CheckupStage,
    val kind: FailureKind,
    /** The address last tried, as the checkup named it: the port can change between attempts. */
    val endpoint: String,
    val firstAtMillis: Long,
    val lastAtMillis: Long,
    val count: Int = 1,
)

/**
 * What this phone remembers about connecting to one saved device: when it last connected, and the
 * last [MAX_PROBLEMS] problems. Kept on this phone only; see [ConnectionHistoryStore].
 */
data class DeviceConnectionHistory(
    val lastConnectedAtMillis: Long? = null,
    /** Oldest first. */
    val problems: List<ConnectionProblem> = emptyList(),
    /** A connection succeeded after the newest problem, so a later failure is a new problem. */
    val connectedSinceLastProblem: Boolean = false,
) {
    val isEmpty: Boolean get() = lastConnectedAtMillis == null && problems.isEmpty()

    /** Adds a failed attempt. A cancelled one is not a problem: the user stopped it. */
    fun withFailure(stage: CheckupStage, kind: FailureKind, endpoint: String, atMillis: Long): DeviceConnectionHistory {
        if (kind == FailureKind.CANCELLED) return this
        val newest = problems.lastOrNull()
        val updated = if (newest != null && !connectedSinceLastProblem && newest.stage == stage && newest.kind == kind) {
            problems.dropLast(1) + newest.copy(
                endpoint = endpoint,
                // A clock set back must not leave a problem that ends before it began.
                lastAtMillis = maxOf(newest.lastAtMillis, atMillis),
                count = (newest.count + 1).coerceAtMost(MAX_COUNT),
            )
        } else {
            (problems + ConnectionProblem(stage, kind, endpoint, atMillis, atMillis)).takeLast(MAX_PROBLEMS)
        }
        return copy(problems = updated, connectedSinceLastProblem = false)
    }

    fun withConnection(atMillis: Long): DeviceConnectionHistory =
        copy(lastConnectedAtMillis = atMillis, connectedSinceLastProblem = true)

    companion object {
        const val MAX_PROBLEMS = 5
        const val MAX_COUNT = 9_999
    }
}

/** What the failure screen says about the attempts before the one that just failed. */
data class FailureHistory(
    /** The problem the failure on screen belongs to; its count is how many times in a row it happened. */
    val current: ConnectionProblem,
    /** Earlier problems, newest first. */
    val earlier: List<ConnectionProblem>,
    val lastConnectedAtMillis: Long?,
) {
    /** Whether there is anything to say beyond the failure on screen. */
    val worthShowing: Boolean get() = current.count > 1 || earlier.isNotEmpty() || lastConnectedAtMillis != null

    companion object {
        /** The newest problem is the one just recorded, so it is the failure on screen. */
        fun of(history: DeviceConnectionHistory): FailureHistory? {
            val current = history.problems.lastOrNull() ?: return null
            return FailureHistory(current, history.problems.dropLast(1).asReversed(), history.lastConnectedAtMillis)
        }
    }
}

/** The history file's format, read tolerantly: an entry this version cannot read is left out. */
object ConnectionHistoryCodec {
    private const val VERSION = 1

    fun encode(histories: Map<String, DeviceConnectionHistory>): String = buildJsonObject {
        put("version", VERSION)
        put("devices", buildJsonObject {
            for ((id, history) in histories) {
                put(id, buildJsonObject {
                    history.lastConnectedAtMillis?.let { put("lastConnectedAt", it) }
                    put("connectedSinceLastProblem", history.connectedSinceLastProblem)
                    put("problems", buildJsonArray {
                        for (problem in history.problems) {
                            add(buildJsonObject {
                                put("stage", problem.stage.name)
                                put("kind", problem.kind.name)
                                put("endpoint", problem.endpoint)
                                put("firstAt", problem.firstAtMillis)
                                put("lastAt", problem.lastAtMillis)
                                put("count", problem.count)
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    /** Never throws: a file that is not a history reads as none, and an unreadable entry is left out. */
    fun decode(text: String): Map<String, DeviceConnectionHistory> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptyMap()
        val devices = root["devices"] as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, DeviceConnectionHistory>()
        for ((id, value) in devices) {
            val entry = value as? JsonObject ?: continue
            val problems = (entry["problems"] as? JsonArray).orEmpty().mapNotNull(::problem)
                .takeLast(DeviceConnectionHistory.MAX_PROBLEMS)
            val history = DeviceConnectionHistory(
                lastConnectedAtMillis = number(entry["lastConnectedAt"]),
                problems = problems,
                connectedSinceLastProblem = (entry["connectedSinceLastProblem"] as? JsonPrimitive)
                    ?.takeUnless { it.isString }?.booleanOrNull ?: false,
            )
            if (!history.isEmpty) result[id] = history
        }
        return result
    }

    private fun problem(element: JsonElement): ConnectionProblem? {
        val entry = element as? JsonObject ?: return null
        val stage = text(entry["stage"])?.let { name -> CheckupStage.entries.firstOrNull { it.name == name } } ?: return null
        val kind = text(entry["kind"])?.let { name -> FailureKind.entries.firstOrNull { it.name == name } }
            ?.takeUnless { it == FailureKind.CANCELLED } ?: return null
        val endpoint = text(entry["endpoint"])?.takeIf { it.isNotBlank() } ?: return null
        val first = number(entry["firstAt"]) ?: return null
        val last = number(entry["lastAt"]) ?: return null
        val count = number(entry["count"])?.takeIf { it in 1..DeviceConnectionHistory.MAX_COUNT.toLong() } ?: return null
        if (first > last) return null
        return ConnectionProblem(stage, kind, endpoint, first, last, count.toInt())
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(element: JsonElement?): Long? =
        (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}

/**
 * Connection history for saved devices, in one small file.
 *
 * The file lives in no-backup storage: it is about this phone's own attempts, so it stays on this
 * phone and is not in profile backups, Android backups or device transfers. Only saved devices
 * have a history; a link to an address that is not saved records nothing.
 */
class ConnectionHistoryStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val writer: (Runnable) -> Unit = defaultWriter(),
    private val onError: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private val _history = MutableStateFlow(load())
    val history: StateFlow<Map<String, DeviceConnectionHistory>> = _history.asStateFlow()

    /** Records a failed attempt and returns the device's history with it. */
    fun recordFailure(deviceId: String, stage: CheckupStage, kind: FailureKind, endpoint: String): DeviceConnectionHistory {
        val at = now()
        return update { all ->
            val updated = (all[deviceId] ?: DeviceConnectionHistory()).withFailure(stage, kind, endpoint, at)
            if (updated.isEmpty) all else all + (deviceId to updated)
        }[deviceId] ?: DeviceConnectionHistory()
    }

    fun recordConnected(deviceId: String) {
        val at = now()
        update { all -> all + (deviceId to (all[deviceId] ?: DeviceConnectionHistory()).withConnection(at)) }
    }

    /** Forgets [deviceIds]: cleared by the user, or removed for good. */
    fun forget(deviceIds: Collection<String>) {
        val gone = deviceIds.toSet()
        update { all -> all.filterKeys { it !in gone } }
    }

    /** Keeps only the history of [deviceIds]. */
    fun retainOnly(deviceIds: Set<String>) {
        update { all -> all.filterKeys { it in deviceIds } }
    }

    /**
     * Forgets devices that are no longer saved, such as one removed while the app was ended before
     * its Undo had gone. Only when every saved device was read: a list that could not be loaded
     * would otherwise erase every history.
     */
    fun retainSavedDevices(repository: DeviceRepository) {
        if (!repository.devicesFullyLoaded) return
        retainOnly(repository.getDevices().mapTo(HashSet()) { it.id })
    }

    /** Applies [change] and saves the result in the background, in the order changes were made. */
    private fun update(
        change: (Map<String, DeviceConnectionHistory>) -> Map<String, DeviceConnectionHistory>,
    ): Map<String, DeviceConnectionHistory> = synchronized(lock) {
        val before = _history.value
        val after = change(before)
        if (after != before) {
            _history.value = after
            val text = ConnectionHistoryCodec.encode(after)
            writer(Runnable { save(text) })
        }
        after
    }

    private fun load(): Map<String, DeviceConnectionHistory> = try {
        if (file.isFile) ConnectionHistoryCodec.decode(file.readText()) else emptyMap()
    } catch (error: IOException) {
        onError("Connection history could not be read; starting a new one", error)
        emptyMap()
    } catch (error: SecurityException) {
        onError("Connection history could not be read; starting a new one", error)
        emptyMap()
    }

    /** Written beside the file and moved over it, so a reader never sees half of one. */
    private fun save(text: String) {
        val staging = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            staging.writeText(text)
            try {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            staging.delete()
            onError("Connection history could not be saved", error)
        } catch (error: SecurityException) {
            staging.delete()
            onError("Connection history could not be saved", error)
        }
    }

    companion object {
        private const val FILE_NAME = "connection-history.json"

        private fun defaultWriter(): (Runnable) -> Unit {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "connection-history").apply { isDaemon = true } }
            return executor::execute
        }

        @Volatile
        private var instance: ConnectionHistoryStore? = null

        fun get(context: Context): ConnectionHistoryStore = instance ?: synchronized(this) {
            instance ?: ConnectionHistoryStore(
                File(context.applicationContext.noBackupFilesDir, FILE_NAME),
                onError = { message, error -> Log.w("ConnectionHistory", message, error) },
            ).also { store ->
                instance = store
                store.retainSavedDevices(DeviceRepository.get(context))
            }
        }
    }
}
