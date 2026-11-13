package com.scrcpy.remote.ui

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Settings are a long form, so they get the screen: a sheet whose body uses nearly all of it,
 * with Cancel and Save in a bar above the body rather than below a short scrolling window.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSheetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun globalSettingsUseNearlyTheWholeHeightWithTheirActionsAboveTheBody() {
        show { SettingsDialog(MirrorSettings(), {}, { _, _ -> }) }
        assertSheet("main-settings-body")
    }

    @Test fun deviceSettingsAreTheSameSheet() {
        val device = SavedDevice(name = "Living room tablet", host = "10.0.2.2",
            mirroringSettings = DeviceMirroringSettings.from(MirrorSettings()))
        show { DeviceSettingsDialog(device, MirrorSettings(), onDismiss = {}, onSave = { _, _ -> }) }
        assertSheet("device-settings-body")
    }

    @Test fun appInfoIsTheLastThingInSettingsUnderAbout() {
        show { SettingsDialog(MirrorSettings(), {}, { _, _ -> }) }
        // The last setting is the horizontal calibration slider, named by its axis. Unclipped
        // positions: both may be scrolled out of view.
        val lastSetting = compose.onNodeWithContentDescription(text(R.string.setting_horizontal_offset))
            .fetchSemanticsNode().boundsInWindow()
        val appInfo = compose.onNodeWithText(text(R.string.app_info_title)).fetchSemanticsNode().boundsInWindow()
        assertTrue("App info after the last setting: $appInfo vs $lastSetting", appInfo.top > lastSetting.bottom)
        compose.onNodeWithText(text(R.string.settings_about), ignoreCase = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_title)).performScrollTo().assertIsDisplayed()
    }

    @Test fun returningFromAppInfoFindsTheFormWhereItWasLeft() {
        show { SettingsDialog(MirrorSettings(), {}, { _, _ -> }) }
        compose.onNodeWithText(text(R.string.app_info_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.app_info_back_to_settings)).performClick()
        // At the bottom, where App info is, not back at the top of the whole form.
        compose.onNodeWithText(text(R.string.app_info_title)).assertIsDisplayed()
    }

    @Test fun theBarsHairlineShowsOnlyOnceTheFormScrollsUnderIt() {
        assumeTrue("Compose can only capture dialog pixels on Android 9+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        var scheme: ColorScheme? = null
        show {
            scheme = MaterialTheme.colorScheme
            SettingsDialog(MirrorSettings(), {}, { _, _ -> })
        }
        val colors = checkNotNull(scheme)
        fun hairline() = compose.onNodeWithTag("settings-sheet-hairline").captureToImage().toPixelMap()
            .let { it[it.width / 2, it.height / 2] }
        // At rest the first section's own divider is just below; a second line there doubled it.
        assertClose("No hairline at rest", colors.background, hairline())
        compose.onNodeWithTag("main-settings-body").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertClose("A hairline once the form is under the bar", colors.outlineVariant, hairline())
    }

    private fun assertClose(message: String, expected: Color, actual: Color) {
        val distance = abs(expected.red - actual.red) + abs(expected.green - actual.green) + abs(expected.blue - actual.blue)
        assertTrue("$message: expected $expected, was $actual", distance < 0.06f)
    }

    private fun assertSheet(bodyTag: String) {
        val window = compose.activity.window.decorView.height.toFloat()
        val body = compose.onNodeWithTag(bodyTag).fetchSemanticsNode().boundsInWindow()
        assertTrue("The body uses most of the height: ${body.height} of $window", body.height >= window * 0.7f)
        for (action in listOf(R.string.cancel, R.string.save)) {
            val bounds = compose.onNodeWithText(text(action)).assertIsDisplayed().fetchSemanticsNode().boundsInWindow()
            assertTrue("${text(action)} sits above the body: $bounds vs $body", bounds.bottom <= body.top + 1f)
        }
        // Save's fill ends where the form's groups do, 16 dp in, rather than nearer the edge.
        val save = compose.onNodeWithText(text(R.string.save)).fetchSemanticsNode().boundsInWindow()
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Save's end inset in dp", 16f, (body.right - save.right) / density, 0.75f)
    }

    private fun androidx.compose.ui.semantics.SemanticsNode.boundsInWindow(): Rect {
        val position = positionInWindow
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent { ScrcpyRemoteTheme(darkTheme = false) { content() } }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
