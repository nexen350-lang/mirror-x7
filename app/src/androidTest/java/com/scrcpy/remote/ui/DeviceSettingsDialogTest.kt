package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSettingsDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun customSnapshotUsesGlobalsAtFirstEnableAndRemembersHiddenAudioCodec() {
        val defaults = mutableStateOf(MirrorSettings())
        var saved: DeviceMirroringSettings? = null
        var baseline: DeviceMirroringSettings? = null
        val device = SavedDevice(name = "Loopback profile", host = "127.0.0.1")
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    DeviceSettingsDialog(
                        device = device,
                        defaults = defaults.value,
                        onDismiss = {},
                        onSave = { base, edited -> baseline = base; saved = edited },
                    )
                }
            }
        }
        val latest = MirrorSettings(
            maxSize = 960, maxFps = 30, audioEnabled = false, audioCodec = AudioCodec.FLAC,
            touchOffsetXDp = 7, touchOffsetYDp = -8, captureEdgeGestures = false,
            syncMode = PlaybackSyncMode.SYNCHRONIZED, audioBufferMs = 80, syncOffsetMs = -64,
            autoReconnect = true,
        )
        compose.runOnIdle { defaults.value = latest }
        compose.onNodeWithText(text(R.string.device_settings_use_global)).performClick()
        compose.onNodeWithText(text(R.string.ui_setting_audio_codec)).assertDoesNotExist()
        compose.runOnIdle {
            defaults.value = latest.copy(maxSize = 2560, audioCodec = AudioCodec.AAC,
                syncMode = PlaybackSyncMode.LOW_LATENCY, audioBufferMs = 0, syncOffsetMs = 200,
                autoReconnect = false)
        }
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertNull(baseline)
            assertEquals(DeviceMirroringSettings.from(latest), saved)
        }
        compose.onNodeWithText(text(R.string.setting_forward_audio)).performScrollTo().performClick()
        compose.onNodeWithText(AudioCodec.FLAC.label).performScrollTo().assertIsSelected()
    }

    @Test
    fun saveKeepsDraftAndBaselineUntilCallerDismissesAndExplainsReconnect() {
        val original = DeviceMirroringSettings(showTouches = false)
        val device = SavedDevice(name = "Loopback profile", host = "127.0.0.1", mirroringSettings = original)
        val saves = mutableListOf<Pair<DeviceMirroringSettings?, DeviceMirroringSettings?>>()
        var dismissed = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    DeviceSettingsDialog(
                        device = device,
                        defaults = MirrorSettings(),
                        onDismiss = { dismissed = true },
                        onSave = { baseline, edited -> saves += baseline to edited },
                        sessionActive = true,
                    )
                }
            }
        }
        compose.onNodeWithText(text(R.string.device_settings_reconnect_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_show_touches)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.onNodeWithText(text(R.string.device_settings_title, device.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertFalse(dismissed)
            assertEquals(List(2) { original to original.copy(showTouches = true) }, saves)
        }
    }

    @Test
    fun unsupportedSavedVideoCodecStaysSelectedWithWarningUntilUserChangesIt() {
        val original = MirrorSettings(videoCodec = VideoCodec.AV1)
        val draft = mutableStateOf(original)
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MirroringSettingsControls(
                            settings = draft.value,
                            onSettingsChange = { draft.value = it },
                            supportedVideoCodecs = listOf(VideoCodec.H264),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(VideoCodec.AV1.label).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.device_settings_unsupported_codec, VideoCodec.AV1.label))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(original, draft.value) }
        compose.onNodeWithText(VideoCodec.H264.label).performScrollTo().performClick()
        compose.onNodeWithText(VideoCodec.H264.label).assertIsSelected()
        compose.onNodeWithText(text(R.string.device_settings_unsupported_codec, VideoCodec.AV1.label))
            .assertDoesNotExist()
        compose.runOnIdle { assertEquals(original.copy(videoCodec = VideoCodec.H264), draft.value) }
    }

    @Test
    fun globalPlaybackControlsSaveTheDraftAndOriginalBaselineWithoutChangingTouchOffsets() {
        val original = MirrorSettings(touchOffsetXDp = 7, touchOffsetYDp = -8)
        val settings = mutableStateOf(original)
        val saves = mutableListOf<Pair<MirrorSettings, MirrorSettings>>()
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    SettingsDialog(
                        settings = settings.value,
                        onDismiss = {},
                        onSave = { baseline, edited -> saves += baseline to edited },
                    )
                }
            }
        }
        compose.onNodeWithText(text(R.string.setting_sync_low_latency)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_audio_buffer_automatic)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.setting_sync_synchronized)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_sync_offset_help)).performScrollTo().assertIsDisplayed()
        setSlider(R.string.setting_sync_offset_value, 0, 125)
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_increase)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_offset_value, 126)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_decrease)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_audio_buffer_preset, 80)).performScrollTo().performClick()
        setSlider(R.string.setting_audio_buffer_value, 80, 250)
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(text(R.string.setting_auto_reconnect_help)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_playback_apply_help)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { settings.value = original.copy(touchOffsetYDp = 77, audioCodec = AudioCodec.AAC) }
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(original to original.copy(
                syncMode = PlaybackSyncMode.SYNCHRONIZED, audioBufferMs = 250,
                syncOffsetMs = 125, autoReconnect = true,
            )), saves)
        }
    }

    @Test
    fun playbackModesRetainSignedAdjustmentAndBufferCanReturnToAutomaticWithAudioOff() {
        val original = MirrorSettings(audioEnabled = false, syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 40, syncOffsetMs = -23)
        val draft = mutableStateOf(original)
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MirroringSettingsControls(settings = draft.value, onSettingsChange = { draft.value = it })
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.ui_setting_audio_codec)).assertDoesNotExist()
        setSlider(R.string.setting_sync_offset_value, -23, -200)
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_decrease))
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_increase)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_low_latency)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_offset_value, -199)).assertDoesNotExist()
        setSlider(R.string.setting_audio_buffer_value, 40, 20)
        compose.runOnIdle {
            assertEquals(original.copy(syncMode = PlaybackSyncMode.LOW_LATENCY,
                audioBufferMs = 20, syncOffsetMs = -199), draft.value)
        }
        compose.onNodeWithText(text(R.string.setting_audio_buffer_automatic)).performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithContentDescription(text(R.string.setting_audio_buffer_value, 20)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.setting_sync_synchronized)).performScrollTo().performClick()
        setSlider(R.string.setting_sync_offset_value, -199, 200)
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_increase))
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.setting_sync_offset_reset)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(original.copy(audioBufferMs = 0, syncOffsetMs = 0), draft.value) }
    }

    @Test
    fun inheritedSummaryTracksLatestGlobalsButSwitchingBackPreservesCustomPlaybackDraft() {
        val original = DeviceMirroringSettings(syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 150, syncOffsetMs = -100, autoReconnect = true)
        val device = SavedDevice(name = "Loopback profile", host = "127.0.0.1", mirroringSettings = original)
        val defaults = mutableStateOf(MirrorSettings())
        val saves = mutableListOf<Pair<DeviceMirroringSettings?, DeviceMirroringSettings?>>()
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    DeviceSettingsDialog(device, defaults.value, onDismiss = {},
                        onSave = { baseline, edited -> saves += baseline to edited })
                }
            }
        }
        setSlider(R.string.setting_sync_offset_value, -100, 75)
        compose.onNodeWithText(text(R.string.setting_audio_buffer_preset, 40)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_low_latency)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.device_settings_use_global)).performScrollTo().performClick()
        compose.runOnIdle {
            defaults.value = MirrorSettings(syncMode = PlaybackSyncMode.SYNCHRONIZED,
                audioBufferMs = 250, syncOffsetMs = 37)
        }
        compose.onNodeWithText(text(R.string.setting_playback_summary,
            text(R.string.setting_sync_synchronized), text(R.string.setting_audio_buffer_value, 250),
            text(R.string.setting_auto_reconnect_off))).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_sync_offset_value, 37)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.onNodeWithText(text(R.string.device_settings_use_global)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_low_latency)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_audio_buffer_preset, 40)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().assertIsOff()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(original to null, original to original.copy(
                syncMode = PlaybackSyncMode.LOW_LATENCY, audioBufferMs = 40,
                syncOffsetMs = 75, autoReconnect = false,
            )), saves)
        }
    }

    @Test
    fun pendingDeviceSaveDisablesDraftEditsAndDuplicateSubmissionThenAllowsRetry() {
        val original = DeviceMirroringSettings(syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 80, syncOffsetMs = 12)
        val device = SavedDevice(name = "Loopback profile", host = "127.0.0.1", mirroringSettings = original)
        val saving = mutableStateOf(false)
        val saves = mutableListOf<Pair<DeviceMirroringSettings?, DeviceMirroringSettings?>>()
        var dismissed = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    DeviceSettingsDialog(
                        device, MirrorSettings(), onDismiss = { dismissed = true },
                        onSave = { baseline, edited ->
                            saves += baseline to edited
                            saving.value = true
                        },
                        saving = saving.value,
                    )
                }
            }
        }
        setSlider(R.string.setting_sync_offset_value, 12, 35)
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.onNodeWithText(text(R.string.settings_saving)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.device_settings_use_global))
            .performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.setting_sync_low_latency)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.setting_sync_offset_decrease))
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription(text(R.string.setting_audio_buffer_value, 80))
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.setting_auto_reconnect)).performScrollTo().assertIsNotEnabled()
        val expected = original to original.copy(syncOffsetMs = 35, autoReconnect = true)
        compose.runOnIdle {
            assertEquals(listOf(expected), saves)
            assertFalse(dismissed)
            saving.value = false
        }
        compose.onNodeWithText(text(R.string.device_settings_use_global)).performScrollTo().assertIsOff().assertIsEnabled()
        compose.onNodeWithText(text(R.string.setting_sync_offset_value, 35)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(expected, expected), saves) }
    }

    @Test
    fun pendingGlobalSaveDisablesCalibrationAndRetainsDraftForRetry() {
        val original = MirrorSettings(touchOffsetYDp = -8)
        val saving = mutableStateOf(false)
        val saves = mutableListOf<Pair<MirrorSettings, MirrorSettings>>()
        var dismissed = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    SettingsDialog(
                        original, onDismiss = { dismissed = true },
                        onSave = { baseline, edited ->
                            saves += baseline to edited
                            saving.value = true
                        },
                        saving = saving.value,
                    )
                }
            }
        }
        val calibrationIncrease = text(R.string.control_plus_one, text(R.string.setting_vertical_offset))
        compose.onNodeWithContentDescription(calibrationIncrease).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.onNodeWithText(text(R.string.settings_saving)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription(calibrationIncrease).performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription(text(R.string.setting_max_resolution, original.maxSize))
            .performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(original to original.copy(touchOffsetYDp = -7)), saves)
            assertFalse(dismissed)
            saving.value = false
        }
        compose.onNodeWithContentDescription(calibrationIncrease).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(List(2) { original to original.copy(touchOffsetYDp = -7) }, saves) }
    }

    @Test
    fun pendingDeviceEditorSaveDisablesFieldsAndPreservesTheEditedEndpointForRetry() {
        val original = SavedDevice(name = "Loopback profile", host = "127.0.0.1")
        val saving = mutableStateOf(false)
        val saves = mutableListOf<SavedDevice>()
        var dismissed = false
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    DeviceEditorDialog(
                        original, onDismiss = { dismissed = true },
                        onSave = { edited -> saves += edited; saving.value = true },
                        saving = saving.value,
                    )
                }
            }
        }
        compose.onNodeWithText(text(R.string.field_name_optional)).performTextReplacement("Edited profile")
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.onNodeWithText(text(R.string.settings_saving)).assertIsNotEnabled().performClick()
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled().performClick()
        for (field in listOf(R.string.field_name_optional, R.string.field_ip_address, R.string.field_port)) {
            compose.onNodeWithText(text(field)).performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle {
            assertEquals(listOf(original.copy(name = "Edited profile")), saves)
            assertFalse(dismissed)
            saving.value = false
        }
        compose.onNodeWithText(text(R.string.field_name_optional)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(List(2) { original.copy(name = "Edited profile") }, saves) }
    }

    @Test
    fun aStartupAppIsOnlyAcceptedWhenTheControlMessageCouldCarryIt() {
        // An over-long or malformed name throws on the control writer, which ends the session
        // rather than the action, so the field has to refuse it here.
        val draft = mutableStateOf(MirrorSettings())
        renderControls(draft)

        compose.onNodeWithText(text(R.string.setting_start_app)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", draft.value.startApp) }

        compose.onNodeWithText(text(R.string.setting_start_app_field)).performScrollTo()
            .performTextReplacement("not a package")
        compose.onNodeWithText(text(R.string.setting_start_app_hint_error)).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.setting_start_app_field)).performScrollTo()
            .performTextReplacement("com.example.app")
        compose.onNodeWithText(text(R.string.setting_start_app_field_help)).assertIsDisplayed()
        compose.runOnIdle { assertEquals("com.example.app", draft.value.startApp) }

        compose.onNodeWithText(text(R.string.setting_start_app)).performScrollTo().performClick()
        compose.runOnIdle { assertNull(draft.value.startApp) }
        compose.onNodeWithText(text(R.string.setting_start_app_field)).assertDoesNotExist()
    }

    @Test
    fun aVirtualDisplaySizeIsCheckedBeforeItCanFailAConnection() {
        // The device refuses to start at all on a malformed value, which would otherwise reach
        // the user only as a failed connection.
        val draft = mutableStateOf(MirrorSettings())
        renderControls(draft)

        compose.onNodeWithText(text(R.string.setting_virtual_display)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", draft.value.virtualDisplay) }
        // Scrolled to before asserting, like every other reach into this dialog: the helper sits
        // below the switch that reveals it, so on a shorter screen it lands off the fold and the
        // assertion failed for want of room rather than because the text was missing.
        compose.onNodeWithText(text(R.string.setting_virtual_display_size_help))
            .performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(text(R.string.setting_virtual_display_size)).performScrollTo()
            .performTextReplacement("1280")
        compose.onNodeWithText("Size must look like 1280x720").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(text(R.string.setting_virtual_display_size)).performScrollTo()
            .performTextReplacement("1280x720/240")
        compose.onNodeWithText(text(R.string.setting_virtual_display_size_help))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("1280x720/240", draft.value.virtualDisplay) }

        compose.onNodeWithText(text(R.string.setting_virtual_display)).performScrollTo().performClick()
        compose.runOnIdle { assertNull(draft.value.virtualDisplay) }
    }

    @Test
    fun reselectingTheVideoCodecPreservesItsExplicitEncoder() {
        val original = MirrorSettings(
            videoCodec = VideoCodec.H264,
            videoEncoder = "c2.android.avc.encoder",
            audioEncoder = "c2.android.opus.encoder",
        )
        val draft = mutableStateOf(original)
        renderControls(draft)

        compose.onNodeWithText(VideoCodec.H264.label).performScrollTo().assertIsSelected().performClick()

        compose.runOnIdle { assertEquals(original, draft.value) }
    }

    @Test
    fun reselectingTheAudioCodecPreservesItsExplicitEncoder() {
        val original = MirrorSettings(
            audioEnabled = true,
            audioCodec = AudioCodec.OPUS,
            audioEncoder = "c2.android.opus.encoder",
            videoEncoder = "c2.android.avc.encoder",
        )
        val draft = mutableStateOf(original)
        renderControls(draft)

        compose.onNodeWithText(AudioCodec.OPUS.label).performScrollTo().assertIsSelected().performClick()

        compose.runOnIdle { assertEquals(original, draft.value) }
    }

    @Test
    fun changingCodecsStillClearsOnlyTheEncoderForThatStream() {
        val original = MirrorSettings(
            videoCodec = VideoCodec.VP9,
            videoEncoder = "c2.android.vp9.encoder",
            audioEnabled = true,
            audioCodec = AudioCodec.FLAC,
            audioEncoder = "c2.android.flac.encoder",
        )
        val draft = mutableStateOf(original)
        renderControls(draft)

        compose.onNodeWithText(VideoCodec.H264.label).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(original.copy(videoCodec = VideoCodec.H264, videoEncoder = null), draft.value)
        }
        compose.onNodeWithText(AudioCodec.AAC.label).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(original.copy(
                videoCodec = VideoCodec.H264, videoEncoder = null,
                audioCodec = AudioCodec.AAC, audioEncoder = null,
            ), draft.value)
        }
    }

    private fun renderControls(draft: androidx.compose.runtime.MutableState<MirrorSettings>) {
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MirroringSettingsControls(
                            settings = draft.value,
                            onSettingsChange = { draft.value = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun setSlider(resource: Int, current: Int, updated: Int) {
        compose.onNodeWithContentDescription(text(resource, current)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(updated.toFloat()) }
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}
