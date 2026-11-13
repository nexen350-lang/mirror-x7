package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PersistenceIssue
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.VideoCodec
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRepositoryTest {
    private class MemoryStorage(initial: Map<String, Any?> = emptyMap()) : PreferenceStorage {
        val values = initial.toMutableMap()
        var writes = 0
        var failWrites = false
        var failReads = false
        override fun read(): Map<String, *> {
            if (failReads) throw IOException("unavailable")
            return values.toMap()
        }
        override fun write(values: Map<String, Any?>): Boolean {
            writes++
            if (failWrites) return false
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            return true
        }
    }

    @Test
    fun legacyMigrationPreservesEveryPreferenceAndRunsOnlyOnce() {
        val legacy = mapOf(
            "max_size" to 1280, "bitrate_mbps" to 16, "max_fps" to 90,
            "video_codec" to "H265", "audio_enabled" to false, "audio_codec" to "AAC",
            "stay_awake" to true, "show_touches" to true, "power_off_on_close" to true,
            "turn_screen_off" to true, "touch_offset_x" to -31, "touch_offset_y" to 27,
            "capture_edge_gestures" to false,
        )
        val devices = """[{"id":"a","name":"Pixel","host":"192.168.1.2","port":5555}]"""
        val storage = MemoryStorage(legacy + ("devices" to devices))
        val repository = DeviceRepository(storage)
        assertEquals(MirrorSettings(
            maxSize = 1280, videoBitRateMbps = 16, maxFps = 90, videoCodec = VideoCodec.H265,
            audioEnabled = false, audioCodec = AudioCodec.AAC, stayAwake = true, showTouches = true,
            powerOffOnClose = true, turnScreenOff = true, touchOffsetXDp = -31, touchOffsetYDp = 27,
            captureEdgeGestures = false,
        ), repository.settings.value)
        assertTrue(repository.errors.value.isEmpty())
        assertEquals(devices, storage.values["devices"])
        legacy.forEach { (key, value) -> assertEquals(value, storage.values[key]) }
        assertEquals(SettingsCodec.CURRENT_VERSION, SettingsCodec.decodeSettings(storage.values["settings"] as String).schemaVersion)
        assertEquals(repository.getSettings(), DeviceRepository(storage).getSettings())
        assertEquals(1, storage.writes)
    }

    @Test
    fun existingJsonWinsOverStaleLegacyPreferences() {
        val storage = MemoryStorage(mapOf("touch_offset_y" to -9, "settings" to """{"touchOffsetYDp":17,"audioEnabled":false}"""))
        val repository = DeviceRepository(storage)
        assertEquals(17, repository.getSettings().touchOffsetYDp)
        assertFalse(repository.getSettings().audioEnabled)
        assertTrue(repository.errors.value.isEmpty())
        assertEquals(1, storage.writes)
    }

    @Test
    fun malformedAndWrongTypedSourcesAreNotWrittenAtStartup() {
        for (raw in listOf<Any>("not json", "   ", """{"videoCodec":"NEW","touchOffsetYDp":-21,"audioEnabled":false}""", 7)) {
            val storage = MemoryStorage(mapOf("settings" to raw, "devices" to "[broken"))
            val logged = mutableListOf<PersistenceIssue>()
            val repository = DeviceRepository(storage, logged::add)
            assertEquals(raw, storage.values["settings"])
            assertEquals("[broken", storage.values["devices"])
            assertEquals(0, storage.writes)
            assertTrue(repository.errors.value.isNotEmpty())
            assertEquals(repository.errors.value, logged)
            assertTrue(repository.devices.value.isEmpty())
        }
    }

    @Test
    fun malformedLegacyIsPreservedAndValidFieldsStillLoad() {
        val storage = MemoryStorage(mapOf(
            "video_codec" to "UNKNOWN", "audio_codec" to "aac", "audio_enabled" to false,
            "touch_offset_x" to -17, "touch_offset_y" to "bad", "capture_edge_gestures" to false,
        ))
        val repository = DeviceRepository(storage)
        assertEquals(-17, repository.getSettings().touchOffsetXDp)
        assertEquals(AudioCodec.AAC, repository.getSettings().audioCodec)
        assertFalse(repository.getSettings().audioEnabled)
        assertFalse(repository.getSettings().captureEdgeGestures)
        assertEquals(0, storage.writes)
        assertEquals("bad", storage.values["touch_offset_y"])
        assertEquals(2, repository.errors.value.size)
        assertTrue(repository.updateSettings { it.copy(stayAwake = true) })
        val reloaded = DeviceRepository(storage)
        assertTrue(reloaded.getSettings().stayAwake)
        assertTrue(reloaded.errors.value.any { it.field == "videoCodec" })
        assertEquals("bad", storage.values["touch_offset_y"])
    }

    @Test
    fun savingAnUnchangedDialogDoesNotReplaceACorruptSource() {
        val storage = MemoryStorage(mapOf("settings" to "broken"))
        val repository = DeviceRepository(storage)
        val baseline = repository.getSettings()
        assertTrue(repository.mergeSettings(baseline, baseline))
        assertEquals("broken", storage.values["settings"])
        assertEquals(0, storage.writes)
        assertTrue(repository.errors.value.isNotEmpty())
    }

    @Test
    fun interleavedDialogAndLiveCalibrationMergeOnlyEditedFields() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        val dialogBaseline = repository.getSettings()
        assertTrue(repository.updateSettings { it.copy(touchOffsetXDp = -12, touchOffsetYDp = 21) })
        assertTrue(repository.updateSettings { it.copy(captureEdgeGestures = false) })
        assertTrue(repository.mergeSettings(dialogBaseline, dialogBaseline.copy(audioEnabled = false, maxFps = 90)))
        val settings = repository.getSettings()
        assertEquals(-12, settings.touchOffsetXDp)
        assertEquals(21, settings.touchOffsetYDp)
        assertFalse(settings.captureEdgeGestures)
        assertFalse(settings.audioEnabled)
        assertEquals(90, settings.maxFps)
        assertEquals(settings, DeviceRepository(storage).getSettings())
    }

    @Test
    fun aDialogCanIntentionallyEditOneCalibrationAxisWithoutClobberingTheOther() {
        val repository = DeviceRepository(MemoryStorage())
        val baseline = repository.getSettings()
        repository.updateSettings { it.copy(touchOffsetXDp = 7, touchOffsetYDp = 9, audioEnabled = false) }
        repository.mergeSettings(baseline, baseline.copy(touchOffsetXDp = -4))
        assertEquals(-4, repository.getSettings().touchOffsetXDp)
        assertEquals(9, repository.getSettings().touchOffsetYDp)
        assertFalse(repository.getSettings().audioEnabled)
    }

    @Test
    fun updatesAreAtomicAcrossThreads() {
        val repository = DeviceRepository(MemoryStorage())
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..80).map {
                executor.submit<Boolean> {
                    start.await()
                    repository.updateSettings { current -> current.copy(touchOffsetXDp = current.touchOffsetXDp + 1) }
                }
            }
            start.countDown()
            jobs.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(80, repository.getSettings().touchOffsetXDp)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun flowsPublishCommittedUpdatesDirectly() = runBlocking {
        val repository = DeviceRepository(MemoryStorage())
        val seen = mutableListOf<MirrorSettings>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.settings.take(2).toList(seen)
        }
        repository.updateSettings { it.copy(touchOffsetYDp = 7) }
        observer.join()
        assertEquals(listOf(MirrorSettings(), MirrorSettings(touchOffsetYDp = 7)), seen)
        val device = SavedDevice(id = "a", name = "Phone", host = "phone.local")
        assertTrue(repository.saveDevice(device))
        assertEquals(listOf(device), repository.devices.value)
        assertTrue(repository.deleteDevice(device.id))
        assertTrue(repository.devices.value.isEmpty())
    }

    @Test
    fun explicitEditsArchiveCorruptionBeforeReplacingItAndNeverReplaceOlderBackups() {
        val storage = MemoryStorage(mapOf(
            "settings" to "broken", "settings.recovery.1" to "older original",
            "devices" to "[broken",
        ))
        val repository = DeviceRepository(storage)
        assertTrue(repository.updateSettings { it.copy(touchOffsetYDp = 9) })
        assertEquals("broken", storage.values["settings.recovery.2"])
        assertEquals("older original", storage.values["settings.recovery.1"])
        assertTrue(repository.errors.value.any { it.recoveryKey == "settings.recovery.2" })
        assertEquals(9, DeviceRepository(storage).getSettings().touchOffsetYDp)
        assertTrue(repository.saveDevice(SavedDevice(name = "Phone", host = "phone.local")))
        assertEquals("[broken", storage.values["devices.recovery.1"])
    }

    @Test
    fun failedRecoveryWriteDoesNotReplaceTheOriginalOrPublishItsReplacement() {
        val storage = MemoryStorage(mapOf("settings" to 123, "devices" to "[broken"))
        val repository = DeviceRepository(storage)
        storage.failWrites = true
        assertFalse(repository.updateSettings { it.copy(touchOffsetYDp = -19) })
        assertEquals(123, storage.values["settings"])
        assertFalse(storage.values.containsKey("settings.recovery.1"))
        assertEquals(0, repository.getSettings().touchOffsetYDp)
        assertTrue(repository.deleteDevice("not-present"))
        assertEquals("[broken", storage.values["devices"])
        storage.failWrites = false
        assertTrue(repository.updateSettings { it.copy(touchOffsetYDp = -19) })
        assertEquals(123, storage.values["settings.recovery.1"])
        assertEquals(-19, repository.getSettings().touchOffsetYDp)
    }

    @Test
    fun unrelatedEditsPreserveUnknownAndInvalidFieldsIncludingFutureSchemaVersions() {
        val raw = """{"schemaVersion":99,"videoCodec":"FUTURE","future":{"flag":true},"touchOffsetYDp":-17}"""
        val storage = MemoryStorage(mapOf("settings" to raw))
        val repository = DeviceRepository(storage)
        assertEquals(0, storage.writes)
        assertTrue(repository.updateSettings { it.copy(audioEnabled = false) })
        val decoded = SettingsCodec.decodeSettings(storage.values["settings"] as String)
        assertEquals(99, decoded.schemaVersion)
        assertEquals(-17, decoded.value.touchOffsetYDp)
        assertFalse(decoded.value.audioEnabled)
        assertEquals("\"FUTURE\"", decoded.source?.get("videoCodec").toString())
        assertNotNull(decoded.source?.get("future"))
        assertEquals(raw, storage.values["settings.recovery.1"])
        assertTrue(decoded.issues.any { it.field == "videoCodec" })
        assertTrue(repository.updateSettings { it.copy(touchOffsetXDp = 1) })
        assertFalse(storage.values.containsKey("settings.recovery.2"))
    }

    @Test
    fun anIdenticalCopyIsNotArchivedTwiceOverAndOver() {
        // Damaged data that cannot be re-encoded stays damaged, so every later edit archived the
        // same bytes again under the next free number. Each copy also brought a history record,
        // and each was reported as something to look at, so a single unreadable value turned into
        // a growing pile of identical copies and a list of warnings the user could not clear.
        val storage = MemoryStorage(mapOf(
            "settings" to "broken",
            "settings.recovery.1" to "broken",
        ))
        val repository = DeviceRepository(storage)
        assertTrue(repository.updateSettings { it.copy(touchOffsetYDp = 4) })
        assertEquals("broken", storage.values["settings.recovery.1"])
        assertFalse("The same bytes must not be archived a second time",
            storage.values.containsKey("settings.recovery.2"))
        // The copy already there is the one the user is pointed at, so it can still be found.
        assertTrue(repository.errors.value.any { it.recoveryKey == "settings.recovery.1" })
        assertEquals(4, DeviceRepository(storage).getSettings().touchOffsetYDp)
    }

    @Test
    fun writingBackAnUnchangedValueArchivesNothing() {
        // A write that changes nothing is not a moment where anything can be lost, so there is
        // nothing to keep a copy of.
        val raw = """{"schemaVersion":99,"future":{"flag":true},"touchOffsetYDp":-17}"""
        val storage = MemoryStorage(mapOf("settings" to raw))
        val repository = DeviceRepository(storage)
        assertTrue(repository.updateSettings { it })
        assertFalse(storage.values.containsKey("settings.recovery.1"))
        assertEquals(raw, storage.values["settings"])
    }

    @Test
    fun rejectedValuesAndFailedCommitsNeverPublishChanges() {
        val storage = MemoryStorage(mapOf("settings" to SettingsCodec.encodeSettings(MirrorSettings(touchOffsetYDp = -7))))
        val repository = DeviceRepository(storage)
        val original = storage.values.toMap()
        assertFalse(repository.updateSettings { it.copy(videoBitRateMbps = Int.MAX_VALUE) })
        assertFalse(repository.saveDevice(SavedDevice(name = "bad", host = "host/path")))
        assertFalse(repository.saveDevice(SavedDevice(name = "bad", host = "host", port = -1)))
        assertEquals(original, storage.values)
        assertEquals(0, storage.writes)
        storage.failWrites = true
        assertFalse(repository.updateSettings { it.copy(touchOffsetYDp = 15) })
        assertEquals(-7, repository.getSettings().touchOffsetYDp)
        assertEquals(original, storage.values)
        assertTrue(repository.errors.value.any { "write failed" in it.message })
    }

    @Test
    fun anIssueTheUserWasShownLeavesTheListAndReturnsIfItHappensAgain() {
        // The list lives as long as the process. Kept there, an old problem was shown again by every
        // screen opened later, and the mirror screen led every session with it.
        val logged = mutableListOf<PersistenceIssue>()
        val repository = DeviceRepository(MemoryStorage(), logged::add)
        assertFalse(repository.deleteBackupHistory("missing"))
        val shown = repository.errors.value
        assertEquals(1, shown.size)
        repository.acknowledge(shown)
        assertTrue(repository.errors.value.isEmpty())
        // Happening again is news again; while it stayed listed, a repeat was not reported at all.
        assertFalse(repository.deleteBackupHistory("missing"))
        assertEquals(shown, repository.errors.value)
        assertEquals(2, logged.size)
    }

    @Test
    fun acknowledgingLeavesWhatWasReportedSinceOnTheList() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        assertFalse(repository.deleteBackupHistory("missing"))
        val shown = repository.errors.value
        storage.failWrites = true
        assertFalse(repository.updateSettings { it.copy(stayAwake = true) })
        repository.acknowledge(shown)
        assertEquals(listOf("Storage write failed; changes were not committed"), repository.errors.value.map { it.message })
    }

    @Test
    fun invalidCameraValuesNeverCommitGlobalOrDeviceSettings() {
        val storage = MemoryStorage()
        val repository = DeviceRepository(storage)
        val device = SavedDevice(id = "camera", name = "Camera", host = "phone.local")
        assertTrue(repository.saveDevice(device))
        val before = repository.configurationSnapshot()
        val disk = storage.values.toMap()
        val writes = storage.writes
        for (camera in listOf(
            CameraCapture(size = "not-a-size"),
            CameraCapture(aspectRatio = "not-a-ratio"),
        )) {
            assertFalse(repository.updateSettings { it.copy(camera = camera) })
            assertFalse(repository.saveDeviceSettings(
                device.id, null, DeviceMirroringSettings(camera = camera)))
            assertEquals(before, repository.configurationSnapshot())
            assertEquals(disk, storage.values)
            assertEquals(writes, storage.writes)
        }
    }

    @Test
    fun invalidStoredCameraValuesAreReportedAndRetainedDuringUnrelatedEdits() {
        for (camera in listOf("""{"size":"not-a-size"}""", """{"aspectRatio":"not-a-ratio"}""")) {
            val settings = """{"schemaVersion":1,"maxFps":45,"camera":$camera}"""
            val devices = """[{"id":"camera","name":"Camera","host":"phone.local",
                "mirroringSettings":{"camera":$camera}}]"""
            val storage = MemoryStorage(mapOf("settings" to settings, "devices" to devices))
            val repository = DeviceRepository(storage)

            assertEquals(0, storage.writes)
            assertEquals(settings, storage.values["settings"])
            assertEquals(devices, storage.values["devices"])
            assertEquals(null, repository.getSettings().camera)
            assertEquals(null, repository.getDevices().single().mirroringSettings?.camera)
            assertTrue(repository.errors.value.any { it.field == "camera" })
            assertTrue(repository.errors.value.any { it.field == "[0].mirroringSettings.camera" })

            assertTrue(repository.updateSettings { it.copy(maxFps = 30) })
            assertTrue(repository.setFavorite("camera", true))
            assertEquals(settings, storage.values["settings.recovery.1"])
            assertEquals(devices, storage.values["devices.recovery.1"])
        }
    }

    @Test
    fun failedMigrationCanBeRetriedWithoutLosingLegacyState() {
        val storage = MemoryStorage(mapOf("touch_offset_y" to -7, "audio_enabled" to false))
        storage.failWrites = true
        val repository = DeviceRepository(storage)
        assertEquals(-7, repository.getSettings().touchOffsetYDp)
        assertFalse(repository.getSettings().audioEnabled)
        assertFalse(storage.values.containsKey("settings"))
        assertTrue(repository.errors.value.isNotEmpty())
        storage.failWrites = false
        assertEquals(repository.getSettings(), DeviceRepository(storage).getSettings())
        assertTrue(storage.values.containsKey("settings"))
    }

    @Test
    fun failedReadsKeepAppUsableAndPreventAccidentalOverwrite() {
        val storage = MemoryStorage(mapOf("settings" to "original"))
        storage.failReads = true
        val repository = DeviceRepository(storage)
        assertEquals(MirrorSettings(), repository.getSettings())
        assertTrue(repository.errors.value.isNotEmpty())
        storage.failReads = false
        assertFalse(repository.updateSettings { it.copy(touchOffsetYDp = 9) })
        assertEquals("original", storage.values["settings"])
        assertEquals(0, storage.writes)
    }

    @Test
    fun aStaleEditCannotResurrectADeletedDevice() {
        // Two device lists can be open at once (one launched by the share sheet in another task),
        // so an editor can outlive the device it was opened on.
        val repository = DeviceRepository(MemoryStorage())
        assertTrue(repository.saveDevice(SavedDevice("id", "Phone", "phone.local")))
        val baseline = repository.getDevices().single()
        assertTrue(repository.deleteDevice("id"))
        assertFalse(repository.editDevice(baseline, baseline.copy(name = "Stale edit")))
        assertTrue(repository.getDevices().isEmpty())
        assertTrue(repository.errors.value.any { it.message.contains("no longer exists") })
    }

    @Test
    fun concurrentEditorsOnlyWriteTheFieldsTheyChanged() {
        val repository = DeviceRepository(MemoryStorage())
        assertTrue(repository.saveDevice(SavedDevice("id", "Phone", "192.168.1.2", 5555)))
        val first = repository.getDevices().single()
        val second = repository.getDevices().single()
        assertTrue(repository.editDevice(first, first.copy(host = "192.168.1.9")))
        assertTrue(repository.editDevice(second, second.copy(name = "Desk phone")))
        val result = repository.getDevices().single()
        assertEquals("Desk phone", result.name)
        assertEquals("The second editor never touched the address", "192.168.1.9", result.host)
        assertEquals(5555, result.port)
    }

    @Test
    fun aStaleSettingsEditorCannotPairAnEncoderWithAnotherEditorsCodec() {
        val repository = DeviceRepository(MemoryStorage())
        val base = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.android.avc.encoder")
        assertTrue(repository.saveSettings(base))
        // One editor switches codec, which drops the encoder chosen for the old one. A second
        // editor, opened before that, picks another H.264 encoder.
        assertTrue(repository.mergeSettings(base, base.copy(videoCodec = VideoCodec.H265, videoEncoder = null)))
        assertTrue(repository.mergeSettings(base, base.copy(videoEncoder = "c2.other.avc.encoder")))
        val merged = repository.settings.value
        assertEquals("A codec and its encoder are one choice", VideoCodec.H264 to "c2.other.avc.encoder",
            merged.videoCodec to merged.videoEncoder)
    }
}
