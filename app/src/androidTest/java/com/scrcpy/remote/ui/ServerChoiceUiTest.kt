package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Server version is informational, never a selectable setting. */
@RunWith(AndroidJUnit4::class)
class ServerChoiceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun globalSettingsHaveNoServerSelector() {
        show { SettingsDialog(MirrorSettings(), {}, { _, _ -> }) }
        assertNoServerChoices()
    }

    @Test fun savedDeviceSettingsHaveNoServerSelector() {
        val device = SavedDevice(name = "Tablet", host = "10.0.2.2",
            mirroringSettings = DeviceMirroringSettings.from(MirrorSettings()))
        show { DeviceSettingsDialog(device, MirrorSettings(), onDismiss = {}, onSave = { _, _ -> }) }
        assertNoServerChoices()
    }

    @Test fun appInfoNamesOnlyTheCurrentServer() {
        show { AppInfoDialog(onDismiss = {}) }
        compose.onNodeWithText(text(R.string.app_info_server_value, "5.0.1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("scrcpy 4.1", substring = true).assertDoesNotExist()
    }

    private fun assertNoServerChoices() {
        compose.onNode(hasText("scrcpy 5.0.1", substring = true) and isSelectable()).assertDoesNotExist()
        compose.onNode(hasText("scrcpy 4.1", substring = true) and isSelectable()).assertDoesNotExist()
        compose.onNodeWithText("scrcpy server", ignoreCase = true).assertDoesNotExist()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent { ScrcpyRemoteTheme(darkTheme = false) { content() } }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
