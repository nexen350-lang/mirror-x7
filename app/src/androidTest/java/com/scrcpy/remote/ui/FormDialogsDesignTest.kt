package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Form dialogs keep their fields together. A field reserved an empty line under itself for an
 * error that was not there, which spread three fields over most of the screen.
 *
 * An outlined field is 56 dp plus 8 dp above it for its floating label; with 12 dp between fields
 * the pitch is about 77 dp, and the empty line made it 93.
 */
@RunWith(AndroidJUnit4::class)
class FormDialogsDesignTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun addDeviceFieldsSitTogetherAndThePortExplainsItself() {
        show { DeviceEditorDialog(device = null, onDismiss = {}, onSave = {}) }
        val name = top(R.string.field_name_optional)
        val address = top(R.string.field_ip_address)
        assertTrue("Name and address are one field apart: ${(address - name) / density()} dp",
            address - name <= FIELD_PITCH * density())
        // The port's explanation is the port field's own helper line, not a card after the form.
        val port = compose.onNodeWithText(text(R.string.field_port)).fetchSemanticsNode().boundsInRoot
        val help = compose.onNodeWithText(text(R.string.port_help), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Port help inside the port field: $help in $port", help.top >= port.top && help.bottom <= port.bottom + 1f)
    }

    @Test fun anErrorStillTakesTheLineWhenThereIsOne() {
        show { DeviceEditorDialog(device = null, onDismiss = {}, onSave = {}) }
        compose.onNodeWithText(text(R.string.field_ip_address)).performTextReplacement("not an address!")
        compose.onNodeWithText(text(R.string.ui_host_error), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun noFieldIsFocusedUntilTapped() {
        // Android 8 focused the first field as the dialog opened: highlighted, with no keyboard.
        show { DeviceEditorDialog(device = null, onDismiss = {}, onSave = {}) }
        assertInTouchMode(compose.activity)
        for (field in listOf(R.string.field_name_optional, R.string.field_ip_address, R.string.field_port)) {
            compose.onNodeWithText(text(field)).assertIsNotFocused()
        }
    }

    @Test fun noPairingFieldIsFocusedUntilTapped() {
        show { PairDialog(PairingState.Idle, onPair = { _, _, _ -> }, onDismiss = {}) }
        assertInTouchMode(compose.activity)
        for (field in listOf(R.string.field_ip_address, R.string.field_pairing_port, R.string.field_pairing_code)) {
            compose.onNodeWithText(text(field)).assertIsNotFocused()
        }
    }

    @Test fun pairingFieldsSitTogether() {
        show { PairDialog(PairingState.Idle, onPair = { _, _, _ -> }, onDismiss = {}) }
        val address = top(R.string.field_ip_address)
        val port = top(R.string.field_pairing_port)
        assertTrue("Address and port are one field apart: ${(port - address) / density()} dp",
            port - address <= FIELD_PITCH * density())
    }

    private fun top(label: Int) = compose.onNodeWithText(text(label)).fetchSemanticsNode().boundsInRoot.top

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent { ScrcpyRemoteTheme(darkTheme = false) { content() } }
        }
        compose.waitForIdle()
    }

    private fun density() = compose.activity.resources.displayMetrics.density

    private companion object {
        /** One field and its spacing, with no line under it: well short of the 93 dp an empty line made. */
        const val FIELD_PITCH = 84
    }
    private fun text(id: Int) = compose.activity.getString(id)
}
