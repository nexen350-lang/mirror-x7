package com.scrcpy.remote

import com.scrcpy.remote.data.BackupFormatException
import com.scrcpy.remote.data.BackupFormatReason
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.ImportDisposition
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.ProfileBackupCodec
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.planImport
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProfileBackupTest {
    private val settings = MirrorSettings(
        maxSize = 1024,
        videoBitRateMbps = 12,
        maxFps = 90,
        videoCodec = VideoCodec.H265,
        captureOrientation = CaptureOrientation.LOCK_90,
        audioEnabled = false,
        audioCodec = AudioCodec.AAC,
        audioSource = AudioSource.PLAYBACK,
        audioDup = true,
        backgroundAudio = true,
        stayAwake = true,
        keepActive = true,
        showTouches = true,
        powerOffOnClose = true,
        turnScreenOff = true,
        touchOffsetXDp = -7,
        touchOffsetYDp = 19,
        captureEdgeGestures = false,
        syncMode = PlaybackSyncMode.SYNCHRONIZED,
        audioBufferMs = 150,
        syncOffsetMs = 200,
        autoReconnect = true,
        serverVersion = ScrcpyServerVersion.DEFAULT,
    )
    private val first = SavedDevice(
        id = "phone-id",
        name = "휴대폰 📱 \"desk\" \\",
        host = "192.168.1.20",
        port = 37123,
        isFavorite = true,
        portraitCalibration = TouchCalibration(-120, 120),
        landscapeCalibration = TouchCalibration(12, -45),
        mirroringSettings = DeviceMirroringSettings(
            maxSize = 800,
            videoBitRateMbps = 4,
            maxFps = 30,
            videoCodec = VideoCodec.H265,
            captureOrientation = CaptureOrientation.LOCK_CURRENT,
            audioEnabled = false,
            audioCodec = AudioCodec.AAC,
            audioSource = AudioSource.MIC,
            audioDup = true,
            backgroundAudio = true,
            stayAwake = true,
            keepActive = true,
            showTouches = true,
            powerOffOnClose = true,
            turnScreenOff = true,
            captureEdgeGestures = false,
            syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 20,
            syncOffsetMs = -200,
            autoReconnect = true,
            serverVersion = ScrcpyServerVersion.DEFAULT,
        ),
    )
    private val second = SavedDevice("tablet-id", "Tablet", "::1")
    private val snapshot = ConfigurationSnapshot(listOf(first, second), settings)
    private val minimalDevice = """{"id":"a","name":"Phone","host":"localhost"}"""
    private val invalidCameras = listOf(
        CameraCapture(size = "not-a-size"),
        CameraCapture(size = "1280X720"),
        CameraCapture(size = "0x720"),
        CameraCapture(size = "2147483648x720"),
        CameraCapture(aspectRatio = "not-a-ratio"),
        CameraCapture(aspectRatio = ":"),
        CameraCapture(aspectRatio = "16.0:9"),
        CameraCapture(aspectRatio = "16:0"),
        CameraCapture(aspectRatio = "-1.5"),
    )

    @Test fun fullConfigurationRoundTripsWithStableEnvelopeAndAllFields() {
        val bytes = ProfileBackupCodec.encode(snapshot)
        val decoded = ProfileBackupCodec.decode(bytes)
        assertEquals(snapshot, decoded)
        assertArrayEquals(bytes, ProfileBackupCodec.encode(decoded))
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("format", "version", "devices", "settings"), root.keys)
        assertEquals(JsonPrimitive("scrcpy-remote-settings"), root["format"])
        assertEquals(JsonPrimitive(13), root["version"])
        assertEquals(
            setOf("id", "name", "host", "port", "isFavorite", "portraitCalibration",
                "landscapeCalibration", "mirroringSettings", "avatarColor", "avatarIcon"),
            (root["devices"] as JsonArray).first().jsonObject.keys,
        )
        assertEquals(
            setOf("maxSize", "videoBitRateMbps", "maxFps", "videoCodec", "captureOrientation",
                "videoEncoder",
                "audioEnabled", "audioCodec", "audioEncoder", "audioSource", "audioDup", "backgroundAudio",
                "stayAwake", "keepActive", "showTouches", "powerOffOnClose", "turnScreenOff",
                "virtualDisplay", "displayId", "startApp",
                "touchOffsetXDp", "touchOffsetYDp", "captureEdgeGestures",
                "syncMode", "audioBufferMs", "syncOffsetMs", "autoReconnect",
                "audioBitRateKbps", "clipboardAutosync", "camera", "serverVersion"),
            root["settings"]!!.jsonObject.keys,
        )
        assertEquals(
            root["settings"]!!.jsonObject.keys - setOf("touchOffsetXDp", "touchOffsetYDp"),
            (root["devices"] as JsonArray).first().jsonObject["mirroringSettings"]!!.jsonObject.keys,
        )
    }

    @Test fun exportContainsNoAdbIdentityPreferencesRecoveryOrSessionFields() {
        val text = ProfileBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
        for (secret in listOf(
            "adbkey", "adb_identity", "privateKey", "publicKey", "BEGIN PRIVATE KEY",
            "preferences", "recovery", "history", "token", "schemaVersion", "address",
        )) {
            assertFalse(secret, text.contains(secret, ignoreCase = true))
        }
    }

    @Test fun emptyBackupAndMissingOptionalOldProfileMetadataUseModelDefaults() {
        for (version in listOf(1, 2)) {
            assertEquals(
                ConfigurationSnapshot(emptyList(), MirrorSettings()),
                ProfileBackupCodec.decode(backup(version = version)),
            )
            val decoded = ProfileBackupCodec.decode(backup(devices = "[$minimalDevice]", version = version))
            assertEquals(listOf(SavedDevice("a", "Phone", "localhost")), decoded.devices)
            assertEquals(MirrorSettings(), decoded.settings)
            assertFalse(decoded.devices.single().isFavorite)
            assertNull(decoded.devices.single().portraitCalibration)
            assertNull(decoded.devices.single().landscapeCalibration)
            assertNull(decoded.devices.single().mirroringSettings)
        }
    }

    @Test fun olderFullExportsKeepOldValuesAndDefaultAllNewSettingsWithoutChangingInheritance() {
        val version2Fields = setOf("syncMode", "audioBufferMs", "syncOffsetMs", "autoReconnect")
        val version3Fields = setOf("audioSource", "audioDup", "backgroundAudio", "keepActive")
        val version4Fields = setOf("captureOrientation")
        val version5Fields = setOf("virtualDisplay")
        val version6Fields = setOf("startApp")
        val version7Fields = setOf("videoEncoder")
        val version8Fields = setOf("audioEncoder")
        val version9Fields = setOf("displayId")
        val version10Fields = setOf("audioBitRateKbps", "clipboardAutosync")
        val version11Fields = setOf("camera")
        val version13Fields = setOf("serverVersion")
        val defaults = MirrorSettings()
        for ((version, removed) in mapOf(
            1 to version2Fields + version3Fields + version4Fields + version5Fields + version6Fields + version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            2 to version3Fields + version4Fields + version5Fields + version6Fields + version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            3 to version4Fields + version5Fields + version6Fields + version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            4 to version5Fields + version6Fields + version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            5 to version6Fields + version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            6 to version7Fields + version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            7 to version8Fields + version9Fields + version10Fields + version11Fields + version13Fields,
            8 to version9Fields + version10Fields + version11Fields + version13Fields,
            9 to version10Fields + version11Fields + version13Fields,
            10 to version11Fields + version13Fields,
            11 to version13Fields,
            12 to version13Fields,
        )) {
            val current = Json.parseToJsonElement(ProfileBackupCodec.encode(snapshot).toString(Charsets.UTF_8)).jsonObject
            val oldDevices = (current["devices"] as JsonArray).map { entry ->
                // Version 12 added a device's look; older files have no such fields.
                val device = JsonObject(entry.jsonObject - setOf("avatarColor", "avatarIcon"))
                val profile = device["mirroringSettings"] as? JsonObject
                if (profile == null) device
                else JsonObject(device + ("mirroringSettings" to JsonObject(profile - removed)))
            }
            val older = JsonObject(current + mapOf(
                "version" to JsonPrimitive(version),
                "settings" to JsonObject(current["settings"]!!.jsonObject - removed),
                "devices" to JsonArray(oldDevices),
            ))
            val decoded = ProfileBackupCodec.decode(older.toString().toByteArray(Charsets.UTF_8))
            fun MirrorSettings.withDefaultsFor(fields: Set<String>) = copy(
                syncMode = if ("syncMode" in fields) defaults.syncMode else syncMode,
                audioBufferMs = if ("audioBufferMs" in fields) defaults.audioBufferMs else audioBufferMs,
                syncOffsetMs = if ("syncOffsetMs" in fields) defaults.syncOffsetMs else syncOffsetMs,
                autoReconnect = if ("autoReconnect" in fields) defaults.autoReconnect else autoReconnect,
                audioSource = if ("audioSource" in fields) defaults.audioSource else audioSource,
                audioDup = if ("audioDup" in fields) defaults.audioDup else audioDup,
                backgroundAudio = if ("backgroundAudio" in fields) defaults.backgroundAudio else backgroundAudio,
                keepActive = if ("keepActive" in fields) defaults.keepActive else keepActive,
                captureOrientation = if ("captureOrientation" in fields) defaults.captureOrientation else captureOrientation,
                virtualDisplay = if ("virtualDisplay" in fields) defaults.virtualDisplay else virtualDisplay,
                startApp = if ("startApp" in fields) defaults.startApp else startApp,
                videoEncoder = if ("videoEncoder" in fields) defaults.videoEncoder else videoEncoder,
                audioEncoder = if ("audioEncoder" in fields) defaults.audioEncoder else audioEncoder,
                displayId = if ("displayId" in fields) defaults.displayId else displayId,
                audioBitRateKbps = if ("audioBitRateKbps" in fields) defaults.audioBitRateKbps else audioBitRateKbps,
                clipboardAutosync = if ("clipboardAutosync" in fields) defaults.clipboardAutosync else clipboardAutosync,
                camera = if ("camera" in fields) defaults.camera else camera,
                serverVersion = if ("serverVersion" in fields) defaults.serverVersion else serverVersion,
            )
            val expected = snapshot.copy(
                settings = settings.withDefaultsFor(removed),
                devices = snapshot.devices.map { device ->
                    device.copy(mirroringSettings = device.mirroringSettings?.let {
                        DeviceMirroringSettings.from(it.applyTo(defaults).withDefaultsFor(removed))
                    })
                },
            )
            assertEquals("version $version", expected, decoded)
            assertNull(decoded.devices[1].mirroringSettings)
            assertEquals(expected, ProfileBackupCodec.decode(ProfileBackupCodec.encode(decoded)))
        }
    }

    @Test fun supersededVersionsRejectNewerOnlyKeysEvenWhenTheirValuesAreDefaults() {
        val version2Only = mapOf(
            "syncMode" to JsonPrimitive("LOW_LATENCY"), "audioBufferMs" to JsonPrimitive(0),
            "syncOffsetMs" to JsonPrimitive(0), "autoReconnect" to JsonPrimitive(false),
        )
        val version3Only = mapOf(
            "audioSource" to JsonPrimitive("OUTPUT"), "audioDup" to JsonPrimitive(false),
            "backgroundAudio" to JsonPrimitive(false), "keepActive" to JsonPrimitive(false),
        )
        val version4Only = mapOf("captureOrientation" to JsonPrimitive("FOLLOW_DEVICE"))
        val version5Only = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "virtualDisplay" to JsonNull
        )
        val version6Only = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "startApp" to JsonNull
        )
        val version7Only = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "videoEncoder" to JsonNull
        )
        for ((version, rejected) in mapOf(
            1 to version2Only + version3Only + version4Only + version5Only + version6Only + version7Only,
            2 to version3Only + version4Only + version5Only + version6Only + version7Only,
            3 to version4Only + version5Only + version6Only + version7Only,
            4 to version5Only + version6Only + version7Only,
            5 to version6Only + version7Only,
            6 to version7Only,
        )) {
            for ((field, value) in rejected) {
                val settings = JsonObject(mapOf(field to value)).toString()
                assertEquals(
                    "$field in version $version",
                    BackupFormatReason.INVALID_DATA,
                    assertInvalid(backup(settings = settings, version = version)).reason,
                )
                assertEquals(
                    BackupFormatReason.INVALID_DATA,
                    assertInvalid(backup(devices = """[{"id":"a","name":"Phone","host":"localhost",
                        "mirroringSettings":$settings}]""", version = version)).reason,
                )
                assertEquals(
                    MirrorSettings(),
                    ProfileBackupCodec.decode(backup(settings = settings, version = 7)).settings,
                )
            }
        }
    }

    @Test fun version2AllowsAutomaticAndInclusivePlaybackTuningBoundsInBothSettingsScopes() {
        for (mode in PlaybackSyncMode.entries) {
            for (buffer in listOf(0, 20, 250)) {
                for (offset in listOf(-200, 0, 200)) {
                    val defaults = MirrorSettings(syncMode = mode, audioBufferMs = buffer,
                        syncOffsetMs = offset, autoReconnect = true)
                    val snapshot = ConfigurationSnapshot(
                        listOf(second.copy(mirroringSettings = DeviceMirroringSettings.from(defaults))), defaults,
                    )
                    assertEquals(snapshot, ProfileBackupCodec.decode(ProfileBackupCodec.encode(snapshot)))
                }
            }
        }
    }

    @Test fun explicitNullProfilesRemainInheritedAndEmptyObjectsUseTheirDefaults() {
        val raw = """[{"id":"a","name":"Phone","host":"localhost",
            "portraitCalibration":null,"landscapeCalibration":{},"mirroringSettings":{}}]"""
        val device = ProfileBackupCodec.decode(backup(devices = raw)).devices.single()
        assertNull(device.portraitCalibration)
        assertEquals(TouchCalibration(), device.landscapeCalibration)
        assertEquals(DeviceMirroringSettings(), device.mirroringSettings)
    }

    @Test fun snapshotItselfRequiresDevicesAndSettings() {
        for (raw in listOf("{}", """{"devices":[]}""", """{"settings":{}}""")) {
            assertThrows(IllegalArgumentException::class.java) {
                Json.decodeFromString<ConfigurationSnapshot>(raw)
            }
        }
        assertEquals(
            ConfigurationSnapshot(emptyList(), MirrorSettings()),
            Json.decodeFromString<ConfigurationSnapshot>("""{"devices":[],"settings":{}}"""),
        )
    }

    @Test fun malformedTruncatedAndArbitraryJsonNeverBecomeAnEmptyImport() {
        for (raw in listOf(
            "", " ", "not json", "{", "null", "[]", "42", "\"backup\"",
            """{"devices":[],"settings":{}}""",
            """{"format":"scrcpy-remote-settings","version":1,"devices":[""",
            """{"format":"scrcpy-remote-settings","version":1,"devices":[],"settings":{},}""",
        )) {
            assertInvalid(raw.toByteArray(Charsets.UTF_8))
        }
        val valid = ProfileBackupCodec.encode(snapshot)
        assertInvalid(valid.copyOf(valid.size - 1))
        assertInvalid(valid + "{}".toByteArray(Charsets.UTF_8))
    }

    @Test fun allEnvelopeFieldsAreRequiredAndHaveStrictTypes() {
        val valid = Json.parseToJsonElement(backup().toString(Charsets.UTF_8)).jsonObject
        for (field in valid.keys) {
            assertInvalid(JsonObject(valid - field).toString().toByteArray(Charsets.UTF_8))
        }
        for ((field, value) in listOf(
            "format" to "null", "format" to "1", "format" to "\"other-app\"",
            "version" to "\"1\"", "version" to "true", "version" to "1.0",
            "version" to "null", "version" to "2147483648", "version" to "01",
            "devices" to "{}", "devices" to "null", "devices" to "\"[]\"",
            "settings" to "[]", "settings" to "null", "settings" to "\"{}\"",
        )) {
            val raw = valid.entries.joinToString(",", "{", "}") { (key, element) ->
                "\"$key\":${if (key == field) value else element}"
            }
            assertInvalid(raw.toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun unsupportedOlderAndFutureVersionsAreRejectedRatherThanMigratedSilently() {
        // Derived from the current version rather than written out, so adding a version updates
        // what counts as "from the future" along with it. Pinning the number meant this test
        // started accepting the next version the moment that version existed.
        for (version in listOf(-1, 0, ProfileBackupCodec.VERSION + 1, Int.MAX_VALUE)) {
            assertEquals(
                BackupFormatReason.UNSUPPORTED_VERSION,
                assertInvalid(backup(version = version)).reason,
            )
        }
    }

    @Test fun deviceIdentityNameAndHostMustBeExplicitStrings() {
        val valid = Json.parseToJsonElement(minimalDevice).jsonObject
        for (field in valid.keys) {
            assertInvalid(backup(devices = "[${JsonObject(valid - field)}]"))
            for (replacement in listOf("null", "12", "true", "[]", "{}")) {
                val changed = JsonObject(valid + (field to Json.parseToJsonElement(replacement)))
                assertInvalid(backup(devices = "[$changed]"))
            }
        }
        for (entry in listOf("null", "12", "\"device\"", "[]")) {
            assertInvalid(backup(devices = "[$minimalDevice,$entry]"))
        }
    }

    @Test fun primitiveTypesNeverCoerceStringsNumbersNullsOrBooleans() {
        for (settings in listOf(
            """{"maxFps":"60"}""", """{"maxFps":60.5}""", """{"maxFps":null}""",
            """{"maxFps":2147483648}""", """{"maxFps":6e1}""", """{"maxFps":true}""",
            """{"audioEnabled":"true"}""", """{"audioEnabled":1}""", """{"audioEnabled":null}""",
            """{"videoCodec":1}""", """{"audioCodec":null}""",
            """{"syncMode":0}""", """{"syncMode":null}""", """{"syncMode":true}""",
            """{"audioBufferMs":"20"}""", """{"audioBufferMs":20.5}""", """{"audioBufferMs":2e1}""",
            """{"audioBufferMs":2147483648}""", """{"audioBufferMs":null}""", """{"audioBufferMs":false}""",
            """{"syncOffsetMs":"-20"}""", """{"syncOffsetMs":0.5}""", """{"syncOffsetMs":null}""",
            """{"syncOffsetMs":true}""", """{"autoReconnect":"true"}""",
            """{"autoReconnect":1}""", """{"autoReconnect":null}""",
        )) {
            assertInvalid(backup(settings = settings))
            assertInvalid(backup(devices = """[{"id":"a","name":"Phone","host":"localhost",
                "mirroringSettings":$settings}]"""))
        }
        for (extra in listOf(
            """"port":"5555"""", """"port":null""", """"port":5555.5""",
            """"isFavorite":"false"""", """"isFavorite":1""", """"isFavorite":null""",
            """"portraitCalibration":[]""", """"landscapeCalibration":"bad"""",
            """"mirroringSettings":true""", """"portraitCalibration":{"xDp":"1"}""",
            """"landscapeCalibration":{"yDp":null}""",
        )) {
            assertInvalid(backup(devices = "[${minimalDevice.dropLast(1)},$extra}]"))
        }
    }

    @Test fun unknownFieldsAndEnumsRejectTheWholeBackupAtEveryLevel() {
        for (settings in listOf(
            """{"future":1}""", """{"videoCodec":"FUTURE"}""", """{"audioCodec":"NEW"}""",
            """{"syncMode":"FUTURE"}""", """{"syncMode":"synchronized"}""",
        )) {
            assertInvalid(backup(settings = settings))
            assertInvalid(backup(devices = """[{"id":"a","name":"Phone","host":"localhost",
                "mirroringSettings":$settings}]"""))
        }
        assertInvalid(backup().toString(Charsets.UTF_8).dropLast(1).plus(""","token":"secret"}""").toByteArray())
        for (extra in listOf(
            """"privateKey":"secret"""",
            """"portraitCalibration":{"zDp":3}""",
            """"landscapeCalibration":{"future":true}""",
        )) {
            assertInvalid(backup(devices = "[${minimalDevice.dropLast(1)},$extra}]"))
        }
    }

    @Test fun validationRejectsInvalidEndpointsNamesIdsCalibrationsAndProfiles() {
        val invalidDevices = listOf(
            first.copy(host = "http://phone"),
            first.copy(host = "192.168.001.1"),
            first.copy(host = "256.1.1.1"),
            first.copy(host = "phone:5555"),
            first.copy(port = 0),
            first.copy(port = 65536),
            first.copy(name = " "),
            first.copy(name = "x".repeat(101)),
            first.copy(id = ""),
            first.copy(id = "x".repeat(257)),
            first.copy(portraitCalibration = TouchCalibration(121, 0)),
            first.copy(landscapeCalibration = TouchCalibration(0, -121)),
            first.copy(mirroringSettings = DeviceMirroringSettings(maxFps = 121)),
            first.copy(mirroringSettings = DeviceMirroringSettings(audioBufferMs = -1)),
            first.copy(mirroringSettings = DeviceMirroringSettings(audioBufferMs = 1)),
            first.copy(mirroringSettings = DeviceMirroringSettings(audioBufferMs = 19)),
            first.copy(mirroringSettings = DeviceMirroringSettings(audioBufferMs = 251)),
            first.copy(mirroringSettings = DeviceMirroringSettings(syncOffsetMs = -201)),
            first.copy(mirroringSettings = DeviceMirroringSettings(syncOffsetMs = 201)),
        )
        for (device in invalidDevices) {
            assertThrows(BackupFormatException::class.java) {
                ProfileBackupCodec.encode(ConfigurationSnapshot(listOf(device), settings))
            }
            val rawDevices = Json.encodeToString(listOf(second, device))
            assertInvalid(backup(devices = rawDevices))
        }
        for (invalid in listOf(
            settings.copy(maxSize = 0), settings.copy(videoBitRateMbps = 31),
            settings.copy(maxFps = 14), settings.copy(touchOffsetXDp = 121),
            settings.copy(touchOffsetYDp = -121),
            settings.copy(audioBufferMs = -1), settings.copy(audioBufferMs = 1),
            settings.copy(audioBufferMs = 19), settings.copy(audioBufferMs = 251),
            settings.copy(syncOffsetMs = -201), settings.copy(syncOffsetMs = 201),
        )) {
            assertInvalid(backup(settings = Json.encodeToString(invalid)))
            assertThrows(BackupFormatException::class.java) {
                ProfileBackupCodec.validateSnapshot(snapshot.copy(settings = invalid))
            }
        }
    }

    @Test fun invalidCameraValuesAreRejectedOnImportInBothSettingsScopes() {
        for (camera in invalidCameras) {
            val defaults = Json.encodeToString(MirrorSettings(camera = camera))
            val profile = Json.encodeToString(DeviceMirroringSettings(camera = camera))
            assertEquals(BackupFormatReason.INVALID_DATA, assertInvalid(backup(settings = defaults)).reason)
            assertEquals(
                BackupFormatReason.INVALID_DATA,
                assertInvalid(backup(
                    devices = "[${minimalDevice.dropLast(1)},\"mirroringSettings\":$profile}]",
                )).reason,
            )
        }
    }

    @Test fun invalidCameraValuesCannotBeExportedInEitherSettingsScope() {
        for (camera in invalidCameras) {
            val invalidSnapshots = listOf(
                snapshot.copy(settings = settings.copy(camera = camera)),
                snapshot.copy(devices = listOf(
                    first.copy(mirroringSettings = DeviceMirroringSettings(camera = camera)),
                )),
            )
            for (invalid in invalidSnapshots) {
                assertEquals(
                    BackupFormatReason.INVALID_DATA,
                    assertThrows(BackupFormatException::class.java) {
                        ProfileBackupCodec.encode(invalid)
                    }.reason,
                )
            }
        }
    }

    @Test fun validCameraSyntaxRoundTripsInBothSettingsScopes() {
        val cameras = listOf(
            CameraCapture(),
            CameraCapture(id = "0", size = "1280x720", fps = 30),
            CameraCapture(size = "128x128"),
            CameraCapture(size = "8192x4320"),
            CameraCapture(size = "+128x0128"),
            CameraCapture(size = "128x128x"),
            CameraCapture(aspectRatio = "sensor"),
            CameraCapture(aspectRatio = "0"),
            CameraCapture(aspectRatio = "-0.0"),
            CameraCapture(aspectRatio = "1"),
            CameraCapture(aspectRatio = "1.5"),
            CameraCapture(aspectRatio = ".5"),
            CameraCapture(aspectRatio = "+1.5"),
            CameraCapture(aspectRatio = "1e0"),
            CameraCapture(aspectRatio = "0x1.8p0"),
            CameraCapture(aspectRatio = "1.5f"),
            CameraCapture(aspectRatio = "NaN"),
            CameraCapture(aspectRatio = "Infinity"),
            CameraCapture(aspectRatio = "16:9"),
            CameraCapture(aspectRatio = "+16:09"),
            CameraCapture(aspectRatio = "1:"),
            CameraCapture(aspectRatio = "1:2:3"),
            CameraCapture(fps = 961),
            CameraCapture(fps = Int.MAX_VALUE),
            CameraCapture(fps = -1),
        )
        for (camera in cameras) {
            val expected = snapshot.copy(
                settings = settings.copy(camera = camera),
                devices = listOf(first.copy(mirroringSettings = DeviceMirroringSettings(camera = camera))),
            )
            assertEquals(camera.toString(), expected,
                ProfileBackupCodec.decode(ProfileBackupCodec.encode(expected)))
        }
    }

    @Test fun duplicateIdsAreRejectedEvenWhenRecordsOrEndpointsDiffer() {
        for (duplicate in listOf(first, first.copy(name = "Other", host = "another.local"))) {
            val devices = listOf(first, duplicate)
            assertEquals(
                BackupFormatReason.DUPLICATE_DEVICE_ID,
                assertInvalid(backup(devices = Json.encodeToString(devices))).reason,
            )
            assertThrows(BackupFormatException::class.java) {
                ProfileBackupCodec.encode(ConfigurationSnapshot(devices, settings))
            }
        }
    }

    @Test fun malformedUtf8IsRejectedWithoutReplacementCharacters() {
        val prefix = """{"format":"scrcpy-remote-settings","version":1,"devices":[{"id":"a","name":""""
            .toByteArray(Charsets.UTF_8)
        val suffix = """","host":"localhost"}],"settings":{}}""".toByteArray(Charsets.UTF_8)
        for (invalid in listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xC3.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
        )) {
            assertEquals(
                BackupFormatReason.INVALID_UTF8,
                assertInvalid(prefix + invalid + suffix).reason,
            )
        }
    }

    @Test fun escapedLoneSurrogatesAndMalformedInMemoryUnicodeAreRejected() {
        for (escaped in listOf("""\uD800""", """\uDC00""", """\uD800x""", """\uDC00\uD800""")) {
            for (field in listOf("id", "name", "host")) {
                val device = minimalDevice.replace(
                    when (field) { "id" -> "\"a\""; "name" -> "\"Phone\""; else -> "\"localhost\"" },
                    "\"$escaped\"",
                )
                assertInvalid(backup(devices = "[$device]"))
            }
        }
        for (text in listOf("\uD800", "\uDC00", "x\uD800", "\uDC00\uD800")) {
            for (device in listOf(first.copy(id = text), first.copy(name = text), first.copy(host = text))) {
                assertEquals(
                    BackupFormatReason.INVALID_UNICODE,
                    assertThrows(BackupFormatException::class.java) {
                        ProfileBackupCodec.encode(ConfigurationSnapshot(listOf(device), settings))
                    }.reason,
                )
            }
        }
        val escapedPair = minimalDevice.replace("Phone", """\uD83D\uDCF1""")
        assertEquals("📱", ProfileBackupCodec.decode(backup(devices = "[$escapedPair]")).devices.single().name)
    }

    @Test fun byteLimitIsInclusiveAndUsesUtf8BytesNotCharacterCount() {
        val bytes = ProfileBackupCodec.encode(snapshot)
        val padded = bytes + ByteArray(ProfileBackupCodec.MAX_BYTES - bytes.size) { ' '.code.toByte() }
        assertEquals(ProfileBackupCodec.MAX_BYTES, padded.size)
        assertTrue(padded.toString(Charsets.UTF_8).length < padded.size)
        assertEquals(snapshot, ProfileBackupCodec.decode(padded))
        assertEquals(BackupFormatReason.TOO_LARGE, assertInvalid(padded + byteArrayOf(32)).reason)
    }

    @Test fun exportAlsoRejectsAValidSnapshotWhoseEscapedRepresentationIsOversized() {
        val devices = List(ProfileBackupCodec.MAX_DEVICES) { index ->
            SavedDevice(
                // Control characters are rejected in names now, so the quote (escaped as two
                // characters) carries the name; the id's NULs expand six-fold.
                id = "\u0000".repeat(245) + index,
                name = "\"".repeat(100),
                host = List(4) { "a".repeat(61) }.joinToString("."),
            )
        }
        val oversized = ConfigurationSnapshot(devices, settings)
        assertEquals(
            BackupFormatReason.TOO_LARGE,
            assertThrows(BackupFormatException::class.java) { ProfileBackupCodec.encode(oversized) }.reason,
        )
        assertThrows(BackupFormatException::class.java) { ProfileBackupCodec.validateSnapshot(oversized) }
    }

    @Test fun parserNestingIsBoundedAndQuotedBracketsDoNotCount() {
        assertInvalid(("[".repeat(10_000) + "]".repeat(10_000)).toByteArray(Charsets.UTF_8))
        val quoted = snapshot.copy(devices = listOf(first.copy(name = "[".repeat(80))))
        assertEquals(quoted, ProfileBackupCodec.decode(ProfileBackupCodec.encode(quoted)))
    }

    @Test fun maximumDeviceCountIsInclusiveOnImportAndExport() {
        val full = ConfigurationSnapshot(devices(ProfileBackupCodec.MAX_DEVICES), settings)
        assertEquals(full, ProfileBackupCodec.decode(ProfileBackupCodec.encode(full)))
        val tooMany = full.copy(devices = devices(ProfileBackupCodec.MAX_DEVICES + 1))
        assertEquals(
            BackupFormatReason.TOO_MANY_DEVICES,
            assertThrows(BackupFormatException::class.java) { ProfileBackupCodec.encode(tooMany) }.reason,
        )
        assertEquals(
            BackupFormatReason.TOO_MANY_DEVICES,
            assertInvalid(backup(devices = Json.encodeToString(tooMany.devices))).reason,
        )
    }

    @Test fun defaultMergeKeepsWholeExistingRecordsAndAppendsInIncomingOrder() {
        val changed = first.copy(
            name = "Replacement", host = "other.local", port = 12345, isFavorite = false,
            portraitCalibration = null, landscapeCalibration = null, mirroringSettings = null,
        )
        val third = SavedDevice("third", "Third", "phone.local")
        val fourth = SavedDevice("fourth", "Fourth", "phone.local")
        val incoming = ConfigurationSnapshot(listOf(third, second, changed, fourth), MirrorSettings())
        val plan = planImport(snapshot, incoming)
        assertEquals(snapshot, plan.before)
        assertEquals(listOf(first, second, third, fourth), plan.after.devices)
        assertEquals(settings, plan.after.settings)
        assertEquals(2, plan.added)
        assertEquals(0, plan.replaced)
        assertEquals(1, plan.skipped)
        assertEquals(1, plan.unchanged)
        assertEquals(
            listOf(ImportDisposition.ADD, ImportDisposition.UNCHANGED, ImportDisposition.KEEP, ImportDisposition.ADD),
            plan.entries.map { it.disposition },
        )
        assertEquals(incoming.devices, plan.entries.map { it.incoming })
        assertEquals(listOf(null, second, first, null), plan.entries.map { it.existing })
    }

    @Test fun explicitReplacementChangesOnlySameIdRecordsAndNeverRemovesAbsentOnes() {
        val replacement = first.copy(
            name = "Replacement", host = "other.local", port = 12345, isFavorite = false,
            portraitCalibration = null, landscapeCalibration = null, mirroringSettings = null,
        )
        val third = SavedDevice("third", "Third", "phone.local")
        val incoming = ConfigurationSnapshot(listOf(third, replacement), MirrorSettings())
        val plan = planImport(snapshot, incoming, replaceExisting = true)
        assertEquals(listOf(replacement, second, third), plan.after.devices)
        assertEquals(1, plan.added)
        assertEquals(1, plan.replaced)
        assertEquals(0, plan.skipped)
        assertEquals(0, plan.unchanged)
        assertEquals(listOf(ImportDisposition.ADD, ImportDisposition.REPLACE), plan.entries.map { it.disposition })
        assertEquals(settings, plan.after.settings)
    }

    @Test fun identicalRecordsAreUnchangedEvenWithReplaceEnabledAndReimportsAreIdempotent() {
        val third = SavedDevice("third", "Third", first.host, first.port)
        val incoming = ConfigurationSnapshot(listOf(third, first), MirrorSettings())
        for (replace in listOf(false, true)) {
            val initial = planImport(snapshot, incoming, replaceExisting = replace, importDefaults = true)
            assertEquals(1, initial.added)
            assertEquals(1, initial.unchanged)
            val repeated = planImport(initial.after, incoming, replaceExisting = replace, importDefaults = true)
            assertEquals(initial.after, repeated.before)
            assertEquals(initial.after, repeated.after)
            assertEquals(0, repeated.added)
            assertEquals(0, repeated.replaced)
            assertEquals(0, repeated.skipped)
            assertEquals(2, repeated.unchanged)
        }
        val changed = incoming.copy(devices = listOf(first.copy(name = "Different")))
        val kept = planImport(snapshot, changed)
        val keptAgain = planImport(kept.after, changed)
        assertEquals(kept, keptAgain)
        val replaced = planImport(snapshot, changed, replaceExisting = true)
        val replacedAgain = planImport(replaced.after, changed, replaceExisting = true)
        assertEquals(0, replacedAgain.replaced)
        assertEquals(1, replacedAgain.unchanged)
    }

    @Test fun differentIdsAtTheSameEndpointAreSeparateProfiles() {
        val other = first.copy(id = "other-profile", name = "Same endpoint")
        for (replace in listOf(false, true)) {
            val plan = planImport(snapshot, ConfigurationSnapshot(listOf(other), settings), replaceExisting = replace)
            assertEquals(listOf(first, second, other), plan.after.devices)
            assertEquals(1, plan.added)
            assertEquals(0, plan.replaced)
        }
    }

    @Test fun defaultImportToggleIsIndependentOfReplacementAndEmptyImportsDoNotDelete() {
        val incoming = ConfigurationSnapshot(emptyList(), MirrorSettings())
        for (replace in listOf(false, true)) {
            for (importDefaults in listOf(false, true)) {
                val plan = planImport(snapshot, incoming, replace, importDefaults)
                assertEquals(snapshot.devices, plan.after.devices)
                assertEquals(if (importDefaults) incoming.settings else settings, plan.after.settings)
                assertEquals(0, plan.added + plan.replaced + plan.skipped + plan.unchanged)
                assertTrue(plan.entries.isEmpty())
            }
        }
    }

    @Test fun planningAndDecodingFreezeTheirResultsWithoutMutatingSourceCollections() {
        val currentDevices = mutableListOf(first, second)
        val third = first.copy(id = "third")
        val incomingDevices = mutableListOf(third)
        val current = ConfigurationSnapshot(currentDevices, settings)
        val incoming = ConfigurationSnapshot(incomingDevices, MirrorSettings())
        val frozen = current.frozenCopy()
        val plan = planImport(current, incoming)
        assertEquals(listOf(first, second), currentDevices)
        assertEquals(listOf(third), incomingDevices)
        currentDevices.clear()
        incomingDevices.clear()
        assertEquals(snapshot, frozen)
        assertEquals(snapshot, plan.before)
        assertEquals(listOf(first, second, third), plan.after.devices)
        assertEquals(listOf(third), plan.entries.map { it.incoming })
        for (list in listOf(frozen.devices, plan.before.devices, plan.after.devices,
            ProfileBackupCodec.decode(ProfileBackupCodec.encode(snapshot)).devices)) {
            assertThrows(UnsupportedOperationException::class.java) { (list as MutableList<*>).clear() }
        }
        assertThrows(UnsupportedOperationException::class.java) { (plan.entries as MutableList<*>).clear() }
    }

    @Test fun mergeValidatesIncomingEvenWhenConflictingRecordsOrDefaultsWouldBeKept() {
        val invalidSnapshots = listOf(
            snapshot.copy(devices = listOf(first.copy(host = "http://invalid"))),
            snapshot.copy(devices = listOf(first, first)),
            snapshot.copy(devices = listOf(first.copy(name = "\uD800"))),
            snapshot.copy(settings = settings.copy(maxFps = 999)),
            snapshot.copy(devices = devices(ProfileBackupCodec.MAX_DEVICES + 1)),
        )
        for (incoming in invalidSnapshots) {
            assertThrows(BackupFormatException::class.java) { planImport(snapshot, incoming) }
        }
        assertEquals(listOf(first, second), snapshot.devices)
        assertThrows(BackupFormatException::class.java) {
            planImport(snapshot.copy(devices = listOf(first, first)), snapshot)
        }
    }

    @Test fun mergeLimitAppliesToResultRatherThanTheSumOfInputCounts() {
        val current = ConfigurationSnapshot(devices(ProfileBackupCodec.MAX_DEVICES), settings)
        val identical = planImport(current, current, replaceExisting = true)
        assertEquals(ProfileBackupCodec.MAX_DEVICES, identical.unchanged)
        assertEquals(current, identical.after)
        val atLimit = planImport(
            current.copy(devices = current.devices.dropLast(1)),
            current.copy(devices = current.devices.takeLast(1)),
        )
        assertEquals(current, atLimit.after)
        assertEquals(1, atLimit.added)
        val incoming = ConfigurationSnapshot(listOf(first), settings)
        assertEquals(
            BackupFormatReason.TOO_MANY_DEVICES,
            assertThrows(BackupFormatException::class.java) { planImport(current, incoming) }.reason,
        )
        assertEquals(ProfileBackupCodec.MAX_DEVICES, current.devices.size)
    }

    private fun devices(count: Int): List<SavedDevice> =
        List(count) { SavedDevice("device-$it", "Phone $it", "localhost") }

    private fun backup(
        devices: String = "[]",
        settings: String = "{}",
        version: Int = ProfileBackupCodec.VERSION,
    ): ByteArray = """{"format":"scrcpy-remote-settings","version":$version,"devices":$devices,"settings":$settings}"""
        .toByteArray(Charsets.UTF_8)

    private fun assertInvalid(bytes: ByteArray): BackupFormatException {
        val error = assertThrows(IOException::class.java) { ProfileBackupCodec.decode(bytes) }
        assertTrue(error is BackupFormatException)
        assertFalse(error.message.isNullOrBlank())
        return error as BackupFormatException
    }

    @Test fun aVersion7BackupCarryingTheVersion8FieldIsRejected() {
        // Each superseded version keeps its own allowlist so a file claiming to be older, yet
        // carrying a newer field, is refused instead of imported as a silently different profile.
        val settings = """{"audioEncoder":"c2.android.opus.encoder"}"""
        val failure = assertThrows(BackupFormatException::class.java) {
            ProfileBackupCodec.decode(backup(settings = settings, version = 7))
        }
        assertEquals(BackupFormatReason.INVALID_DATA, failure.reason)
        assertTrue(failure.message, failure.message!!.contains("audioEncoder"))
    }

    @Test fun anUnusableAudioEncoderNameIsRejectedOnImport() {
        val settings = """{"audioEncoder":"enc oder"}"""
        val failure = assertThrows(BackupFormatException::class.java) {
            ProfileBackupCodec.decode(backup(settings = settings))
        }
        assertEquals(BackupFormatReason.INVALID_DATA, failure.reason)
    }

    @Test fun aRepeatedKeyIsRefusedRatherThanSilentlyResolved() {
        // The parser keeps one of the two values, so the import would apply a setting the file
        // never unambiguously stated, and a re-export would make that choice permanent.
        val failure = assertThrows(BackupFormatException::class.java) {
            ProfileBackupCodec.decode(backup(settings = """{"displayId":1,"displayId":2}"""))
        }
        assertEquals(BackupFormatReason.INVALID_DATA, failure.reason)
        assertTrue(failure.message, failure.message!!.contains("displayId"))
    }

    @Test fun aKeyRepeatedBehindAUnicodeEscapeIsAlsoRefused() {
        // "display\u0049d" and "displayId" are one key to the parser but two different spellings
        // to a comparison of the raw text, so an escape was a way to smuggle the repeat past the
        // check and have the import quietly settle on one of the two values.
        val failure = assertThrows(BackupFormatException::class.java) {
            ProfileBackupCodec.decode(backup(settings = """{"displayId":1,"display\u0049d":2}"""))
        }
        assertEquals(BackupFormatReason.INVALID_DATA, failure.reason)
    }

    @Test fun aRepeatedEnvelopeKeyIsAlsoRefused() {
        assertEquals(
            BackupFormatReason.INVALID_DATA,
            assertThrows(BackupFormatException::class.java) {
                ProfileBackupCodec.decode(
                    """{"format":"scrcpy-remote-settings","version":9,"version":1,"devices":[],"settings":{}}"""
                        .toByteArray(Charsets.UTF_8)
                )
            }.reason,
        )
    }

    @Test fun theSameKeyInDifferentObjectsIsFine() {
        // Every device repeats the same field names; only a repeat inside one object is ambiguous.
        val devices = """[{"id":"a","name":"A","host":"10.0.0.1","port":5555},""" +
            """{"id":"b","name":"B","host":"10.0.0.2","port":5555}]"""
        assertEquals(2, ProfileBackupCodec.decode(backup(devices = devices)).devices.size)
    }
    @Test fun theServerChoiceTravelsInABackupFromVersion13() {
        val settings = """{"serverVersion":"V4_1"}"""
        val profile = """[{"id":"a","name":"Phone","host":"localhost","mirroringSettings":$settings}]"""
        val decoded = ProfileBackupCodec.decode(backup(settings = settings, devices = profile, version = 13))
        assertEquals(ScrcpyServerVersion.DEFAULT, decoded.settings.serverVersion)
        assertEquals(ScrcpyServerVersion.DEFAULT, decoded.devices.single().mirroringSettings?.serverVersion)
        // A file claiming an older version may not carry a field that version did not have.
        for (version in 1..12) {
            assertEquals("version $version", BackupFormatReason.INVALID_DATA,
                assertInvalid(backup(settings = settings, version = version)).reason)
            assertEquals("version $version profile", BackupFormatReason.INVALID_DATA,
                assertInvalid(backup(devices = profile, version = version)).reason)
        }
        for (bad in listOf("\"V9_9\"", "41", "null")) {
            assertEquals(bad, BackupFormatReason.INVALID_DATA,
                assertInvalid(backup(settings = """{"serverVersion":$bad}""", version = 13)).reason)
        }
    }
}