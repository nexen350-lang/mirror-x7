package com.scrcpy.remote.data

import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.EncoderName
import com.scrcpy.remote.scrcpy.Utf8Text
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Use [frozenCopy] when capturing mutable storage lists; codec and planner results are already frozen. */
@Serializable
data class ConfigurationSnapshot(
    val devices: List<SavedDevice>,
    val settings: MirrorSettings,
) {
    fun frozenCopy(): ConfigurationSnapshot = copy(devices = frozenList(devices))
}

enum class BackupFormatReason {
    TOO_LARGE,
    TOO_MANY_DEVICES,
    INVALID_UTF8,
    INVALID_UNICODE,
    MALFORMED_JSON,
    INVALID_ENVELOPE,
    UNSUPPORTED_VERSION,
    INVALID_DATA,
    DUPLICATE_DEVICE_ID,
}

class BackupFormatException(
    val reason: BackupFormatReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** A portable allowlist of configuration, never a serialization of preference storage. */
object ProfileBackupCodec {
    const val MAX_BYTES = 1_048_576
    const val MAX_DEVICES = 500
    const val FORMAT = "scrcpy-remote-settings"
    const val VERSION = 13

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
    }
    private val integerFields = setOf(
        "maxSize", "videoBitRateMbps", "maxFps", "touchOffsetXDp", "touchOffsetYDp",
        "audioBufferMs", "syncOffsetMs", "displayId", "audioBitRateKbps",
    )
    private val booleanFields = setOf(
        "audioEnabled", "audioDup", "backgroundAudio", "stayAwake", "keepActive", "showTouches",
        "powerOffOnClose", "turnScreenOff", "captureEdgeGestures", "autoReconnect", "clipboardAutosync",
    )
    private val version1SettingsFields = setOf(
        "maxSize", "videoBitRateMbps", "maxFps", "videoCodec", "audioEnabled", "audioCodec",
        "stayAwake", "showTouches", "powerOffOnClose", "turnScreenOff", "touchOffsetXDp",
        "touchOffsetYDp", "captureEdgeGestures",
    )
    private val version2SettingsFields = version1SettingsFields + setOf(
        "syncMode", "audioBufferMs", "syncOffsetMs", "autoReconnect",
    )

    private val version3SettingsFields = version2SettingsFields + setOf(
        "audioSource", "audioDup", "backgroundAudio", "keepActive",
    )

    private val version4SettingsFields = version3SettingsFields + setOf("captureOrientation")

    private val version5SettingsFields = version4SettingsFields + setOf("virtualDisplay")

    private val version6SettingsFields = version5SettingsFields + setOf("startApp")

    private val version7SettingsFields = version6SettingsFields + setOf("videoEncoder")

    private val version8SettingsFields = version7SettingsFields + setOf("audioEncoder")

    private val version9SettingsFields = version8SettingsFields + setOf("displayId")

    private val version10SettingsFields =
        version9SettingsFields + setOf("audioBitRateKbps", "clipboardAutosync")

    private val version11SettingsFields = version10SettingsFields + setOf("camera")

    /** Version 12 added the device look, and no setting; 13 added the server choice. */
    private val version12SettingsFields = version11SettingsFields

    /** Device fields a version added; a file claiming an older version may not carry them. */
    private val deviceLookFields = setOf("avatarColor", "avatarIcon")
    private const val DEVICE_LOOK_VERSION = 12

    /**
     * Every superseded version keeps its own allowlist so an older file that somehow carries a
     * newer field is rejected outright instead of being imported as a silently different profile.
     * The current version is deliberately absent: it accepts every field this build knows.
     */
    private val settingsFieldsByVersion = mapOf(
        1 to version1SettingsFields,
        2 to version2SettingsFields,
        3 to version3SettingsFields,
        4 to version4SettingsFields,
        5 to version5SettingsFields,
        6 to version6SettingsFields,
        7 to version7SettingsFields,
        8 to version8SettingsFields,
         9 to version9SettingsFields,
        10 to version10SettingsFields,
        11 to version11SettingsFields,
        12 to version12SettingsFields,
    )
    private val deviceOnlyExclusions = setOf("touchOffsetXDp", "touchOffsetYDp")
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")

    @Serializable
    private data class Envelope(
        val format: String,
        val version: Int,
        val devices: List<SavedDevice>,
        val settings: MirrorSettings,
    )

    fun encode(snapshot: ConfigurationSnapshot): ByteArray {
        val frozen = snapshot.frozenCopy()
        validateValues(frozen)
        val text = json.encodeToString(Envelope(FORMAT, VERSION, frozen.devices, frozen.settings))
        val bytes = Utf8Text.encode(text)
        checkSize(bytes.size)
        return bytes
    }

    fun decode(bytes: ByteArray): ConfigurationSnapshot {
        checkSize(bytes.size)
        val rawText = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw BackupFormatException(BackupFormatReason.INVALID_UTF8, "Backup is not valid UTF-8", error)
        }
        val text = rawText.removePrefix("\uFEFF")
        checkJsonShape(text)
        val root = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw BackupFormatException(BackupFormatReason.MALFORMED_JSON, "Malformed backup JSON", error)
        } as? JsonObject ?: fail(BackupFormatReason.INVALID_ENVELOPE, "Expected a backup object")

        val format = root["format"] as? JsonPrimitive
        if (format?.isString != true || format.content != FORMAT) {
            fail(BackupFormatReason.INVALID_ENVELOPE, "Not a scrcpy settings backup")
        }
        val version = integer(root["version"] as? JsonPrimitive)
            ?: fail(BackupFormatReason.INVALID_ENVELOPE, "Missing or invalid backup version")
        if (version !in 1..VERSION) {
            fail(BackupFormatReason.UNSUPPORTED_VERSION, "Unsupported backup version: $version")
        }
        val devices = root["devices"] as? JsonArray
            ?: fail(BackupFormatReason.INVALID_ENVELOPE, "Missing or invalid devices array")
        val settings = root["settings"] as? JsonObject
            ?: fail(BackupFormatReason.INVALID_ENVELOPE, "Missing or invalid settings object")
        checkDeviceCount(devices.size)
        checkSettingsTypes(settings, version)
        devices.forEachIndexed { index, value ->
            val device = value as? JsonObject
                ?: fail(BackupFormatReason.INVALID_DATA, "Device ${index + 1} must be an object")
            // SavedDevice's generated ID default is unsafe for repeatable ID-based imports.
            for (field in listOf("id", "name", "host")) {
                val string = device[field] as? JsonPrimitive
                if (string?.isString != true) {
                    fail(BackupFormatReason.INVALID_DATA, "Device ${index + 1} requires a string $field")
                }
            }
            checkTypes(device, setOf("port"), setOf("isFavorite"))
            if (version < DEVICE_LOOK_VERSION) {
                device.keys.firstOrNull { it in deviceLookFields }?.let {
                    fail(BackupFormatReason.INVALID_DATA, "$it is not supported in a version $version backup")
                }
            }
            // Null is the automatic look, which is what an export writes when none was chosen.
            device["avatarColor"]?.takeIf { it != JsonNull }?.let { value ->
                if (integer(value as? JsonPrimitive) == null) {
                    fail(BackupFormatReason.INVALID_DATA, "avatarColor must be an integer or null")
                }
            }
            device["avatarIcon"]?.takeIf { it != JsonNull }?.let { value ->
                if ((value as? JsonPrimitive)?.isString != true) {
                    fail(BackupFormatReason.INVALID_DATA, "avatarIcon must be an icon name or null")
                }
            }
            for (field in listOf("portraitCalibration", "landscapeCalibration", "mirroringSettings")) {
                val profile = device[field] ?: continue
                if (profile == JsonNull) continue
                if (profile !is JsonObject) {
                    fail(BackupFormatReason.INVALID_DATA, "Device ${index + 1} has an invalid $field")
                }
                if (field == "mirroringSettings") checkSettingsTypes(profile, version, deviceProfile = true)
                else checkTypes(profile, setOf("xDp", "yDp"), emptySet())
            }
        }
        val envelope = try {
            json.decodeFromJsonElement<Envelope>(root)
        } catch (error: SerializationException) {
            throw BackupFormatException(
                BackupFormatReason.INVALID_DATA, "Unknown or invalid configuration field", error,
            )
        }
        val snapshot = ConfigurationSnapshot(envelope.devices, envelope.settings).frozenCopy()
        validateValues(snapshot)
        return snapshot
    }

    /** Also checks that the complete portable representation fits the export limit. */
    fun validateSnapshot(snapshot: ConfigurationSnapshot) {
        encode(snapshot)
    }

    private fun validateValues(snapshot: ConfigurationSnapshot) {
        checkDeviceCount(snapshot.devices.size)
        val settingsError = SettingsValidation.settingsErrors(snapshot.settings).entries.firstOrNull()
        if (settingsError != null) {
            fail(BackupFormatReason.INVALID_DATA, "Invalid defaults: ${settingsError.key}: ${settingsError.value}")
        }
        val ids = HashSet<String>()
        snapshot.devices.forEachIndexed { index, device ->
            for (text in listOf(device.id, device.name, device.host)) {
                try {
                    Utf8Text.encode(text)
                } catch (error: IllegalArgumentException) {
                    throw BackupFormatException(
                        BackupFormatReason.INVALID_UNICODE, "Device ${index + 1} contains invalid Unicode", error,
                    )
                }
            }
            SettingsValidation.deviceError(device)?.let {
                fail(BackupFormatReason.INVALID_DATA, "Device ${index + 1}: $it")
            }
            if (!ids.add(device.id)) {
                fail(BackupFormatReason.DUPLICATE_DEVICE_ID, "Duplicate device identifier")
            }
        }
    }

    private fun checkSettingsTypes(settings: JsonObject, version: Int, deviceProfile: Boolean = false) {
        settingsFieldsByVersion[version]?.let { supported ->
            val allowed = if (deviceProfile) supported - deviceOnlyExclusions else supported
            settings.keys.firstOrNull { it !in allowed }?.let {
                fail(BackupFormatReason.INVALID_DATA, "$it is not supported in a version $version backup")
            }
        }
        checkTypes(settings, integerFields, booleanFields)
        settings["virtualDisplay"]?.let { value ->
            // Null and a string mean different things here, so neither may be coerced silently.
            if (value !is JsonNull && (value as? JsonPrimitive)?.isString != true) {
                fail(BackupFormatReason.INVALID_DATA, "virtualDisplay must be a display size or null")
            }
            if (value is JsonPrimitive && value.isString &&
                VirtualDisplaySpec.error(value.content) != null
            ) {
                fail(BackupFormatReason.INVALID_DATA, "virtualDisplay is not a usable display size")
            }
        }
        settings["startApp"]?.let { value ->
            if (value !is JsonNull && (value as? JsonPrimitive)?.isString != true) {
                fail(BackupFormatReason.INVALID_DATA, "startApp must be a package name or null")
            }
            if (value is JsonPrimitive && value.isString && !AppPackage.isSendable(value.content)) {
                fail(BackupFormatReason.INVALID_DATA, "startApp is not a usable package name")
            }
        }
        settings["camera"]?.let { value ->
            // A whole group rather than a scalar, so it is checked as one: either absent, or an
            // object whose own fields are the right shapes. A half-formed camera would otherwise
            // produce a session the server refuses, from a file that imported cleanly.
            if (value is JsonNull) return@let
            val camera = value as? JsonObject
                ?: fail(BackupFormatReason.INVALID_DATA, "camera must be an object or null")
            val known = setOf("id", "facing", "size", "aspectRatio", "fps", "highSpeed")
            camera.keys.firstOrNull { it !in known }?.let {
                fail(BackupFormatReason.INVALID_DATA, "camera has an unknown field: $it")
            }
            for (name in listOf("id", "facing", "size", "aspectRatio")) {
                val field = camera[name] ?: continue
                if (field !is JsonNull && (field as? JsonPrimitive)?.isString != true) {
                    fail(BackupFormatReason.INVALID_DATA, "camera.$name must be text or null")
                }
            }
            checkTypes(camera, setOf("fps"), setOf("highSpeed"))
        }
        settings["videoEncoder"]?.let { value ->
            if (value !is JsonNull && (value as? JsonPrimitive)?.isString != true) {
                fail(BackupFormatReason.INVALID_DATA, "videoEncoder must be an encoder name or null")
            }
            if (value is JsonPrimitive && value.isString && !EncoderName.isUsable(value.content)) {
                fail(BackupFormatReason.INVALID_DATA, "videoEncoder is not a usable encoder name")
            }
        }
        settings["audioEncoder"]?.let { value ->
            if (value !is JsonNull && (value as? JsonPrimitive)?.isString != true) {
                fail(BackupFormatReason.INVALID_DATA, "audioEncoder must be an encoder name or null")
            }
            if (value is JsonPrimitive && value.isString && !EncoderName.isUsable(value.content)) {
                fail(BackupFormatReason.INVALID_DATA, "audioEncoder is not a usable encoder name")
            }
        }
        for (field in listOf("videoCodec", "audioCodec", "audioSource", "captureOrientation", "syncMode", "serverVersion")) {
            val value = settings[field] ?: continue
            if ((value as? JsonPrimitive)?.isString != true) {
                fail(BackupFormatReason.INVALID_DATA, "$field must be an enum name")
            }
        }
    }

    private fun checkTypes(source: JsonObject, integers: Set<String>, booleans: Set<String>) {
        for (field in integers) {
            val value = source[field] ?: continue
            if (integer(value as? JsonPrimitive) == null) {
                fail(BackupFormatReason.INVALID_DATA, "$field must be an integer")
            }
        }
        for (field in booleans) {
            val value = source[field] ?: continue
            val primitive = value as? JsonPrimitive
            if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                fail(BackupFormatReason.INVALID_DATA, "$field must be a boolean")
            }
        }
    }

    private fun integer(value: JsonPrimitive?): Int? =
        value?.takeUnless { it.isString }?.content
            ?.takeIf { integerPattern.matches(it) }?.toIntOrNull()

    private fun checkSize(size: Int) {
        if (size > MAX_BYTES) fail(BackupFormatReason.TOO_LARGE, "Backup exceeds 1 MiB")
    }

    private fun checkDeviceCount(count: Int) {
        if (count > MAX_DEVICES) {
            fail(BackupFormatReason.TOO_MANY_DEVICES, "Backup exceeds $MAX_DEVICES devices")
        }
    }

    /**
     * Checks the document's shape before it is parsed: nesting depth, control characters, and
     * repeated keys.
     *
     * The parser resolves a repeated key by keeping one of the values, so `"displayId": 1,
     * "displayId": 2` would import as a setting the file never unambiguously stated — and a
     * re-export would then make that silent choice permanent. Everything else about this format
     * refuses ambiguous input rather than guessing, so this does too.
     */
    private fun checkJsonShape(text: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        val containers = ArrayDeque<Char>()
        val objectKeys = ArrayDeque<MutableSet<String>>()
        val token = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char.code < 0x20) fail(BackupFormatReason.MALFORMED_JSON, "Unescaped JSON control character")
                when {
                    escaped -> { escaped = false; token.append(char) }
                    char == '\\' -> { escaped = true; token.append(char) }
                    char == '"' -> {
                        quoted = false
                        // A string is a key when it sits directly inside an object and the next
                        // thing after it is a colon.
                        var ahead = index + 1
                        while (ahead < text.length && text[ahead].isWhitespace()) ahead++
                        if (containers.lastOrNull() == '{' && ahead < text.length && text[ahead] == ':') {
                            val key = token.toString()
                            // Keys in this format are plain identifiers, so an escape inside one
                            // can only disguise what it actually says. `display\u0049d` reads as
                            // `displayId` to the parser but not to the comparison below, which
                            // made it a way to smuggle a repeated key past this check.
                            if ('\\' in key) {
                                fail(BackupFormatReason.INVALID_DATA, "Escaped key in a backup object: $key")
                            }
                            if (!objectKeys.last().add(key)) {
                                fail(BackupFormatReason.INVALID_DATA, "Repeated key in a backup object: $key")
                            }
                        }
                    }
                    else -> token.append(char)
                }
            } else {
                when (char) {
                    '"' -> { quoted = true; token.setLength(0) }
                    '{' -> {
                        // The schema needs only four levels; bound untrusted parser recursion.
                        if (++depth > 32) fail(BackupFormatReason.MALFORMED_JSON, "Backup JSON is nested too deeply")
                        containers.addLast('{')
                        objectKeys.addLast(mutableSetOf())
                    }
                    '[' -> {
                        if (++depth > 32) fail(BackupFormatReason.MALFORMED_JSON, "Backup JSON is nested too deeply")
                        containers.addLast('[')
                    }
                    '}' -> {
                        depth--
                        if (containers.lastOrNull() == '{') { containers.removeLast(); objectKeys.removeLast() }
                    }
                    ']' -> {
                        depth--
                        if (containers.lastOrNull() == '[') containers.removeLast()
                    }
                }
            }
            index++
        }
    }
}

enum class ImportDisposition { ADD, REPLACE, KEEP, UNCHANGED }

data class ProfileImportEntry(
    val incoming: SavedDevice,
    val existing: SavedDevice?,
    val disposition: ImportDisposition,
)

data class ProfileImportPlan(
    val before: ConfigurationSnapshot,
    val after: ConfigurationSnapshot,
    val added: Int,
    val replaced: Int,
    val skipped: Int,
    val unchanged: Int,
    val entries: List<ProfileImportEntry>,
    /** Preview-only metadata; matching decoded values can still replace damaged stored bytes. */
    val repairsStorage: Boolean = false,
) {
    val hasChanges: Boolean get() = repairsStorage || before != after
}

/** ID-only, non-destructive merge; endpoints may intentionally have multiple saved profiles. */
fun planImport(
    current: ConfigurationSnapshot,
    incoming: ConfigurationSnapshot,
    replaceExisting: Boolean = false,
    importDefaults: Boolean = false,
): ProfileImportPlan {
    val before = current.frozenCopy()
    val imported = incoming.frozenCopy()
    ProfileBackupCodec.validateSnapshot(before)
    ProfileBackupCodec.validateSnapshot(imported)
    val devices = before.devices.toMutableList()
    val indices = before.devices.withIndex().associate { it.value.id to it.index }
    val entries = imported.devices.map { device ->
        val index = indices[device.id]
        val existing = index?.let { devices[it] }
        val disposition = when {
            existing == null -> {
                devices.add(device)
                ImportDisposition.ADD
            }
            existing == device -> ImportDisposition.UNCHANGED
            replaceExisting -> {
                devices[checkNotNull(index)] = device
                ImportDisposition.REPLACE
            }
            else -> ImportDisposition.KEEP
        }
        ProfileImportEntry(device, existing, disposition)
    }
    val after = ConfigurationSnapshot(
        devices = frozenList(devices),
        settings = if (importDefaults) imported.settings else before.settings,
    )
    ProfileBackupCodec.validateSnapshot(after)
    return ProfileImportPlan(
        before = before,
        after = after,
        added = entries.count { it.disposition == ImportDisposition.ADD },
        replaced = entries.count { it.disposition == ImportDisposition.REPLACE },
        skipped = entries.count { it.disposition == ImportDisposition.KEEP },
        unchanged = entries.count { it.disposition == ImportDisposition.UNCHANGED },
        entries = frozenList(entries),
    )
}

private fun <T> frozenList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun fail(reason: BackupFormatReason, message: String): Nothing =
    throw BackupFormatException(reason, message)
