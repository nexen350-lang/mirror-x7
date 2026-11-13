package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun devicesSurviveARoundTripAndOldArraysRemainCompatible() {
        val devices = listOf(
            SavedDevice(id = "a", name = "Pixel", host = "192.168.1.20", port = 5555),
            SavedDevice(id = "b", name = "Tablet", host = "10.0.0.5", port = 37123),
        )
        val decoded = SettingsCodec.decodeDevices(SettingsCodec.encodeDevices(devices))
        assertEquals(devices, decoded.value)
        assertTrue(decoded.issues.isEmpty())
        assertEquals("10.0.0.5:37123", decoded.value[1].address)
        assertEquals(devices.take(1), SettingsCodec.decodeDevices(
            """[{"id":"a","name":"Pixel","host":"192.168.1.20","port":5555}]""",
        ).value)
    }

    @Test
    fun settingsSurviveAVersionedRoundTrip() {
        val settings = MirrorSettings(
            maxSize = 1024,
            videoBitRateMbps = 12,
            maxFps = 90,
            videoCodec = VideoCodec.H265,
            captureOrientation = CaptureOrientation.LOCK_180,
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
            virtualDisplay = "1280x720/240",
            startApp = "com.example.kiosk",
            videoEncoder = "c2.qti.avc.encoder",
            touchOffsetXDp = -7,
            touchOffsetYDp = -13,
            captureEdgeGestures = false,
            syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 150,
            syncOffsetMs = -43,
            autoReconnect = true,
        )
        val decoded = SettingsCodec.decodeSettings(SettingsCodec.encodeSettings(settings))
        assertEquals(settings, decoded.value)
        assertEquals(SettingsCodec.CURRENT_VERSION, decoded.schemaVersion)
        assertTrue(decoded.issues.isEmpty())
    }

    @Test
    fun settingsStoredBeforeTheUpstreamAudioOptionsKeepWorkingWithTheirDefaults() {
        // An install upgraded from 1.6.x has no audioSource/audioDup/backgroundAudio/keepActive
        // keys at all; those must default silently rather than be reported as damaged state.
        val stored = """{"schemaVersion":1,"maxSize":1024,"maxFps":90,"stayAwake":true}"""
        val decoded = SettingsCodec.decodeSettings(stored)
        assertTrue(decoded.issues.isEmpty())
        assertEquals(AudioSource.OUTPUT, decoded.value.audioSource)
        assertFalse(decoded.value.audioDup)
        assertFalse(decoded.value.backgroundAudio)
        assertFalse(decoded.value.keepActive)
        assertEquals(1024, decoded.value.maxSize)
        assertTrue(decoded.value.stayAwake)
    }

    @Test
    fun anUnknownAudioSourceFallsBackWithoutDiscardingTheRestOfTheSettings() {
        val decoded = SettingsCodec.decodeSettings(
            """{"schemaVersion":1,"audioSource":"radio-tuner","maxFps":45}""")
        assertEquals(AudioSource.OUTPUT, decoded.value.audioSource)
        assertEquals(45, decoded.value.maxFps)
        assertEquals(listOf("audioSource"), decoded.issues.map { it.field })
    }

    @Test
    fun anAbsentVirtualDisplayMeansMirrorTheScreenAndAnEmptyOneMeansMatchIt() {
        // Null and "" are different requests, so neither may be folded into the other.
        assertNull(SettingsCodec.decodeSettings("""{"schemaVersion":1}""").value.virtualDisplay)
        assertNull(
            SettingsCodec.decodeSettings("""{"schemaVersion":1,"virtualDisplay":null}""").value.virtualDisplay
        )
        assertEquals(
            "",
            SettingsCodec.decodeSettings("""{"schemaVersion":1,"virtualDisplay":""}""").value.virtualDisplay,
        )
    }

    @Test
    fun anUnusableVirtualDisplayFallsBackInsteadOfBreakingEveryLaterConnection() {
        val decoded = SettingsCodec.decodeSettings(
            """{"schemaVersion":1,"virtualDisplay":"1280","maxFps":45}""")
        assertNull(decoded.value.virtualDisplay)
        assertEquals(45, decoded.value.maxFps)
        assertEquals(listOf("virtualDisplay"), decoded.issues.map { it.field })

        val wrongType = SettingsCodec.decodeSettings("""{"schemaVersion":1,"virtualDisplay":720}""")
        assertNull(wrongType.value.virtualDisplay)
        assertEquals(listOf("virtualDisplay"), wrongType.issues.map { it.field })
    }

    @Test
    fun onlyMissingStateFallsBackWithoutAnError() {
        assertEquals(emptyList<SavedDevice>(), SettingsCodec.decodeDevices(null).value)
        assertTrue(SettingsCodec.decodeDevices(null).issues.isEmpty())
        assertEquals(MirrorSettings(), SettingsCodec.decodeSettings(null).value)
        assertTrue(SettingsCodec.decodeSettings(null).issues.isEmpty())
        assertFalse(SettingsCodec.decodeDevices("").issues.isEmpty())
        assertFalse(SettingsCodec.decodeSettings("   ").issues.isEmpty())
    }

    @Test
    fun corruptStateIsReportedWithoutThrowing() {
        for (raw in listOf("not json at all", "{", "null", "[]", "42")) {
            val decoded = SettingsCodec.decodeSettings(raw)
            assertEquals(raw, MirrorSettings(), decoded.value)
            assertFalse(raw, decoded.issues.isEmpty())
        }
        val devices = SettingsCodec.decodeDevices("[{\"name\":")
        assertTrue(devices.value.isEmpty())
        assertFalse(devices.issues.isEmpty())
    }

    @Test
    fun invalidFieldsDoNotDiscardUnrelatedCalibrationOrAudio() {
        val decoded = SettingsCodec.decodeSettings(
            """{"maxSize":"wrong type","videoCodec":"FUTURE","audioCodec":"NEW",
                "touchOffsetXDp":-7,"touchOffsetYDp":19,"audioEnabled":false,"maxFps":90}""",
        )
        assertEquals(MirrorSettings().maxSize, decoded.value.maxSize)
        assertEquals(VideoCodec.H264, decoded.value.videoCodec)
        assertEquals(AudioCodec.OPUS, decoded.value.audioCodec)
        assertEquals(-7, decoded.value.touchOffsetXDp)
        assertEquals(19, decoded.value.touchOffsetYDp)
        assertFalse(decoded.value.audioEnabled)
        assertEquals(90, decoded.value.maxFps)
        assertEquals(setOf("maxSize", "videoCodec", "audioCodec"), decoded.issues.map { it.field }.toSet())
    }

    @Test
    fun boundsAndTypesAreRecoveredIndividually() {
        val decoded = SettingsCodec.decodeSettings(
            """{"maxSize":0,"videoBitRateMbps":2147483647,"maxFps":-1,
                "touchOffsetXDp":121,"touchOffsetYDp":-121,"audioEnabled":"false","stayAwake":true}""",
        )
        assertEquals(MirrorSettings(stayAwake = true), decoded.value)
        assertEquals(6, decoded.issues.size)
        assertTrue(SettingsValidation.settingsErrors(decoded.value).isEmpty())
    }

    @Test
    fun absentPlaybackAndReconnectFieldsNeverOptInDuringMigration() {
        for (raw in listOf("{}", """{"schemaVersion":1}""", """{"maxFps":90}""")) {
            val decoded = SettingsCodec.decodeSettings(raw)
            assertEquals(PlaybackSyncMode.LOW_LATENCY, decoded.value.syncMode)
            assertEquals(0, decoded.value.audioBufferMs)
            assertEquals(0, decoded.value.syncOffsetMs)
            assertFalse(decoded.value.autoReconnect)
            assertTrue(decoded.issues.isEmpty())
        }
        assertEquals(MirrorSettings(maxFps = 90), SettingsCodec.decodeLegacy(mapOf("max_fps" to 90)).value)
    }

    @Test
    fun playbackBufferAndOffsetBoundariesAreInclusiveAndAutomaticIsExplicit() {
        for (buffer in listOf(0, 20, 250)) {
            for (offset in listOf(-200, 0, 200)) {
                val settings = MirrorSettings(audioBufferMs = buffer, syncOffsetMs = offset)
                assertTrue(SettingsValidation.settingsErrors(settings).isEmpty())
                val decoded = SettingsCodec.decodeSettings(SettingsCodec.encodeSettings(settings))
                assertEquals(settings, decoded.value)
                assertTrue(decoded.issues.isEmpty())
            }
        }
    }

    @Test
    fun malformedPlaybackFieldsRecoverIndividuallyWithoutLosingOtherSettings() {
        val invalidValues = mapOf(
            "syncMode" to listOf("\"FUTURE\"", "\"synchronized\"", "0", "true", "null", "{}"),
            "audioBufferMs" to listOf("-1", "1", "19", "251", "2147483648", "\"20\"", "20.5", "null", "false"),
            "syncOffsetMs" to listOf("-201", "201", "-2147483649", "\"0\"", "0.5", "null", "true"),
            "autoReconnect" to listOf("\"true\"", "1", "null", "[]"),
        )
        for ((field, values) in invalidValues) {
            for (value in values) {
                val decoded = SettingsCodec.decodeSettings("""{"$field":$value,"maxFps":90,"showTouches":true}""")
                assertEquals("$field=$value", MirrorSettings(maxFps = 90, showTouches = true), decoded.value)
                assertEquals("$field=$value", listOf(field), decoded.issues.map { it.field })
                assertEquals(Json.parseToJsonElement(value), decoded.source?.get(field))
            }
        }
    }

    @Test
    fun upgradingFromAPre1_7StoreOnlyAddsTheOptionTheUserActuallyChanged() {
        // The real upgrade path: settings written by 1.6.x carry none of the new keys. Editing one
        // of them must patch that single key into the retained source, not rewrite the whole file
        // with defaults for options the user never touched.
        val stored = """{"schemaVersion":1,"maxSize":1280,"maxFps":45,"videoCodec":"H265",
            "stayAwake":true,"captureEdgeGestures":false,"syncMode":"SYNCHRONIZED"}"""
        val decoded = SettingsCodec.decodeSettings(stored)
        assertTrue(decoded.issues.isEmpty())

        val patched = Json.parseToJsonElement(
            SettingsCodec.patchSettings(decoded.source, decoded.value, decoded.value.copy(keepActive = true)),
        ).jsonObject

        assertEquals(JsonPrimitive(true), patched["keepActive"])
        for (untouched in listOf("audioSource", "audioDup", "backgroundAudio")) {
            assertFalse(untouched, untouched in patched)
        }
        for (preserved in listOf("schemaVersion", "maxSize", "maxFps", "videoCodec", "stayAwake",
            "captureEdgeGestures", "syncMode")) {
            assertEquals(preserved, decoded.source?.get(preserved), patched[preserved])
        }
        // Reading it back keeps the edit and still defaults everything else.
        val reloaded = SettingsCodec.decodeSettings(patched.toString())
        assertTrue(reloaded.issues.isEmpty())
        assertTrue(reloaded.value.keepActive)
        assertEquals(AudioSource.OUTPUT, reloaded.value.audioSource)
        assertFalse(reloaded.value.backgroundAudio)
        assertEquals(1280, reloaded.value.maxSize)
    }

    @Test
    fun patchingUnrelatedSettingsRetainsUnknownOrMalformedPlaybackSourceUntilExplicitlyEdited() {
        val decoded = SettingsCodec.decodeSettings(
            """{"schemaVersion":2,"syncMode":"FUTURE","audioBufferMs":9,
                "syncOffsetMs":"later","autoReconnect":"true","future":{"clock":1}}""",
        )
        val updated = decoded.value.copy(maxFps = 90)
        val patched = Json.parseToJsonElement(SettingsCodec.patchSettings(decoded.source, decoded.value, updated)).jsonObject
        for (field in listOf("schemaVersion", "syncMode", "audioBufferMs", "syncOffsetMs", "autoReconnect", "future")) {
            assertEquals(field, decoded.source?.get(field), patched[field])
        }
        assertEquals(JsonPrimitive(90), patched["maxFps"])
        val explicit = Json.parseToJsonElement(
            SettingsCodec.patchSettings(patched, updated, updated.copy(audioBufferMs = 80, autoReconnect = true)),
        ).jsonObject
        assertEquals(JsonPrimitive(80), explicit["audioBufferMs"])
        assertEquals(JsonPrimitive(true), explicit["autoReconnect"])
        assertEquals(patched["syncMode"], explicit["syncMode"])
        assertEquals(patched["syncOffsetMs"], explicit["syncOffsetMs"])
        assertEquals(patched["future"], explicit["future"])
        assertEquals(patched["schemaVersion"], explicit["schemaVersion"])
    }

    @Test
    fun unknownFieldsAndUnsupportedVersionsAreRetainedForPatching() {
        val decoded = SettingsCodec.decodeSettings("""{"schemaVersion":2,"maxSize":800,"future":{"a":1}}""")
        assertEquals(800, decoded.value.maxSize)
        assertEquals(2, decoded.schemaVersion)
        assertNotNull(decoded.source?.get("future"))
        assertTrue(decoded.issues.any { it.field == "schemaVersion" })
    }

    @Test
    fun allLegacyFieldsAreDecodedIncludingWireCodecNames() {
        val decoded = SettingsCodec.decodeLegacy(mapOf(
            "max_size" to 1280, "bitrate_mbps" to 16, "max_fps" to 120,
            "video_codec" to "h265", "audio_enabled" to false, "audio_codec" to "aac",
            "stay_awake" to true, "show_touches" to true, "power_off_on_close" to true,
            "turn_screen_off" to true, "touch_offset_x" to -23, "touch_offset_y" to 31,
            "capture_edge_gestures" to false,
            "sync_mode" to "SYNCHRONIZED", "audio_buffer_ms" to 80, "sync_offset_ms" to -25,
            "auto_reconnect" to true,
        ))
        assertEquals(MirrorSettings(
            maxSize = 1280, videoBitRateMbps = 16, maxFps = 120, videoCodec = VideoCodec.H265,
            audioEnabled = false, audioCodec = AudioCodec.AAC, stayAwake = true, showTouches = true,
            powerOffOnClose = true, turnScreenOff = true, touchOffsetXDp = -23, touchOffsetYDp = 31,
            captureEdgeGestures = false,
            syncMode = PlaybackSyncMode.SYNCHRONIZED, audioBufferMs = 80, syncOffsetMs = -25,
            autoReconnect = true,
        ), decoded.value)
        assertTrue(decoded.issues.isEmpty())
    }

    @Test
    fun invalidDeviceEntriesDoNotHideValidNeighbors() {
        val decoded = SettingsCodec.decodeDevices(
            """[{"id":"a","name":"Good","host":"192.168.1.2","port":5555},
                {"id":"b","name":"Bad port","host":"192.168.1.3","port":65536},
                {"id":"c","name":"Bad host","host":"http://example.com","port":5555},
                {"id":"d","name":"Missing host"},{"id":"a","name":"Duplicate","host":"localhost"},null]""",
        )
        assertEquals(listOf("a"), decoded.value.map { it.id })
        assertEquals(5, decoded.issues.size)
    }

    @Test
    fun endpointValidationDoesNotResolveDnsAndRejectsAmbiguousInput() {
        for (host in listOf("localhost", "phone.local", "192.168.1.1", "::1", "[2001:db8::1]", "fe80::1%wlan0")) {
            assertNull(host, SettingsValidation.endpointError(host, 65535))
        }
        for (host in listOf("", " 192.168.1.1", "a b", "a:5555", "http://a", "a/path", "user@host",
            "256.1.2.3", "192.168.1", "192.168.001.1", "-phone", "a..b", "1:::2", "[localhost]")) {
            assertNotNull(host, SettingsValidation.endpointError(host, 5555))
        }
        assertNotNull(SettingsValidation.endpointError("localhost", 0))
        assertNotNull(SettingsValidation.endpointError("localhost", 65536))
        assertEquals("[::1]:5555", SavedDevice(name = "v6", host = "::1").address)
    }

    @Test fun aDeviceWithoutAnIdentifierIsNotLoadedWithAnInventedOne() {
        // SavedDevice.id defaults to a fresh UUID, so accepting an entry without one would give
        // it a different identity on every load: launcher shortcuts would go stale and an
        // exported profile would come back as a duplicate rather than an update.
        val decoded = SettingsCodec.decodeDevices("""[{"name":"Desk","host":"10.0.0.2","port":5555}]""")
        assertEquals(emptyList<SavedDevice>(), decoded.value)
        assertTrue(decoded.issues.toString(), decoded.issues.any { it.message.contains("identifier") })
    }

    @Test fun aDeviceKeepsTheIdentifierItWasSavedWith() {
        val decoded = SettingsCodec.decodeDevices("""[{"id":"kept","name":"Desk","host":"10.0.0.2","port":5555}]""")
        assertEquals(listOf("kept"), decoded.value.map { it.id })
        assertTrue(decoded.issues.toString(), decoded.issues.isEmpty())
    }

    @Test fun theAudioEncoderChoiceSurvivesASaveAndReload() {
        val settings = MirrorSettings(audioEncoder = "c2.android.opus.encoder")
        val decoded = SettingsCodec.decodeSettings(SettingsCodec.encodeSettings(settings))
        assertTrue(decoded.issues.toString(), decoded.issues.isEmpty())
        assertEquals("c2.android.opus.encoder", decoded.value.audioEncoder)
    }

    @Test fun anUnusableAudioEncoderNameIsReportedRatherThanStored() {
        // The name goes onto the server's command line, so anything that is not an encoder name
        // has to be refused here rather than discovered as a session that will not start.
        for (bad in listOf("\"", "enc oder", "a;b", "\u0000")) {
            val json = """{"audioEncoder":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), JsonPrimitive(bad))}}"""
            val decoded = SettingsCodec.decodeSettings(json)
            assertTrue(bad, decoded.issues.any { it.field == "audioEncoder" })
            assertNull(bad, decoded.value.audioEncoder)
        }
    }

    @Test fun anExplicitNullAudioEncoderMeansLetTheDeviceChoose() {
        val decoded = SettingsCodec.decodeSettings("""{"audioEncoder":null}""")
        assertTrue(decoded.issues.toString(), decoded.issues.isEmpty())
        assertNull(decoded.value.audioEncoder)
    }

    @Test fun theServerChoiceSurvivesASaveAndDefaultsToTheNewest() {
        for (server in ScrcpyServerVersion.entries) {
            val decoded = SettingsCodec.decodeSettings(SettingsCodec.encodeSettings(MirrorSettings(serverVersion = server)))
            assertTrue(decoded.issues.toString(), decoded.issues.isEmpty())
            assertEquals(server, decoded.value.serverVersion)
        }
        // Settings saved before the choice existed connect with the newest server, as they did.
        assertEquals(ScrcpyServerVersion.V5_0, SettingsCodec.decodeSettings("""{"schemaVersion":1,"maxFps":90}""").value.serverVersion)
        // The version as people write it is understood too, as the codecs accept their own names.
        assertEquals(ScrcpyServerVersion.DEFAULT, SettingsCodec.decodeSettings("""{"serverVersion":"4.1"}""").value.serverVersion)
    }

    @Test fun savedFiveZeroNamesAndVersionTokensMigrateToThePatchRelease() {
        for (legacy in listOf("V4_1", "4.1", "V5_0", "5.0", "5.0.1")) {
            val decoded = SettingsCodec.decodeSettings("""{"serverVersion":"$legacy"}""")
            assertTrue(decoded.issues.toString(), decoded.issues.isEmpty())
            assertEquals("5.0.1", decoded.value.serverVersion.version)
        }
    }

    @Test fun anUnknownServerIsReportedAndTheNewestUsed() {
        for (bad in listOf("\"6.0\"", "\"V3_3\"", "41", "null")) {
            val decoded = SettingsCodec.decodeSettings("""{"serverVersion":$bad}""")
            assertTrue(bad, decoded.issues.any { it.field == "serverVersion" })
            assertEquals(bad, ScrcpyServerVersion.DEFAULT, decoded.value.serverVersion)
        }
    }
}