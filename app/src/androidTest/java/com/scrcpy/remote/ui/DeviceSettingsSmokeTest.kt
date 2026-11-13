package com.scrcpy.remote.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.VideoCodec
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSettingsSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val testIds = mutableListOf<String>()
    private val repository get() = DeviceRepository.get(compose.activity)

    @After
    fun removeOnlyTestDevices() {
        compose.waitUntil(timeoutMillis = 10_000) { storageIsIdle() }
        compose.runOnIdle {
            testIds.forEach { id ->
                if (repository.devices.value.any { it.id == id }) assertTrue(repository.deleteDevice(id))
            }
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun globalSettingsRetainOriginalTitleAndShowGlobalDefaultsScope() {
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.device_settings_global_defaults)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
    }

    @Test
    fun deviceMenuOpensInheritedSettingsAndNoOpSaveDoesNotCreateProfile() {
        val device = addDevice()
        val globals = repository.settings.value
        filterTo(device)
        compose.onNodeWithText(text(R.string.device_settings_global_summary)).assertIsDisplayed()
        openSettings(device)
        compose.onNodeWithText(text(R.string.device_settings_title, device.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.device_settings_use_global)).assertIsOn()
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved(device)
        compose.onNodeWithText(text(R.string.device_settings_title, device.name)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(device, currentDevice(device))
            assertNull(currentDevice(device).mirroringSettings)
            assertEquals(globals, repository.settings.value)
        }
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.device_list_add_favorite, device.name))
            .assertIsDisplayed()
    }

    @Test
    fun allCustomValuesSurviveRecreationAndReopenWithoutChangingAnotherDevice() {
        val first = addDevice()
        val second = addDevice(profile = DeviceMirroringSettings(maxSize = 2560, maxFps = 90))
        val globals = repository.settings.value
        val initial = DeviceMirroringSettings.from(globals)
        val edited = initial.copy(
            maxSize = if (initial.maxSize == 1280) 1920 else 1280,
            videoBitRateMbps = if (initial.videoBitRateMbps == 12) 13 else 12,
            maxFps = if (initial.maxFps == 45) 90 else 45,
            videoCodec = VideoCodec.H264,
            audioEnabled = false,
            audioCodec = AudioCodec.AAC,
            stayAwake = !initial.stayAwake,
            showTouches = !initial.showTouches,
            turnScreenOff = !initial.turnScreenOff,
            powerOffOnClose = !initial.powerOffOnClose,
            captureEdgeGestures = !initial.captureEdgeGestures,
            syncMode = PlaybackSyncMode.SYNCHRONIZED,
            audioBufferMs = 250,
            syncOffsetMs = if (initial.syncOffsetMs == -125) 125 else -125,
            autoReconnect = !initial.autoReconnect,
        )
        filterTo(first)
        openSettings(first)
        toggle(R.string.device_settings_use_global)
        setSlider(R.string.setting_max_resolution, initial.maxSize, edited.maxSize)
        setSlider(R.string.setting_bit_rate, initial.videoBitRateMbps, edited.videoBitRateMbps)
        setSlider(R.string.setting_max_fps, initial.maxFps, edited.maxFps)
        compose.onNodeWithText(VideoCodec.H264.label).performScrollTo().performClick()
        if (!initial.audioEnabled) toggle(R.string.setting_forward_audio)
        compose.onNodeWithText(AudioCodec.AAC.label).performScrollTo().performClick()
        toggle(R.string.setting_forward_audio)
        compose.onNodeWithText(text(R.string.ui_setting_audio_codec)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.setting_sync_synchronized)).performScrollTo().performClick()
        setSlider(R.string.setting_sync_offset_value, initial.syncOffsetMs, edited.syncOffsetMs)
        compose.onNodeWithText(text(R.string.setting_audio_buffer_preset, 80)).performScrollTo().performClick()
        setSlider(R.string.setting_audio_buffer_value, 80, edited.audioBufferMs)
        toggle(R.string.setting_auto_reconnect)
        toggle(R.string.setting_keep_awake)
        toggle(R.string.setting_show_touches)
        toggle(R.string.setting_turn_screen_off)
        toggle(R.string.setting_power_off)
        toggle(R.string.setting_edge_gestures)
        compose.runOnIdle { assertNull(currentDevice(first).mirroringSettings) }

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.device_settings_title, first.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().performClick()
        awaitSaved(first.copy(mirroringSettings = edited))
        compose.runOnIdle {
            assertEquals(edited, currentDevice(first).mirroringSettings)
            assertEquals(second, currentDevice(second))
            assertEquals(globals, repository.settings.value)
        }
        compose.onNodeWithText(
            text(R.string.device_settings_custom_summary, edited.maxSize, edited.maxFps, text(R.string.device_settings_audio_off)),
        ).assertIsDisplayed()

        openSettings(first)
        compose.onNodeWithText(text(R.string.device_settings_use_global)).assertIsOff()
        compose.onNodeWithText(text(R.string.setting_max_resolution, edited.maxSize))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_bit_rate, edited.videoBitRateMbps))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_max_fps, edited.maxFps))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(VideoCodec.H264.label).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_forward_audio)).performScrollTo().assertIsOff()
        toggle(R.string.setting_forward_audio)
        compose.onNodeWithText(AudioCodec.AAC.label).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_sync_synchronized)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.setting_sync_offset_value, edited.syncOffsetMs))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_audio_buffer_value, edited.audioBufferMs))
            .performScrollTo().assertIsDisplayed()
        assertSwitch(R.string.setting_auto_reconnect, edited.autoReconnect)
        assertSwitch(R.string.setting_keep_awake, edited.stayAwake)
        assertSwitch(R.string.setting_show_touches, edited.showTouches)
        assertSwitch(R.string.setting_turn_screen_off, edited.turnScreenOff)
        assertSwitch(R.string.setting_power_off, edited.powerOffOnClose)
        assertSwitch(R.string.setting_edge_gestures, edited.captureEdgeGestures)
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(edited, currentDevice(first).mirroringSettings) }
    }

    @Test
    fun useGlobalSettingsResetsOnlyMirroringProfileAfterSave() {
        val device = addDevice(
            profile = DeviceMirroringSettings(maxSize = 960, audioEnabled = false),
            portrait = TouchCalibration(3, -4),
            landscape = TouchCalibration(-5, 6),
            favorite = true,
        )
        val globals = repository.settings.value
        filterTo(device)
        openSettings(device)
        toggle(R.string.device_settings_use_global)
        compose.onNodeWithText(text(R.string.device_settings_calibration_help))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_max_resolution, 960)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(device, currentDevice(device)) }
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved(device.copy(mirroringSettings = null))
        compose.runOnIdle {
            assertEquals(device.copy(mirroringSettings = null), currentDevice(device))
            assertEquals(globals, repository.settings.value)
        }
        openSettings(device)
        compose.onNodeWithText(text(R.string.device_settings_use_global)).assertIsOn()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
    }

    @Test
    fun cancellingInheritedAndCustomizedDraftsNeverWritesSettings() {
        for (profile in listOf(null, DeviceMirroringSettings(maxSize = 960, audioCodec = AudioCodec.FLAC))) {
            val device = addDevice(profile = profile)
            filterTo(device)
            openSettings(device)
            if (profile == null) toggle(R.string.device_settings_use_global)
            toggle(R.string.setting_edge_gestures)
            compose.onNodeWithText(text(R.string.cancel)).performClick()
            compose.runOnIdle { assertEquals(device, currentDevice(device)) }
            openSettings(device)
            assertSwitch(R.string.device_settings_use_global, profile == null)
            compose.onNodeWithText(text(R.string.cancel)).performClick()
        }
    }

    @Test
    fun savingDraftMergesConcurrentProfileChangesAndUsesLatestDeviceName() {
        val profile = DeviceMirroringSettings(maxSize = 1280, maxFps = 30,
            syncMode = PlaybackSyncMode.SYNCHRONIZED, audioBufferMs = 80)
        val device = addDevice(profile = profile, portrait = TouchCalibration(2, 3))
        filterTo(device)
        openSettings(device)
        setSlider(R.string.setting_max_resolution, 1280, 1920)
        setSlider(R.string.setting_sync_offset_value, 0, 125)
        toggle(R.string.setting_auto_reconnect)
        val renamed = device.copy(name = "${device.name} renamed")
        compose.runOnIdle {
            assertTrue(repository.saveDevice(renamed))
            assertTrue(repository.setFavorite(device.id, true))
            assertTrue(repository.saveDeviceSettings(device.id, profile, profile.copy(maxFps = 90, audioBufferMs = 150)))
        }
        compose.onNodeWithText(text(R.string.device_settings_title, renamed.name)).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.save)).performClick()
        val expected = renamed.copy(isFavorite = true, mirroringSettings = profile.copy(
            maxSize = 1920, maxFps = 90, audioBufferMs = 150, syncOffsetMs = 125, autoReconnect = true,
        ))
        awaitSaved(expected)
        compose.runOnIdle {
            assertEquals(expected, currentDevice(device))
        }
    }

    @Test
    fun deletingDeviceDismissesOpenSettingsWithoutRecreatingDevice() {
        val device = addDevice()
        filterTo(device)
        openSettings(device)
        compose.runOnIdle { assertTrue(repository.deleteDevice(device.id)) }
        compose.onNodeWithText(text(R.string.device_settings_title, device.name)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.save)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.save)).assertDoesNotExist()
        compose.runOnIdle { assertTrue(repository.devices.value.none { it.id == device.id }) }
    }

    @Test
    fun saveAndCancelStayVisibleInLandscapeWhileLastSettingScrolls() {
        compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(timeoutMillis = 10_000) {
            val decor = compose.activity.window.decorView
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                decor.width > decor.height
        }
        val device = addDevice(profile = DeviceMirroringSettings())
        openSettings(device)
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText(text(R.string.setting_edge_gestures))
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription(
            text(R.string.control_plus_one, text(R.string.setting_horizontal_offset)),
        ).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.save)).assertIsDisplayed().performClick()
        awaitSaved(device)
        compose.runOnIdle { assertEquals(device, currentDevice(device)) }
    }

    private fun awaitSaved(expected: SavedDevice) {
        val dialogTitle = text(R.string.device_settings_title, expected.name)
        // A no-op save already matches storage; it is finished only after the caller closes the dialog.
        compose.waitUntil(timeoutMillis = 10_000) {
            repository.devices.value.singleOrNull { it.id == expected.id } == expected &&
                storageIsIdle() &&
                compose.onAllNodesWithText(dialogTitle).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun storageIsIdle(): Boolean = !repository.writing.value &&
        compose.onAllNodesWithTag("storage-busy").fetchSemanticsNodes().isEmpty()

    private fun addDevice(
        profile: DeviceMirroringSettings? = null,
        portrait: TouchCalibration? = null,
        landscape: TouchCalibration? = null,
        favorite: Boolean = false,
    ): SavedDevice {
        val id = "device-settings-test-${UUID.randomUUID()}"
        val device = SavedDevice(
            id = id, name = id, host = "127.0.0.1", isFavorite = favorite,
            portraitCalibration = portrait, landscapeCalibration = landscape, mirroringSettings = profile,
        )
        testIds += id
        compose.runOnIdle { assertTrue(repository.saveDevice(device)) }
        return device
    }

    private fun currentDevice(device: SavedDevice) = repository.devices.value.single { it.id == device.id }

    private fun filterTo(device: SavedDevice) {
        compose.onNodeWithText(text(R.string.device_list_search)).performTextReplacement(device.name)
        compose.pressDeviceSearch()
        // IME insets settle asynchronously, especially after a rotation. Wait before positioning
        // and opening the lazy-list item's popup; otherwise its anchor may leave the viewport.
        compose.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription(text(R.string.device_list_hide_pairing_help))
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithContentDescription(text(R.string.device_list_hide_pairing_help)).performClick()
        }
    }

    private fun openSettings(device: SavedDevice) {
        compose.onNodeWithTag("saved-device-list").performScrollToNode(
            hasContentDescription(text(R.string.device_list_more_actions, device.name)),
        )
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name))
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { click -> assertTrue(click()) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text(R.string.device_settings)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.device_settings)).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun toggle(resource: Int) {
        compose.onNodeWithText(text(resource)).performScrollTo().performClick()
    }

    private fun assertSwitch(resource: Int, checked: Boolean) {
        val node = compose.onNodeWithText(text(resource)).performScrollTo()
        if (checked) node.assertIsOn() else node.assertIsOff()
    }

    private fun setSlider(resource: Int, current: Int, updated: Int) {
        compose.onNodeWithContentDescription(text(resource, current)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(updated.toFloat()) }
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}
