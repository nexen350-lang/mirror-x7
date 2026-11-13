package com.scrcpy.remote.ui

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceIcon
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.TouchCalibration
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Removing a saved device is one swipe or one menu item, without a question first; what was
 * removed can be put back from the message that follows, with everything it had.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRemovalTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val testIds = mutableListOf<String>()
    private val repository get() = DeviceRepository.get(compose.activity)

    @After fun removeOnlyTestDevices() {
        compose.runOnIdle { testIds.forEach { id -> assertTrue(repository.deleteDevice(id)) } }
    }

    @Test fun swipingACardAwayRemovesItAndUndoPutsItBackWithEverything() {
        val device = addTestDevice()
        val position = compose.runOnIdle { repository.devices.value.indexOfFirst { it.id == device.id } }
        filterTo(device.name)
        card(device).performTouchInput { swipeLeft() }
        awaitSaved { repository.devices.value.none { it.id == device.id } }
        compose.onNodeWithText(text(R.string.device_removed, device.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.undo)).performClick()
        awaitSaved { repository.devices.value.any { it.id == device.id } }
        compose.runOnIdle {
            assertEquals(device, repository.devices.value.single { it.id == device.id })
            assertEquals(position, repository.devices.value.indexOfFirst { it.id == device.id })
        }
        compose.onNode(hasText(device.name) and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun aSwipeTheOtherWayRemovesNothing() {
        val device = addTestDevice()
        filterTo(device.name)
        card(device).performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(repository.devices.value.any { it.id == device.id }) }
        compose.onNode(hasText(device.name) and !hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun removingFromTheMenuAsksNothingAndItsUndoSurvivesRecreation() {
        val device = addTestDevice()
        filterTo(device.name)
        openActions(device)
        compose.onNodeWithText(text(R.string.device_list_remove)).performClick()
        awaitSaved { repository.devices.value.none { it.id == device.id } }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.device_removed, device.name)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.undo)).performClick()
        awaitSaved { repository.devices.value.any { it.id == device.id } }
        compose.runOnIdle { assertEquals(device, repository.devices.value.single { it.id == device.id }) }
    }

    @Test fun aScreenReaderCanRemoveACardWithoutSwiping() {
        val device = addTestDevice()
        filterTo(device.name)
        // Offered where a screen reader stops: the card's name and address, merged into the row that
        // connects. The card itself is never focused, so an action on it was never offered.
        val stop = compose.onNode(hasClickAction() and hasText(device.name) and hasAnyAncestor(hasTestTag("device-card:${device.id}")))
        val actions = stop.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val remove = actions.single { it.label == text(R.string.device_list_remove_named, device.name) }
        compose.runOnUiThread { remove.action() }
        awaitSaved { repository.devices.value.none { it.id == device.id } }
        compose.onNodeWithText(text(R.string.undo)).assertIsDisplayed()
    }

    @Test fun aCardWhoseRemovalDoesNotHappenComesBack() {
        // As when a write fails or another one is still running: the list keeps the device.
        val device = SavedDevice(id = "kept", name = "Kept device", host = "10.0.0.9")
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    DeviceList(listOf(device), "", {}, false, {}, PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {},
                        { _, _ -> }, onPair = {}, onAdd = {})
                }
            }
        }
        compose.waitForIdle()
        val resting = compose.onNodeWithTag("device-card:kept").fetchSemanticsNode().positionInWindow.x
        compose.onNodeWithTag("device-card:kept").performTouchInput { swipeLeft() }
        // Waits in real time: the card waits a moment for the removal before coming back.
        compose.waitUntil(timeoutMillis = 5_000) {
            abs(compose.onNodeWithTag("device-card:kept").fetchSemanticsNode().positionInWindow.x - resting) < 2f
        }
        compose.onNodeWithText("Kept device").assertIsDisplayed()
    }

    private fun card(device: SavedDevice) = compose.onNodeWithTag("device-card:${device.id}")

    private fun addTestDevice(): SavedDevice {
        val id = "device-removal-test-${UUID.randomUUID()}"
        val device = SavedDevice(
            id = id, name = id, host = "127.0.0.1", port = 41234, isFavorite = true,
            portraitCalibration = TouchCalibration(3, -4), landscapeCalibration = TouchCalibration(-5, 6),
            mirroringSettings = DeviceMirroringSettings.from(MirrorSettings(maxSize = 1024)),
            avatarColor = 6, avatarIcon = DeviceIcon.TV,
        )
        testIds += id
        compose.runOnIdle { assertTrue(repository.saveDevice(device)) }
        return device
    }

    private fun filterTo(query: String) {
        compose.onNodeWithText(text(R.string.device_list_search)).performTextReplacement(query)
        compose.pressDeviceSearch()
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
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name)).performClick()
    }

    private fun awaitSaved(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            predicate() && compose.onAllNodesWithTag("storage-busy").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}
