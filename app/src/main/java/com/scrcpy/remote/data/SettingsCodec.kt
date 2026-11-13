package com.scrcpy.remote.data

import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.EncoderName
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull

data class PersistenceIssue(
    val key: String,
    val message: String,
    val field: String? = null,
    val recoveryKey: String? = null,
)

data class DecodedValue<T>(val value: T, val issues: List<PersistenceIssue> = emptyList())

data class DecodedSettings(
    val value: MirrorSettings,
    val issues: List<PersistenceIssue> = emptyList(),
    val schemaVersion: Int = 0,
    val source: JsonObject? = null,
)

/** Recovery is per field, explicit to callers, and never writes over its source. */
object SettingsCodec {
    const val CURRENT_VERSION = 1
    const val VERSION_FIELD = "schemaVersion"

    val legacyKeys = mapOf(
        "max_size" to "maxSize",
        "bitrate_mbps" to "videoBitRateMbps",
        "max_fps" to "maxFps",
        "video_codec" to "videoCodec",
        "audio_enabled" to "audioEnabled",
        "audio_codec" to "audioCodec",
        "stay_awake" to "stayAwake",
        "show_touches" to "showTouches",
        "power_off_on_close" to "powerOffOnClose",
        "turn_screen_off" to "turnScreenOff",
        "touch_offset_x" to "touchOffsetXDp",
        "touch_offset_y" to "touchOffsetYDp",
        "capture_edge_gestures" to "captureEdgeGestures",
        "sync_mode" to "syncMode",
        "audio_buffer_ms" to "audioBufferMs",
        "sync_offset_ms" to "syncOffsetMs",
        "auto_reconnect" to "autoReconnect",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeDevices(devices: List<SavedDevice>): String {
        require(devices.all { SettingsValidation.deviceError(it) == null }) { "Invalid saved device" }
        require(devices.distinctBy { it.id }.size == devices.size) { "Duplicate device identifier" }
        return json.encodeToString(devices)
    }

    /** Whether a stored entry carries its own id, rather than relying on the generated default. */
    private fun hasIdentifier(element: JsonElement): Boolean {
        val id = (element as? JsonObject)?.get("id") as? JsonPrimitive ?: return false
        return id.isString && id.content.isNotBlank()
    }

    fun decodeDevices(raw: String?): DecodedValue<List<SavedDevice>> {
        if (raw == null) return DecodedValue(emptyList())
        val issues = mutableListOf<PersistenceIssue>()
        val array = parse(raw, "devices", issues) as? JsonArray
        if (array == null) {
            if (issues.isEmpty()) issues += PersistenceIssue("devices", "Expected a JSON array; source retained")
            return DecodedValue(emptyList(), issues)
        }
        val devices = mutableListOf<SavedDevice>()
        array.forEachIndexed { index, element ->
            // Optional profile metadata must never hide an otherwise recoverable saved endpoint.
            val entry = (element as? JsonObject)?.toMutableMap()
            if (entry != null) {
                val favorite = entry["isFavorite"]
                if (favorite != null &&
                    (favorite !is JsonPrimitive || favorite.isString || favorite.booleanOrNull == null)
                ) {
                    issues += PersistenceIssue("devices", "Invalid favorite value; source retained", "[$index].isFavorite")
                    entry.remove("isFavorite")
                }
                // A look this build cannot show, such as an icon a newer version added, falls back
                // to the automatic one rather than costing the user the whole device.
                val color = entry["avatarColor"]
                if (color != null && color != JsonNull &&
                    ((color as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?.takeIf { it in SettingsValidation.AVATAR_COLORS } == null)
                ) {
                    issues += PersistenceIssue("devices", "Invalid avatar color; using automatic, source retained", "[$index].avatarColor")
                    entry.remove("avatarColor")
                }
                val icon = entry["avatarIcon"]
                if (icon != null && icon != JsonNull &&
                    ((icon as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.let { name -> DeviceIcon.entries.firstOrNull { it.name == name } } == null)
                ) {
                    issues += PersistenceIssue("devices", "Invalid avatar icon; using initials, source retained", "[$index].avatarIcon")
                    entry.remove("avatarIcon")
                }
                // Reported at every level a newer build could have added a field to, not just the
                // profile. These are decoded with unknown keys ignored, so anything unrecognised
                // is dropped on re-encode and the next ordinary edit — a favourite, a calibration
                // nudge — writes that loss back for good. Reporting is what retains the original.
                reportUnknown(entry.keys, knownDeviceFields, "[$index]")?.let { issues += it }
                for (field in listOf("portraitCalibration", "landscapeCalibration")) {
                    val rawProfile = entry[field] ?: continue
                    if (rawProfile == JsonNull) continue
                    (rawProfile as? JsonObject)?.let { calibration ->
                        reportUnknown(calibration.keys, knownCalibrationFields, "[$index].$field")
                            ?.let { issues += it }
                    }
                    val profile = try {
                        json.decodeFromJsonElement<TouchCalibration>(rawProfile)
                    } catch (_: SerializationException) {
                        null
                    }
                    if (profile == null || profile.xDp !in SettingsValidation.TOUCH_OFFSET_RANGE ||
                        profile.yDp !in SettingsValidation.TOUCH_OFFSET_RANGE
                    ) {
                        issues += PersistenceIssue("devices", "Invalid calibration; using global defaults, source retained", "[$index].$field")
                        entry.remove(field)
                    }
                }
                val profile = entry["mirroringSettings"]
                if (profile != null && profile != JsonNull) {
                    // Decode each setting with the same validation as globals. Recovery is reported,
                    // and the untouched original device JSON is retained for a subsequent backup.
                    if (profile !is JsonObject) {
                        issues += PersistenceIssue("devices", "Invalid device settings; inheriting globals, source retained",
                            "[$index].mirroringSettings")
                        entry.remove("mirroringSettings")
                    } else {
                        val decoded = decodeSettings(profile.toString())
                        issues += decoded.issues.map {
                            PersistenceIssue("devices", it.message, "[$index].mirroringSettings.${it.field ?: "profile"}")
                        }
                        val reencoded = json.encodeToJsonElement(DeviceMirroringSettings.from(decoded.value)) as JsonObject
                        // Re-encoding keeps only the fields this build knows, so anything a newer
                        // version wrote is dropped here and the next unrelated edit writes that
                        // loss back for good. Global settings keep their unreadable fields on
                        // purpose; a device profile is reported instead, which is what retains the
                        // original JSON for recovery rather than quietly cutting it down to fit.
                        //
                        // Legacy names are deliberately *not* excused here, unlike in the global
                        // settings above. There they are migrated on read; a device profile's
                        // decoder only knows the current names, so excusing them meant such a
                        // field was neither read nor reported — the one combination that loses it
                        // without trace.
                        val unknown = profile.keys.filter {
                            it !in reencoded && it != VERSION_FIELD
                        } + unreadableCameraFields(profile)
                        if (unknown.isNotEmpty()) {
                            issues += PersistenceIssue("devices",
                                "Device settings contain fields this version cannot read " +
                                    "(${unknown.sorted().joinToString()}); source retained",
                                "[$index].mirroringSettings")
                        }
                        entry["mirroringSettings"] = reencoded
                    }
                }
            }
            val device = try {
                json.decodeFromJsonElement<SavedDevice>(entry?.let(::JsonObject) ?: element)
            } catch (_: SerializationException) {
                issues += PersistenceIssue("devices", "Invalid device entry; source retained", "[$index]")
                null
            }
            if (device != null && !hasIdentifier(element)) {
                // SavedDevice.id defaults to a fresh UUID, so an entry without one would be given
                // a different identity on every load: shortcuts pointing at it would go stale and
                // an exported profile would come back as a duplicate. The portable backup reader
                // requires an id for the same reason.
                issues += PersistenceIssue("devices", "Missing device identifier; entry not loaded, source retained", "[$index]")
            } else if (device != null) {
                val problem = SettingsValidation.deviceError(device)
                    ?: if (devices.any { it.id == device.id }) "Duplicate device identifier" else null
                if (problem == null) devices += device
                else issues += PersistenceIssue("devices", "$problem; entry not loaded, source retained", "[$index]")
            }
        }
        return DecodedValue(devices.toList(), issues)
    }

    /**
     * Field paths in a stored settings object that this build cannot write back.
     *
     * An ordinary save patches the retained source, so a field a newer version wrote survives
     * untouched. Anything that replaces the whole value instead — an import — drops it, so the
     * caller needs to know to keep the original first.
     */
    fun unreadableSettingsFields(source: JsonObject): List<String> {
        val known = (json.encodeToJsonElement(MirrorSettings()) as JsonObject).keys
        return (source.keys.filter { it !in known && it !in legacyKeys && it != VERSION_FIELD } +
            unreadableCameraFields(source)).sorted()
    }

    private fun unreadableCameraFields(source: JsonObject): List<String> =
        (source["camera"] as? JsonObject)?.keys.orEmpty()
            .filter { it !in knownCameraFields }
            .map { "camera.$it" }

    /** Field names a device entry and a calibration are written with by this build. */
    private val knownDeviceFields: Set<String> by lazy {
        (json.encodeToJsonElement(SavedDevice(name = "", host = "")) as JsonObject).keys
    }
    private val knownCalibrationFields: Set<String> by lazy {
        (json.encodeToJsonElement(TouchCalibration()) as JsonObject).keys
    }
    private val knownCameraFields: Set<String> by lazy {
        (json.encodeToJsonElement(CameraCapture()) as JsonObject).keys
    }

    /**
     * An issue naming the keys of [present] this build does not know, or null when it knows them all.
     *
     * Derived from what the class actually serializes rather than a hand-written list, so adding a
     * field cannot leave this behind out of date.
     */
    private fun reportUnknown(present: Set<String>, known: Set<String>, field: String): PersistenceIssue? {
        val unknown = present.filter { it !in known }.sorted()
        if (unknown.isEmpty()) return null
        return PersistenceIssue("devices",
            "Contains fields this version cannot read (${unknown.joinToString()}); source retained",
            field)
    }

    fun encodeSettings(settings: MirrorSettings): String = patchSettings(null, null, settings)

    /**
     * Encodes an editor draft, which is allowed to be half-typed.
     *
     * Deliberately separate from [encodeSettings], which refuses anything the repository would
     * reject. A draft is not a saved value: switching "start app" on stores an empty package
     * name until one is typed, and requiring validity here meant rotating at that moment threw
     * out of `onSaveInstanceState`. Validation still gates Save; this only has to survive a trip
     * through saved instance state.
     */
    fun encodeDraft(settings: MirrorSettings): String =
        (json.encodeToJsonElement(settings) as JsonObject).let { fields ->
            JsonObject(fields + (VERSION_FIELD to JsonPrimitive(CURRENT_VERSION))).toString()
        }

    /** Restores a draft encoded by [encodeDraft], or null if it is not one. */
    fun decodeDraft(raw: String): MirrorSettings? = runCatching {
        val source = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        json.decodeFromJsonElement<MirrorSettings>(JsonObject(source - VERSION_FIELD))
    }.getOrNull()

    /** A null baseline is an explicit replacement of every known field, not of unknown fields. */
    internal fun patchSettings(
        source: JsonObject?,
        baseline: MirrorSettings?,
        updated: MirrorSettings,
    ): String {
        require(SettingsValidation.settingsErrors(updated).isEmpty()) { "Invalid mirror settings" }
        val fields = json.encodeToJsonElement(updated) as JsonObject
        val before = baseline?.let { json.encodeToJsonElement(it) as JsonObject }
        val result = source?.toMutableMap() ?: mutableMapOf()
        fields.forEach { (key, value) ->
            if (source == null || before == null || before[key] != value) result[key] = value
        }
        if (VERSION_FIELD !in result) result[VERSION_FIELD] = JsonPrimitive(CURRENT_VERSION)
        return JsonObject(result).toString()
    }

    fun decodeSettings(raw: String?): DecodedSettings {
        if (raw == null) return DecodedSettings(MirrorSettings())
        val issues = mutableListOf<PersistenceIssue>()
        val source = parse(raw, "settings", issues) as? JsonObject
        if (source == null) {
            if (issues.isEmpty()) issues += PersistenceIssue("settings", "Expected a JSON object; source retained")
            return DecodedSettings(MirrorSettings(), issues)
        }
        return decodeFields(source, issues)
    }

    fun decodeLegacy(preferences: Map<String, *>): DecodedSettings {
        val fields = buildMap {
            legacyKeys.forEach { (old, current) ->
                if (preferences.containsKey(old)) {
                    put(current, when (val value = preferences[old]) {
                        is String -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> JsonNull
                    })
                }
            }
        }
        if (fields.isEmpty()) return DecodedSettings(MirrorSettings())
        return decodeFields(JsonObject(fields), mutableListOf())
    }

    private fun parse(raw: String, key: String, issues: MutableList<PersistenceIssue>): JsonElement? =
        try {
            json.parseToJsonElement(raw)
        } catch (_: SerializationException) {
            issues += PersistenceIssue(key, "Malformed JSON; using recoverable in-memory state, source retained")
            null
        }

    private fun decodeFields(
        source: JsonObject,
        issues: MutableList<PersistenceIssue>,
    ): DecodedSettings {
        val defaults = MirrorSettings()
        fun invalid(field: String, expected: String) {
            issues += PersistenceIssue("settings", "$expected; using this field's default in memory, source retained", field)
        }
        fun integer(field: String, fallback: Int, range: IntRange, automaticZero: Boolean = false): Int {
            if (field !in source) return fallback
            val value = source[field] as? JsonPrimitive
            val number = value?.takeUnless { it.isString }?.intOrNull
            if (number != null && (number in range || automaticZero && number == 0)) return number
            invalid(field, "Expected ${if (automaticZero) "0 (automatic) or " else ""}an integer between ${range.first} and ${range.last}")
            return fallback
        }
        fun boolean(field: String, fallback: Boolean): Boolean {
            if (field !in source) return fallback
            val value = (source[field] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (value != null) return value
            invalid(field, "Expected a boolean")
            return fallback
        }
        fun <T> codec(field: String, fallback: T, values: List<T>, name: (T) -> String, wire: (T) -> String): T {
            if (field !in source) return fallback
            val value = (source[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val canonical = if (field == "serverVersion") value?.let { ScrcpyServerVersion.of(it)?.version } else value
            val found = values.firstOrNull { name(it) == canonical || wire(it) == canonical }
            if (found != null) return found
            invalid(field, "Unknown codec")
            return fallback
        }
        /**
         * Absent or null means "capture the screen"; an object means a camera session.
         *
         * Decoded as a whole rather than field by field, so a malformed group falls back to no
         * camera instead of leaving a half-formed one the server would refuse — the same rule the
         * portable backup applies, for the same reason.
         */
        fun cameraCapture(fallback: com.scrcpy.remote.scrcpy.CameraCapture?):
            com.scrcpy.remote.scrcpy.CameraCapture? {
            val field = "camera"
            if (field !in source) return fallback
            val value = source[field]
            if (value is JsonNull) return null
            val obj = value as? JsonObject
            if (obj == null) {
                invalid(field, "Expected a camera object or null")
                return fallback
            }
            val camera = runCatching {
                json.decodeFromJsonElement(
                    com.scrcpy.remote.scrcpy.CameraCapture.serializer(), obj,
                ).normalized()
            }.getOrElse {
                invalid(field, "Expected a camera object or null")
                return fallback
            }
            SettingsValidation.cameraError(camera)?.let {
                invalid(field, it)
                return fallback
            }
            return camera
        }
        /**
         * Absent means "mirror the device's own screen"; a present string is a virtual display
         * spec, so a null and an empty string are genuinely different values here.
         */
        fun virtualDisplay(): String? {
            if ("virtualDisplay" !in source) return defaults.virtualDisplay
            val element = source["virtualDisplay"]
            if (element is JsonNull) return null
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null) {
                invalid("virtualDisplay", "Expected a virtual display size, or null")
                return defaults.virtualDisplay
            }
            val normalized = VirtualDisplaySpec.normalize(value)
            val problem = VirtualDisplaySpec.error(normalized)
            if (problem != null) {
                invalid("virtualDisplay", problem)
                return defaults.virtualDisplay
            }
            return normalized
        }
        /** Absent means "do not open anything"; a present string is a package name. */
        /** Absent means "let the device choose"; a present string names one of its encoders. */
        fun videoEncoder(): String? {
            if ("videoEncoder" !in source) return defaults.videoEncoder
            val element = source["videoEncoder"]
            if (element is JsonNull) return null
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || !EncoderName.isUsable(value)) {
                invalid("videoEncoder", "Expected an encoder name, or null")
                return defaults.videoEncoder
            }
            return value
        }
        /** Absent means "let the device choose"; a present string names one of its encoders. */
        fun audioEncoder(): String? {
            if ("audioEncoder" !in source) return defaults.audioEncoder
            val element = source["audioEncoder"]
            if (element is JsonNull) return null
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || !EncoderName.isUsable(value)) {
                invalid("audioEncoder", "Expected an encoder name, or null")
                return defaults.audioEncoder
            }
            return value
        }
        fun startApp(): String? {
            if ("startApp" !in source) return defaults.startApp
            val element = source["startApp"]
            if (element is JsonNull) return null
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value == null || !AppPackage.isSendable(value)) {
                invalid("startApp", "Expected a package name, or null")
                return defaults.startApp
            }
            return value
        }
        fun syncMode(): PlaybackSyncMode {
            if ("syncMode" !in source) return defaults.syncMode
            val value = (source["syncMode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val mode = PlaybackSyncMode.entries.firstOrNull { it.name == value }
            if (mode != null) return mode
            invalid("syncMode", "Unknown playback sync mode")
            return defaults.syncMode
        }
        val version = if (VERSION_FIELD !in source) 0 else {
            val number = (source[VERSION_FIELD] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (number == null || number !in 1..CURRENT_VERSION) {
                issues += PersistenceIssue("settings", "Unsupported settings version; automatic migration disabled", VERSION_FIELD)
            }
            number ?: -1
        }
        val settings = MirrorSettings(
            maxSize = integer("maxSize", defaults.maxSize, SettingsValidation.MAX_SIZE_RANGE),
            videoBitRateMbps = integer("videoBitRateMbps", defaults.videoBitRateMbps, SettingsValidation.BIT_RATE_RANGE),
            maxFps = integer("maxFps", defaults.maxFps, SettingsValidation.FPS_RANGE),
            videoCodec = codec("videoCodec", defaults.videoCodec, VideoCodec.entries, { it.name }, { it.serverValue }),
            captureOrientation = codec("captureOrientation", defaults.captureOrientation,
                CaptureOrientation.entries, { it.name }, { it.serverValue ?: it.name }),
            videoEncoder = videoEncoder(),
            audioEnabled = boolean("audioEnabled", defaults.audioEnabled),
            audioCodec = codec("audioCodec", defaults.audioCodec, AudioCodec.entries, { it.name }, { it.serverValue }),
            audioEncoder = audioEncoder(),
            audioSource = codec("audioSource", defaults.audioSource, AudioSource.entries, { it.name }, { it.serverValue }),
            audioDup = boolean("audioDup", defaults.audioDup),
            backgroundAudio = boolean("backgroundAudio", defaults.backgroundAudio),
            stayAwake = boolean("stayAwake", defaults.stayAwake),
            keepActive = boolean("keepActive", defaults.keepActive),
            showTouches = boolean("showTouches", defaults.showTouches),
            powerOffOnClose = boolean("powerOffOnClose", defaults.powerOffOnClose),
            turnScreenOff = boolean("turnScreenOff", defaults.turnScreenOff),
            virtualDisplay = virtualDisplay(),
            displayId = integer("displayId", defaults.displayId, SettingsValidation.DISPLAY_ID_RANGE),
            startApp = startApp(),
            touchOffsetXDp = integer("touchOffsetXDp", defaults.touchOffsetXDp, SettingsValidation.TOUCH_OFFSET_RANGE),
            touchOffsetYDp = integer("touchOffsetYDp", defaults.touchOffsetYDp, SettingsValidation.TOUCH_OFFSET_RANGE),
            captureEdgeGestures = boolean("captureEdgeGestures", defaults.captureEdgeGestures),
            syncMode = syncMode(),
            audioBufferMs = integer("audioBufferMs", defaults.audioBufferMs, SettingsValidation.AUDIO_BUFFER_RANGE,
                automaticZero = true),
            syncOffsetMs = integer("syncOffsetMs", defaults.syncOffsetMs, SettingsValidation.SYNC_OFFSET_RANGE),
            autoReconnect = boolean("autoReconnect", defaults.autoReconnect),
            audioBitRateKbps = integer("audioBitRateKbps", defaults.audioBitRateKbps,
                SettingsValidation.AUDIO_BIT_RATE_RANGE),
            clipboardAutosync = boolean("clipboardAutosync", defaults.clipboardAutosync),
            camera = cameraCapture(defaults.camera),
            serverVersion = codec("serverVersion", defaults.serverVersion, ScrcpyServerVersion.entries,
                { it.name }, { it.version }),
        )
        return DecodedSettings(settings, issues.toList(), version, source)
    }
}
