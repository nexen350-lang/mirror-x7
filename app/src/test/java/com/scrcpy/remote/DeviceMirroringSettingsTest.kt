package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.data.calibrationFor
import com.scrcpy.remote.data.effectiveSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.session.ConnectionRequest
import com.scrcpy.remote.session.requiresReconnectFrom
import org.junit.Assert.*
import org.junit.Test

class DeviceMirroringSettingsTest {
    private class MemoryStorage(initial: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = initial.toMutableMap()
        var writes = 0
        var fail = false
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            if (fail) return false
            writes++
            this.values.putAll(values)
            return true
        }
    }
    private val first = SavedDevice("a", "Phone", "192.168.1.2",
        isFavorite = true, portraitCalibration = TouchCalibration(3, -40),
        landscapeCalibration = TouchCalibration(-10, 2))
    private val second = SavedDevice("b", "Tablet", "192.168.1.3")
    private val globals = MirrorSettings(maxSize = 1280, maxFps = 45, audioEnabled = false,
        touchOffsetXDp = -4, touchOffsetYDp = -12)
    private val custom = DeviceMirroringSettings(maxSize = 800, videoBitRateMbps = 4, maxFps = 30,
        videoCodec = VideoCodec.H265, audioEnabled = true, audioCodec = AudioCodec.AAC,
        stayAwake = true, showTouches = true, powerOffOnClose = true, turnScreenOff = true,
        captureEdgeGestures = false, syncMode = PlaybackSyncMode.SYNCHRONIZED,
        audioBufferMs = 150, syncOffsetMs = -31, autoReconnect = true)

    @Test fun oldDevicesInheritGlobalsWithoutMaterializingACustomProfile() {
        val decoded = SettingsCodec.decodeDevices("""[{"id":"a","name":"Phone","host":"192.168.1.2"}]""")
        assertTrue(decoded.issues.isEmpty())
        assertNull(decoded.value.single().mirroringSettings)
        assertEquals(globals, decoded.value.single().effectiveSettings(globals))
        assertEquals(globals, (null as SavedDevice?).effectiveSettings(globals))
        val storage = MemoryStorage(mapOf("devices" to SettingsCodec.encodeDevices(listOf(first))))
        val repo = DeviceRepository(storage)
        val before = storage.writes
        assertTrue(repo.saveDeviceSettings(first.id, null, null))
        assertEquals(before, storage.writes)
        assertNull(repo.getDevices().single().mirroringSettings)
    }

    @Test fun customizedProfileSurvivesRestartAndDoesNotChangeOtherDevices() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        repo.saveSettings(globals)
        repo.saveDevice(first)
        repo.saveDevice(second)
        assertTrue(repo.saveDeviceSettings(first.id, null, custom))
        val restored = DeviceRepository(storage)
        assertEquals(custom.applyTo(globals), restored.connectionSettings(first.id))
        assertEquals(globals, restored.connectionSettings(second.id))
        assertEquals(globals, restored.connectionSettings(null))
        assertEquals(custom, restored.getDevices().first().mirroringSettings)
        assertEquals(first.portraitCalibration, restored.getDevices().first().portraitCalibration)
        assertEquals(first.landscapeCalibration, restored.getDevices().first().landscapeCalibration)
        assertTrue(restored.getDevices().first().isFavorite)
    }

    @Test fun newFieldsInheritOnlyWhenTheWholeProfileIsNull() {
        val tuned = globals.copy(syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 250, syncOffsetMs = 200, autoReconnect = true)
        assertEquals(tuned, first.effectiveSettings(tuned))
        val explicitDefaults = first.copy(mirroringSettings = DeviceMirroringSettings())
        assertEquals(DeviceMirroringSettings(), DeviceMirroringSettings.from(explicitDefaults.effectiveSettings(tuned)))
        assertEquals(globals.touchOffsetXDp, explicitDefaults.effectiveSettings(tuned).touchOffsetXDp)
        val oldProfile = SettingsCodec.decodeDevices(
            """[{"id":"a","name":"Phone","host":"localhost","mirroringSettings":{"maxFps":30}}]""",
        )
        assertTrue(oldProfile.issues.isEmpty())
        assertEquals(DeviceMirroringSettings(maxFps = 30), oldProfile.value.single().mirroringSettings)
        assertEquals(PlaybackSyncMode.LOW_LATENCY, oldProfile.value.single().effectiveSettings(tuned).syncMode)
        assertEquals(0, oldProfile.value.single().effectiveSettings(tuned).audioBufferMs)
        assertEquals(0, oldProfile.value.single().effectiveSettings(tuned).syncOffsetMs)
        assertFalse(oldProfile.value.single().effectiveSettings(tuned).autoReconnect)
    }

    @Test fun globalChangesAffectOnlyInheritedStreamingValuesAndCalibrationDefaults() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveSettings(globals)
        repo.saveDevice(first.copy(mirroringSettings = custom))
        repo.saveDevice(second)
        repo.updateSettings { it.copy(maxFps = 90, touchOffsetYDp = 7) }
        assertEquals(30, repo.connectionSettings(first.id).maxFps)
        assertEquals(90, repo.connectionSettings(second.id).maxFps)
        assertEquals(7, repo.connectionSettings(first.id).touchOffsetYDp)
        val device = repo.getDevices().first()
        assertEquals(TouchCalibration(3, -40), device.calibrationFor(false, repo.getSettings()))
    }

    @Test fun resetRemovesOnlyMirroringProfileAndUsesCurrentGlobals() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveSettings(globals)
        repo.saveDevice(first.copy(mirroringSettings = custom))
        assertTrue(repo.saveDeviceSettings(first.id, custom, null))
        assertEquals(first, repo.getDevices().single())
        assertEquals(globals, repo.connectionSettings(first.id))
        repo.updateSettings { it.copy(maxFps = 100) }
        assertEquals(100, repo.connectionSettings(first.id).maxFps)
    }

    @Test fun staleEndpointEditorPreservesMirroringProfile() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first)
        val stale = repo.getDevices().single()
        repo.saveDeviceSettings(first.id, null, custom)
        repo.saveDevice(stale.copy(name = "Renamed", host = "10.0.0.2", port = 30123))
        assertEquals(custom, repo.getDevices().single().mirroringSettings)
        assertEquals("10.0.0.2", repo.getDevices().single().host)
        assertTrue(repo.getDevices().single().isFavorite)
    }

    @Test fun concurrentCustomEditsMergeOnlyFieldsChangedByEachEditor() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first.copy(mirroringSettings = custom))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(maxFps = 60)))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(videoBitRateMbps = 16)))
        repo.setCalibration(first.id, false, TouchCalibration(9, -55))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(audioEnabled = false)))
        val saved = repo.getDevices().single()
        assertEquals(custom.copy(maxFps = 60, videoBitRateMbps = 16, audioEnabled = false), saved.mirroringSettings)
        assertEquals(TouchCalibration(9, -55), saved.portraitCalibration)
    }

    @Test fun staleCustomPlaybackEditsMergeEachNewFieldWithoutOverwritingTheirNeighbors() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first.copy(mirroringSettings = custom))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(maxFps = 60)))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(syncMode = PlaybackSyncMode.LOW_LATENCY)))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(audioBufferMs = 0)))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(syncOffsetMs = 125)))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(autoReconnect = false)))
        val saved = repo.getDevices().single()
        assertEquals(custom.copy(maxFps = 60, syncMode = PlaybackSyncMode.LOW_LATENCY,
            audioBufferMs = 0, syncOffsetMs = 125, autoReconnect = false), saved.mirroringSettings)
        assertEquals(first.portraitCalibration, saved.portraitCalibration)
        assertEquals(first.landscapeCalibration, saved.landscapeCalibration)
    }

    @Test fun staleGlobalPlaybackEditsMergeEachNewFieldAndPreserveLiveCalibration() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        assertTrue(repo.saveSettings(globals))
        assertTrue(repo.updateSettings { it.copy(maxFps = 90, touchOffsetYDp = 7) })
        assertTrue(repo.mergeSettings(globals, globals.copy(syncMode = PlaybackSyncMode.SYNCHRONIZED)))
        assertTrue(repo.mergeSettings(globals, globals.copy(audioBufferMs = 80)))
        assertTrue(repo.mergeSettings(globals, globals.copy(syncOffsetMs = -200)))
        assertTrue(repo.mergeSettings(globals, globals.copy(autoReconnect = true)))
        assertTrue(repo.mergeSettings(globals, globals.copy(audioCodec = AudioCodec.AAC)))
        val expected = globals.copy(maxFps = 90, touchOffsetYDp = 7, audioCodec = AudioCodec.AAC,
            syncMode = PlaybackSyncMode.SYNCHRONIZED, audioBufferMs = 80, syncOffsetMs = -200,
            autoReconnect = true)
        assertEquals(expected, repo.getSettings())
        assertEquals(expected, DeviceRepository(storage).getSettings())
    }

    @Test fun staleEditorCannotReenableProfileAfterAnotherEditorResetIt() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first.copy(mirroringSettings = custom))
        assertTrue(repo.saveDeviceSettings(first.id, custom, null))
        assertFalse(repo.saveDeviceSettings(first.id, custom, custom.copy(maxFps = 60)))
        assertNull(repo.getDevices().single().mirroringSettings)
        assertTrue(repo.errors.value.isNotEmpty())
    }

    @Test fun staleResetCannotEraseAnInterveningCustomEdit() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first.copy(mirroringSettings = custom))
        assertTrue(repo.saveDeviceSettings(first.id, custom, custom.copy(maxFps = 60)))
        assertFalse(repo.saveDeviceSettings(first.id, custom, null))
        assertEquals(60, repo.connectionSettings(first.id).maxFps)
    }

    @Test fun failedWritesAndInvalidSettingsDoNotChangeVisibleState() {
        val storage = MemoryStorage()
        val repo = DeviceRepository(storage)
        repo.saveDevice(first)
        assertFalse(repo.saveDeviceSettings(first.id, null, custom.copy(maxFps = 999)))
        storage.fail = true
        assertFalse(repo.saveDeviceSettings(first.id, null, custom))
        assertNull(repo.getDevices().single().mirroringSettings)
        assertTrue(repo.errors.value.isNotEmpty())
    }

    @Test fun invalidPlaybackRangesRejectSavingProfilesAndConnectionSnapshots() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first)
        for (invalid in listOf(custom.copy(audioBufferMs = 1), custom.copy(audioBufferMs = 251),
            custom.copy(syncOffsetMs = -201), custom.copy(syncOffsetMs = 201))) {
            assertFalse(repo.saveDeviceSettings(first.id, null, invalid))
            assertNull(repo.getDevices().single().mirroringSettings)
            val request = ConnectionRequest(first.host, first.port) { invalid.applyTo(globals) }
            assertThrows(IllegalArgumentException::class.java) { request.snapshot() }
        }
    }

    @Test fun aDeviceFieldFromANewerVersionIsNotQuietlyDroppedByAnUnrelatedEdit() {
        // The same promise as the profile below, one level up. A newer build can add fields to the
        // device itself — or to a calibration — and those are decoded with unknown keys ignored,
        // so an ordinary edit such as toggling a favourite rewrote the list without them and no
        // recovery copy was kept. Only the mirroringSettings object was being checked.
        val raw = """[{"id":"a","name":"Phone","host":"192.168.1.2","port":5555,
            "portraitCalibration":{"xDp":3,"yDp":-4,"scale":1.25},"wakeOnConnect":true}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repo = DeviceRepository(storage)

        assertEquals("Phone", repo.getDevices().single().name)
        assertTrue(
            repo.errors.value.map { "${it.field}: ${it.message}" }.toString(),
            repo.errors.value.any { it.field == "[0]" && it.message.contains("wakeOnConnect") },
        )
        assertTrue(
            repo.errors.value.map { "${it.field}: ${it.message}" }.toString(),
            repo.errors.value.any { it.field == "[0].portraitCalibration" && it.message.contains("scale") },
        )

        assertTrue(repo.setFavorite("a", true))
        assertTrue("The original device JSON was not retained", storage.values.values.any { it == raw })
    }

    @Test fun aProfileFieldFromANewerVersionIsNotQuietlyDroppedByAnUnrelatedEdit() {
        // Re-encoding a device profile keeps only the fields this build knows, and an ordinary
        // edit elsewhere then writes that loss back for good. Global settings deliberately retain
        // theirs for forward compatibility; a device profile has to be recoverable the same way,
        // so the unreadable field is reported and the original JSON kept.
        val raw = """[{"id":"a","name":"Phone","host":"192.168.1.2",
            "mirroringSettings":{"maxFps":30,"futureOption":37}}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repo = DeviceRepository(storage)

        assertEquals(30, repo.getDevices().single().mirroringSettings?.maxFps)
        assertTrue(
            repo.errors.value.map { "${it.field}: ${it.message}" }.toString(),
            repo.errors.value.any { it.field == "[0].mirroringSettings" && it.message.contains("futureOption") },
        )

        // An edit that has nothing to do with the profile must not overwrite the original
        // without leaving the unreadable version behind to recover from.
        assertTrue(repo.setFavorite("a", true))
        assertTrue("The original device JSON was not retained", storage.values.values.any { it == raw })
    }

    @Test fun aNestedCameraFieldFromANewerVersionRetainsOriginalBytesOnFavoriteChange() {
        val raw = """[{"id":"a","name":"Phone","host":"192.168.1.2",
            "mirroringSettings":{"camera":{"id":"0","futureOption":{"mode":"steady","levels":[1,2]}}}}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repo = DeviceRepository(storage)
        assertEquals("0", repo.getDevices().single().mirroringSettings?.camera?.id)
        assertEquals(raw, storage.values["devices"])
        assertEquals(0, storage.writes)

        assertTrue(repo.setFavorite("a", true))

        assertEquals("An unrelated edit must retain the complete original camera data",
            raw, storage.values["devices.recovery.1"])
        assertTrue(repo.errors.value.toString(), repo.errors.value.any {
            it.field?.startsWith("[0].mirroringSettings") == true && it.message.contains("futureOption")
        })
        val reloaded = DeviceRepository(storage).getDevices().single()
        assertTrue(reloaded.isFavorite)
        assertEquals("0", reloaded.mirroringSettings?.camera?.id)
    }

    @Test fun malformedProfileDoesNotLoseTheDeviceCalibrationOrSource() {
        val raw = """[{"id":"a","name":"Phone","host":"192.168.1.2","isFavorite":true,
            "portraitCalibration":{"xDp":3,"yDp":-40},
            "mirroringSettings":{"maxFps":30,"audioEnabled":false,"videoCodec":"FUTURE",
                "syncMode":"FUTURE","audioBufferMs":1,"syncOffsetMs":201,"autoReconnect":"true"}}]"""
        val storage = MemoryStorage(mapOf("devices" to raw))
        val repo = DeviceRepository(storage)
        assertEquals(raw, storage.values["devices"])
        assertEquals(0, storage.writes)
        val recovered = repo.getDevices().single()
        assertEquals(30, recovered.mirroringSettings?.maxFps)
        assertEquals(false, recovered.mirroringSettings?.audioEnabled)
        assertEquals(PlaybackSyncMode.LOW_LATENCY, recovered.mirroringSettings?.syncMode)
        assertEquals(0, recovered.mirroringSettings?.audioBufferMs)
        assertEquals(0, recovered.mirroringSettings?.syncOffsetMs)
        assertEquals(false, recovered.mirroringSettings?.autoReconnect)
        assertEquals(first.portraitCalibration, recovered.portraitCalibration)
        assertTrue(repo.errors.value.any { it.field?.contains("videoCodec") == true })
        for (field in listOf("syncMode", "audioBufferMs", "syncOffsetMs", "autoReconnect")) {
            assertTrue(field, repo.errors.value.any { it.field == "[0].mirroringSettings.$field" })
        }
        assertTrue(repo.setFavorite(first.id, false))
        assertTrue(storage.values.values.contains(raw))
    }

    @Test fun wrongProfileTypeFallsBackToGlobalWithoutHidingTheDevice() {
        val decoded = SettingsCodec.decodeDevices(
            """[{"id":"a","name":"Phone","host":"192.168.1.2","mirroringSettings":"bad"}]""")
        assertEquals("a", decoded.value.single().id)
        assertNull(decoded.value.single().mirroringSettings)
        assertEquals(globals, decoded.value.single().effectiveSettings(globals))
        assertTrue(decoded.issues.isNotEmpty())
    }

    @Test fun reconnectUsesLatestProfileNotInitialSnapshot() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveSettings(globals)
        repo.saveDevice(first)
        val request = ConnectionRequest(first.host, first.port) { repo.connectionSettings(first.id) }
        val initial = request.snapshot()
        assertEquals(globals, initial)
        repo.saveDeviceSettings(first.id, null, custom)
        assertEquals(custom.applyTo(globals), request.snapshot())
        assertEquals(globals, initial)
        repo.saveDeviceSettings(first.id, custom, null)
        repo.updateSettings { it.copy(maxSize = 2560) }
        assertEquals(2560, request.snapshot().maxSize)
    }

    @Test fun audioRetryDoesNotPermanentlyOverrideDisabledAudioPreference() {
        val settings = globals.copy(syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 80, syncOffsetMs = 37, autoReconnect = true)
        val request = ConnectionRequest(first.host, first.port) { settings }
        assertEquals(settings.copy(audioEnabled = true), request.snapshot(enableAudio = true))
        assertEquals(settings, request.snapshot())
    }

    @Test fun localEdgeAndCalibrationChangesDoNotRequireAStreamRestart() {
        assertFalse(globals.copy(captureEdgeGestures = !globals.captureEdgeGestures).requiresReconnectFrom(globals))
        assertFalse(globals.copy(touchOffsetYDp = 77).requiresReconnectFrom(globals))
        // Background playback is a client-side lifecycle choice; restarting the stream for it
        // would needlessly interrupt the very session the user asked to keep alive.
        assertFalse(globals.copy(backgroundAudio = true).requiresReconnectFrom(globals))
        for (changed in listOf(globals.copy(audioEnabled = true), globals.copy(maxSize = 800),
            globals.copy(maxFps = 60), globals.copy(showTouches = true), globals.copy(turnScreenOff = true),
            globals.copy(syncMode = PlaybackSyncMode.SYNCHRONIZED), globals.copy(audioBufferMs = 20),
            globals.copy(syncOffsetMs = -200), globals.copy(syncOffsetMs = 200),
            globals.copy(audioSource = AudioSource.PLAYBACK), globals.copy(audioDup = true),
            globals.copy(keepActive = true),
            globals.copy(captureOrientation = CaptureOrientation.LOCK_CURRENT),
            // A chosen encoder is picked when the server starts, so it needs a new session.
            globals.copy(videoEncoder = "c2.qti.avc.encoder"),
            // A virtual display only exists for as long as the server session that created it.
            globals.copy(virtualDisplay = ""),
            globals.copy(virtualDisplay = "1280x720"),
            globals.copy(autoReconnect = true))) {
            assertTrue(changed.requiresReconnectFrom(globals))
        }
    }

    @Test fun deletedProfileIsNotResurrectedBySavingOrReconnecting() {
        val repo = DeviceRepository(MemoryStorage())
        repo.saveDevice(first)
        val request = ConnectionRequest(first.host, first.port) { repo.connectionSettings(first.id) }
        repo.deleteDevice(first.id)
        assertFalse(repo.saveDeviceSettings(first.id, null, custom))
        assertThrows(IllegalArgumentException::class.java) { request.snapshot() }
        assertTrue(repo.getDevices().isEmpty())
    }

    @Test fun effectiveValuesReachServerArguments() {
        val args = first.copy(mirroringSettings = custom).effectiveSettings(globals).toOptions().toServerArgs()
        assertTrue(args.containsAll(listOf("max_size=800", "video_bit_rate=4000000", "max_fps=30",
            "video_codec=h265", "audio_codec=aac", "stay_awake=true", "show_touches=true",
            "power_off_on_close=true")))
        assertFalse(args.contains("audio=false"))
    }

    @Test fun upstreamAudioAndActivityOptionsReachServerArguments() {
        val profile = custom.copy(audioSource = AudioSource.PLAYBACK, audioDup = true, keepActive = true)
        val args = first.copy(mirroringSettings = profile).effectiveSettings(globals).toOptions().toServerArgs()
        assertTrue(args.containsAll(listOf("audio_source=playback", "audio_dup=true", "keep_active=true")))
    }

    @Test fun backgroundPlaybackNeverReachesTheServer() {
        // It only changes what this client does while hidden; the device has no equivalent option.
        val base = globals.toOptions().toServerArgs().filterNot { it.startsWith("scid=") }
        val background = globals.copy(backgroundAudio = true).toOptions().toServerArgs()
            .filterNot { it.startsWith("scid=") }
        assertEquals(base, background)
    }

    @Test fun localPlaybackAndRecoveryOptionsDoNotChangeScrcpyServerArguments() {
        val tuned = globals.copy(syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 250, syncOffsetMs = -200, autoReconnect = true)
        // Each connection gets a new scrcpy session ID, independent of its playback settings.
        val originalArgs = globals.toOptions().toServerArgs().filterNot { it.startsWith("scid=") }
        val tunedArgs = tuned.toOptions().toServerArgs().filterNot { it.startsWith("scid=") }
        assertEquals(originalArgs, tunedArgs)
    }

    @Test fun aStaleEncoderEditKeepsACodecAndItsEncoderTogether() {
        val base = custom.copy(videoCodec = VideoCodec.H264, videoEncoder = "c2.android.avc.encoder",
            audioCodec = AudioCodec.AAC, audioEncoder = "c2.android.aac.encoder")
        // Another editor switched to H.265, dropping the H.264 encoder.
        val current = base.copy(videoCodec = VideoCodec.H265, videoEncoder = null)
        val merged = current.mergeChanges(base, base.copy(videoEncoder = "c2.other.avc.encoder"))
        assertEquals(VideoCodec.H264 to "c2.other.avc.encoder", merged.videoCodec to merged.videoEncoder)

        val audio = current.copy(audioCodec = AudioCodec.OPUS, audioEncoder = null)
            .mergeChanges(base, base.copy(audioEncoder = "c2.other.aac.encoder"))
        assertEquals(AudioCodec.AAC to "c2.other.aac.encoder", audio.audioCodec to audio.audioEncoder)
        // Fields outside an edited group still follow the latest saved profile.
        assertEquals(VideoCodec.H265 to null, audio.videoCodec to audio.videoEncoder)
    }

    @Test fun theCaptureSourceIsMergedAsOneChoice() {
        val base = custom.copy(displayId = 0, virtualDisplay = null, camera = null)
        val current = base.copy(camera = CameraCapture(id = "1"))
        val merged = current.mergeChanges(base, base.copy(virtualDisplay = "1280x720"))
        assertNull("A virtual display and a camera cannot both be the source", merged.camera)
        assertEquals("1280x720", merged.virtualDisplay)
        assertEquals(CameraCapture(id = "1"), current.mergeChanges(base, base.copy(maxFps = 24)).camera)
    }

    @Test fun deviceProfilesAlwaysUseTheCurrentServerAndRetainUnrelatedEdits() {
        // The compatibility server field has only the current release as a possible value.
        val older = custom.copy(serverVersion = ScrcpyServerVersion.DEFAULT)
        assertEquals(ScrcpyServerVersion.DEFAULT, older.applyTo(globals).serverVersion)
        assertEquals(ScrcpyServerVersion.DEFAULT,
            DeviceMirroringSettings.from(globals.copy(serverVersion = ScrcpyServerVersion.DEFAULT)).serverVersion)
        assertEquals("5.0.1", first.copy(mirroringSettings = older).effectiveSettings(globals).toOptions().toServerArgs().first())
        // An inheriting device follows the global choice.
        assertEquals(ScrcpyServerVersion.DEFAULT,
            second.effectiveSettings(globals.copy(serverVersion = ScrcpyServerVersion.DEFAULT)).serverVersion)
        // An editor that changed only the server does not undo another editor's fps, and the reverse.
        val merged = custom.copy(maxFps = 24).mergeChanges(custom, older)
        assertEquals(ScrcpyServerVersion.DEFAULT to 24, merged.serverVersion to merged.maxFps)
        assertEquals(ScrcpyServerVersion.DEFAULT, older.mergeChanges(custom, custom.copy(maxFps = 24)).serverVersion)
    }
}
