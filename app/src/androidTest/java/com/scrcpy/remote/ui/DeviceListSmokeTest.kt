package com.scrcpy.remote.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceListSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val testIds = mutableListOf<String>()
    private val repository get() = DeviceRepository.get(compose.activity)

    @After
    fun removeOnlyTestDevices() {
        compose.runOnIdle {
            testIds.forEach { id -> assertTrue(repository.deleteDevice(id)) }
        }
    }

    @Test
    fun searchAndFavoriteActionsDoNotConnect() {
        val device = addTestDevice()
        filterTo(device.name)

        compose.onNode(hasText(device.name) and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.device_list_add_favorite, device.name))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        awaitSaved { repository.devices.value.single { it.id == device.id }.isFavorite }
        compose.onNodeWithContentDescription(text(R.string.device_list_remove_favorite, device.name))
            .assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(repository.devices.value.single { it.id == device.id }.isFavorite)
        }
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()

        filterTo("no-match-${UUID.randomUUID()}")
        compose.onNodeWithText(text(R.string.device_list_no_matches)).assertIsDisplayed()
        compose.onNodeWithText(device.name).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.device_list_clear_search))
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithContentDescription(text(R.string.device_list_clear_search)).assertDoesNotExist()
    }

    @Test
    fun editingKeepsFavoritesAndBothOrientationCalibrations() {
        val device = addTestDevice(
            favorite = true,
            portrait = TouchCalibration(xDp = 3, yDp = -4),
            landscape = TouchCalibration(xDp = -5, yDp = 6),
        )
        filterTo(device.name)
        openActions(device)
        compose.onNodeWithText(text(R.string.edit)).performClick()
        val editedName = "${device.name} edited"
        compose.onNodeWithText(text(R.string.field_name_optional)).performTextReplacement(editedName)
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved { repository.devices.value.single { it.id == device.id }.name == editedName }
        compose.runOnIdle {
            assertEquals(device.copy(name = editedName), repository.devices.value.single { it.id == device.id })
        }
    }

    @Test
    fun duplicateDraftSurvivesRecreationAndCancelDoesNotSave() {
        val source = addTestDevice(true, TouchCalibration(8, -30), TouchCalibration(-10, 20))
        compose.runOnIdle {
            assertTrue(repository.saveDeviceSettings(source.id, source.mirroringSettings, DeviceMirroringSettings(maxSize = 800)))
        }
        val original = repository.getDevices().single { it.id == source.id }
        filterTo(source.name)
        openActions(source)
        compose.onNodeWithText(text(R.string.profile_tools_duplicate)).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.profile_tools_duplicate_help)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, repository.getDevices().count { it.host == source.host && it.name.contains(source.name) }) }
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(original, repository.getDevices().single { it.id == source.id }) }
        openActions(source)
        compose.onNodeWithText(text(R.string.profile_tools_duplicate)).performClick()
        val copyName = "Copy ${UUID.randomUUID()}"
        compose.onNodeWithText(text(R.string.field_name_optional)).performTextReplacement(copyName)
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved { repository.devices.value.any { it.name == copyName } }
        compose.runOnIdle {
            val copy = repository.getDevices().single { it.name == copyName }
            testIds += copy.id
            assertTrue(copy.id != source.id)
            assertEquals(original.copy(id = copy.id, name = copyName), copy)
        }
    }

    @Test
    fun copyAddressDoesNotConnectOrModifyProfile() {
        val device = addTestDevice()
        filterTo(device.name)
        openActions(device)
        compose.onNodeWithText(text(R.string.profile_tools_copy_address)).performClick()
        // Android only allows a focused app to *read* the clipboard, so assert the precondition
        // explicitly. Without this a stolen focus (for example a system ANR dialog) surfaces as a
        // confusing NullPointerException on primaryClip instead of naming the real cause.
        compose.waitUntil(timeoutMillis = 5_000) { compose.activity.hasWindowFocus() }
        compose.runOnIdle {
            assertEquals(device.address, compose.activity.getSystemService(ClipboardManager::class.java)
                .primaryClip!!.getItemAt(0).text.toString())
            assertEquals(device, repository.getDevices().single { it.id == device.id })
        }
        compose.onNodeWithContentDescription(text(R.string.settings)).assertIsDisplayed()
    }

    @Test
    fun exportRequiresPrivacyConfirmationAndCanBeCancelledAfterRecreation() {
        compose.onNodeWithContentDescription(text(R.string.profile_tools_menu)).performClick()
        compose.onNodeWithText(text(R.string.profile_tools_export)).performClick()
        compose.onNodeWithText(text(R.string.profile_tools_export_help)).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.profile_tools_export_help)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.onNodeWithText(text(R.string.profile_tools_export_title)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.profile_tools_menu)).assertIsDisplayed()
    }

    private fun addTestDevice(
        favorite: Boolean = false,
        portrait: TouchCalibration? = null,
        landscape: TouchCalibration? = null,
    ): SavedDevice {
        val id = "device-list-test-${UUID.randomUUID()}"
        val device = SavedDevice(
            id = id,
            name = id,
            host = "127.0.0.1",
            isFavorite = favorite,
            portraitCalibration = portrait,
            landscapeCalibration = landscape,
        )
        testIds += id
        compose.runOnIdle { assertTrue(repository.saveDevice(device)) }
        return device
    }

    private fun filterTo(query: String) {
        compose.onNodeWithText(text(R.string.device_list_search))
            .performTextReplacement(query)
        compose.pressDeviceSearch()
        // WindowInsets can only report IME visibility from API 30. Below that the condition can
        // never become true, so waiting on it would always time out instead of settling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            compose.waitUntil(timeoutMillis = 10_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription(text(R.string.device_list_hide_pairing_help))
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithContentDescription(text(R.string.device_list_hide_pairing_help)).performClick()
        }
    }

    private fun openActions(device: SavedDevice) {
        compose.onNodeWithTag("saved-device-list").performScrollToNode(
            hasContentDescription(text(R.string.device_list_more_actions, device.name)),
        )
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name))
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
    }

    private fun awaitSaved(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            predicate() && compose.onAllNodesWithTag("storage-busy").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)

    @Test
    fun everyDeviceActionIsReachableFromTheMenu() {
        // Pins the menu contents, not just the handlers behind them. "Share connection link"
        // shipped once with its whole callback chain wired up but no entry in this menu, so the
        // feature could not be reached at all and nothing failed.
        val device = addTestDevice()
        filterTo(device.name)
        openActions(device)
        for (label in listOf(
            R.string.device_settings,
            R.string.edit,
            R.string.profile_tools_duplicate,
            R.string.profile_tools_copy_address,
            R.string.profile_tools_share_link,
            R.string.profile_tools_pin,
            R.string.device_list_remove,
        )) {
            compose.onNodeWithText(text(label)).assertIsDisplayed().assertHasClickAction()
        }
    }
}