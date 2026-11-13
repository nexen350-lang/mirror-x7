package com.scrcpy.remote.ui

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceIcon
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.ui.design.AvatarContent
import com.scrcpy.remote.ui.design.AvatarPalette
import com.scrcpy.remote.ui.design.DeviceLookChoices
import java.util.UUID
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A saved device's look is chosen in its editor: one of the palette's colours, and initials or a kind of device. */
@RunWith(AndroidJUnit4::class)
class DeviceLookUiTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val testIds = mutableListOf<String>()
    private val repository get() = DeviceRepository.get(compose.activity)
    private val colors = listOf(
        R.string.avatar_color_blue, R.string.avatar_color_indigo, R.string.avatar_color_purple,
        R.string.avatar_color_pink, R.string.avatar_color_orange, R.string.avatar_color_amber,
        R.string.avatar_color_green, R.string.avatar_color_teal,
    )
    private val kinds = listOf(
        R.string.device_icon_initials, R.string.device_icon_phone, R.string.device_icon_tablet,
        R.string.device_icon_tv, R.string.device_icon_watch,
    )

    @After fun removeOnlyTestDevices() {
        compose.runOnIdle { testIds.forEach { id -> assertTrue(repository.deleteDevice(id)) } }
    }

    @Test fun theEditorOffersEveryColorAndKindAsChoices() {
        val device = addTestDevice()
        openEditor(device)
        expandLook()
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        for (label in listOf(R.string.device_look_automatic) + colors) {
            compose.onNodeWithContentDescription(text(label)).performScrollTo()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assert(radio)
        }
        for (label in kinds) {
            compose.onNodeWithText(text(label)).performScrollTo().assertHeightIsAtLeast(48.dp).assert(radio)
        }
        // A device that has never been given a look is on automatic, with its initials.
        compose.onNodeWithContentDescription(text(R.string.device_look_automatic)).assertIsSelected()
        compose.onNodeWithText(text(R.string.device_icon_initials)).assertIsSelected()
    }

    @Test fun aChosenColorAndKindAreShownBeforeSavingThenSavedAndShownOnTheCard() {
        val device = addTestDevice()
        openEditor(device)
        expandLook()
        compose.onNodeWithContentDescription(text(R.string.avatar_color_pink)).performScrollTo().performClick()
            .assertIsSelected()
        compose.onNodeWithText(text(R.string.device_icon_tablet)).performScrollTo().performClick().assertIsSelected()
        assertEquals("icon:TABLET", compose.onNodeWithTag("device-look-preview", useUnmergedTree = true)
            .fetchSemanticsNode().config[AvatarContent])
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved { repository.devices.value.single { it.id == device.id }.let { it.avatarColor == 3 && it.avatarIcon == DeviceIcon.TABLET } }
        val avatar = compose.onNode(
            hasTestTag("device-avatar") and hasAnyAncestor(hasTestTag("device-card:${device.id}")),
            useUnmergedTree = true,
        )
        assertEquals("icon:TABLET", avatar.fetchSemanticsNode().config[AvatarContent])
        val inset = (4 * compose.activity.resources.displayMetrics.density).toInt()
        val fill = avatar.captureToImage().toPixelMap().let { it[inset, it.height / 2] }
        assertClose(AvatarPalette.light[3].first, fill)
    }

    @Test fun automaticAndInitialsCanBeChosenAgain() {
        val device = addTestDevice(color = 1, icon = DeviceIcon.PHONE)
        openEditor(device)
        expandLook()
        compose.onNodeWithContentDescription(text(R.string.avatar_color_indigo)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.device_icon_phone)).performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription(text(R.string.device_look_automatic)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.device_icon_initials)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        awaitSaved { repository.devices.value.single { it.id == device.id }.let { it.avatarColor == null && it.avatarIcon == null } }
        val avatar = compose.onNode(
            hasTestTag("device-avatar") and hasAnyAncestor(hasTestTag("device-card:${device.id}")),
            useUnmergedTree = true,
        )
        assertTrue(avatar.fetchSemanticsNode().config[AvatarContent].startsWith("initials:"))
    }

    @Test fun theLookStartsFoldedIntoOneRowThatSaysWhatItIs() {
        // Nine colours and five kinds open by default pushed the editor's Save under the keyboard.
        val device = addTestDevice(color = 3, icon = DeviceIcon.TV)
        openEditor(device)
        compose.onNodeWithContentDescription(text(R.string.avatar_color_pink)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.device_look_summary, text(R.string.avatar_color_pink), text(R.string.device_icon_tv)))
            .performScrollTo().assertIsDisplayed()
        assertEquals("icon:TV", compose.onNodeWithTag("device-look-preview", useUnmergedTree = true)
            .fetchSemanticsNode().config[AvatarContent])
        expandLook()
        compose.onNodeWithContentDescription(text(R.string.avatar_color_pink)).performScrollTo().assertIsSelected()
    }

    @Test fun openingTheLookPutsTheKeyboardAwayFromItsChoices() {
        // Choosing a look types nothing, and the keyboard left up covered the choices just opened.
        val device = addTestDevice()
        openEditor(device)
        assertInTouchMode(compose.activity)
        val name = compose.onNodeWithText(text(R.string.field_name_optional))
        name.performClick().assertIsFocused()
        expandLook()
        name.assertIsNotFocused()
    }

    @Test fun openedAtTheFootOfAShortFormTheChoicesScrollIntoView() {
        var expanded by mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(640.dp))
                        DeviceLookChoices("Living room", automaticColor = 0, color = null, icon = null, enabled = true,
                            expanded = expanded, onExpandedChange = { expanded = it }, onColorChange = {}, onIconChange = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        // Scrolled to, the folded row sits at the form's lower edge; opened, its choices start below it.
        expandLook()
        compose.onNodeWithContentDescription(text(R.string.avatar_color_blue)).assertIsDisplayed()
    }

    @Test fun withAKeyboardTheLookKeepsFocusAsItOpens() {
        // Taking focus away, to put a soft keyboard down, left a keyboard user's focus nowhere, and
        // Android then gave it to the form's first field.
        val device = addTestDevice()
        openEditor(device)
        // A key from a keyboard takes the screen out of touch mode, as a keyboard user's first does.
        pressKey(KeyEvent.KEYCODE_TAB)
        val toggle = compose.onNodeWithTag("device-look-toggle").performScrollTo().requestFocus()
        toggle.assertIsFocused()
        // Tab went to the form's first field, whose soft keyboard is offered keys first until it
        // has gone: an Enter in that moment opened nothing. A keyboard user's next key comes later.
        awaitNoSoftKeyboard(toggle)
        pressKey(KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithContentDescription(text(R.string.avatar_color_pink)).assertExists()
        toggle.assertIsFocused()
    }

    private fun pressKey(code: Int) {
        val down = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            assertTrue("The key reached the screen",
                InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
        }
        compose.waitForIdle()
    }

    private fun awaitNoSoftKeyboard(node: SemanticsNodeInteraction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // The editor's own window: the screen's says nothing of a keyboard over a dialog.
        val view = (node.fetchSemanticsNode().root as ViewRootForTest).view
        compose.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true
        }
        compose.waitForIdle()
    }

    private fun expandLook() {
        compose.onNodeWithTag("device-look-toggle").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertClose(expected: Color, actual: Color) {
        val distance = abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue)
        assertTrue("Expected $expected, was $actual", distance < 0.06f)
    }

    private fun addTestDevice(color: Int? = null, icon: DeviceIcon? = null): SavedDevice {
        val id = "device-look-test-${UUID.randomUUID()}"
        val device = SavedDevice(id = id, name = "Look $id", host = "127.0.0.1", avatarColor = color, avatarIcon = icon)
        testIds += id
        compose.runOnIdle { assertTrue(repository.saveDevice(device)) }
        return device
    }

    private fun openEditor(device: SavedDevice) {
        compose.onNodeWithText(text(R.string.device_list_search)).performTextReplacement(device.id)
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
        compose.onNodeWithTag("saved-device-list").performScrollToNode(
            hasContentDescription(text(R.string.device_list_more_actions, device.name)),
        )
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name)).performClick()
        compose.onNodeWithText(text(R.string.edit)).performClick()
        compose.waitForIdle()
    }

    private fun awaitSaved(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            predicate() && compose.onAllNodesWithTag("storage-busy").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}
