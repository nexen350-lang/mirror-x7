package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.CheckupStep
import com.scrcpy.remote.session.ConnectionCheckup
import com.scrcpy.remote.session.ConnectionRecoveryPolicy
import com.scrcpy.remote.session.StageStatus
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult

@RunWith(AndroidJUnit4::class)
class ReleaseUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun deviceCardsKeepFullAccessibleNamesAndSeparateActionsOnSmallScreens() {
        val device = SavedDevice(name = "A very long Android device name for the living room display and guest profile",
            host = "2001:db8:1234:5678:90ab:cdef:1234:5678", mirroringSettings = DeviceMirroringSettings())
        var connections = 0
        var favorites = 0
        render(width = 280, height = 500, fontScale = 1.5f) {
            DeviceRow(device, { connections++ }, {}, {}, {}, {}, {}, {}, {}, { favorites++ })
        }
        compose.onNodeWithContentDescription(text(R.string.device_list_add_favorite, device.name))
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name))
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(1, favorites); assertEquals(0, connections) }
        compose.onNodeWithContentDescription(text(R.string.device_list_connect, device.name))
            .assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test fun theSettingsBadgeOnADeviceCardOpensThatDevicesSettings() {
        // It looked like a button and did nothing; the same settings were two taps away in the menu.
        val device = SavedDevice(name = "Living room tablet", host = "192.0.2.4")
        var settings = 0
        var connections = 0
        render(width = 280, height = 500, fontScale = 2f) {
            DeviceRow(device, { connections++ }, {}, {}, { settings++ }, {}, {}, {}, {}, {})
        }
        val label = text(R.string.device_list_settings_action, device.name)
        // Large text in a narrow card once left the badge a sliver beside Connect: "setting" over "s".
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text(R.string.device_settings_global_summary), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val badge = layouts.single()
        val shown = badge.layoutInput.text.text
        assertTrue("Broken mid-word: ${(0 until badge.lineCount).map { shown.substring(badge.getLineStart(it), badge.getLineEnd(it)) }}",
            (1 until badge.lineCount).all { shown[badge.getLineStart(it) - 1].isWhitespace() })
        compose.onNodeWithText(text(R.string.device_settings_global_summary))
            .assertIsDisplayed().assertHasClickAction()
            .assert(SemanticsMatcher("is announced as changing ${device.name}'s settings") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == label
            })
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, settings); assertEquals(0, connections) }
        compose.onNodeWithText(text(R.string.device_settings_global_summary))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(2, settings); assertEquals(0, connections) }
    }

    @Test fun aLongAddressWrapsAtItsSeparatorsRatherThanInsideANumber() {
        // At large text it wrapped as "192.168.1.20:55" over "55".
        val device = SavedDevice(name = "Office phone", host = "192.168.100.200", port = 41234)
        render(width = 280, height = 500, fontScale = 2f) {
            DeviceRow(device, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
        val layouts = mutableListOf<TextLayoutResult>()
        // Still read, and found, as the address it is.
        compose.onNodeWithText(device.address, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val address = layouts.single()
        val shown = address.layoutInput.text.text
        val lines = (0 until address.lineCount).map { shown.substring(address.getLineStart(it), address.getLineEnd(it)) }
        assertTrue("Wraps: $lines", address.lineCount >= 2)
        assertTrue("Broken inside a number: $lines",
            (1 until address.lineCount).all { shown[address.getLineStart(it) - 1] == '\u200B' })
    }

    @Test fun anEmptyListOffersNoSearchUntilThereIsSomethingToSearch() {
        // A search box over an empty list invited typing an address into it, where nothing connects.
        val devices = mutableStateOf<List<SavedDevice>>(emptyList())
        val query = mutableStateOf("")
        render(width = 360, height = 640) {
            DeviceList(devices.value, query.value, { query.value = it }, false, {},
                PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        }
        compose.onNodeWithTag("saved-device-list")
            .performScrollToNode(hasText(text(R.string.release_empty_title)))
        compose.onNodeWithText(text(R.string.release_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.device_list_search)).assertDoesNotExist()
        compose.runOnIdle { devices.value = listOf(SavedDevice(name = "Tablet", host = "127.0.0.1")) }
        compose.onNodeWithText(text(R.string.device_list_search)).assertExists()
        // A search still typed when the last device goes stays, so it can be cleared.
        compose.runOnIdle { query.value = "Tab"; devices.value = emptyList() }
        compose.onNodeWithText(text(R.string.device_list_search)).assertExists()
    }

    @Test fun emptyAndFilteredListsProvideClearGuidanceWithoutPretendingDevicesAreOnline() {
        val device = SavedDevice(name = "Tablet", host = "127.0.0.1")
        val devices = mutableStateOf<List<SavedDevice>>(emptyList())
        val query = mutableStateOf("")
        render(width = 320, height = 460, dark = true) {
            DeviceList(devices.value, query.value, { query.value = it }, false, {},
                PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        }
        compose.onNodeWithTag("saved-device-list")
            .performScrollToNode(hasText(text(R.string.release_empty_title)))
        compose.onNodeWithText(text(R.string.release_empty_title)).assertIsDisplayed()
        compose.runOnIdle { devices.value = listOf(device); query.value = "not found" }
        compose.onNodeWithTag("saved-device-list")
            .performScrollToNode(hasText(text(R.string.release_no_matches_action)))
        compose.onNodeWithText(text(R.string.release_no_matches_action)).performClick()
        compose.runOnIdle { assertEquals("", query.value) }
        compose.onNodeWithTag("saved-device-list").performScrollToNode(hasText(device.name))
        compose.onNodeWithText(device.name).assertIsDisplayed()
    }

    @Test fun connectionErrorAndCancelRemainReachableWithLongMessagesAndLargeText() {
        var retries = 0
        var dismissed = 0
        val connecting = mutableStateOf(false)
        render(width = 320, height = 280, dark = true, fontScale = 1.4f) {
            ConnectionStatus("A detailed connection failure. ".repeat(25), connecting.value,
                onDismiss = { dismissed++ }, onRetry = { retries++ })
        }
        compose.onNodeWithText(text(R.string.reconnect)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.back_to_devices)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, dismissed); connecting.value = true }
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, dismissed) }
    }

    @Test fun specificFailureGuidanceIsNotFollowedByGenericAdvice() {
        // A message that already names the cause must not be repeated in weaker, generic terms;
        // the generic hint stays for failures the app could not classify.
        render(width = 360, height = 640, dark = true) {
            ConnectionStatus("Could not reach 192.168.1.44:5555.", connecting = false,
                onDismiss = {}, onRetry = {}, showGenericHint = false)
        }
        compose.onNodeWithText("Could not reach 192.168.1.44:5555.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.release_connection_check)).assertDoesNotExist()
    }

    @Test fun unclassifiedFailuresKeepTheGenericAdvice() {
        render(width = 360, height = 640, dark = true) {
            ConnectionStatus("SocketException", connecting = false,
                onDismiss = {}, onRetry = {}, showGenericHint = true)
        }
        compose.onNodeWithTag("connection-status-body")
            .performScrollToNode(hasText(text(R.string.release_connection_check)))
        compose.onNodeWithText(text(R.string.release_connection_check)).assertIsDisplayed()
    }

    @Test fun theCheckupReadsEachStepWholeAndKeepsItsButtonsAtNarrowWidthAndDoubleText() {
        val endpoint = "192.0.2.20:41234"
        val checkup = ConnectionCheckup(
            endpoint = endpoint,
            steps = listOf(
                CheckupStep(CheckupStage.REACH, StageStatus.PASSED, null),
                CheckupStep(CheckupStage.AUTHORIZE, StageStatus.FAILED, 10_000),
            ) + listOf(CheckupStage.DEPLOY, CheckupStage.START, CheckupStage.MEDIA, CheckupStage.SESSION)
                .map { CheckupStep(it, StageStatus.NOT_REACHED) },
            failure = ConnectionRecoveryPolicy.FailureKind.NOT_RESPONDING,
        )
        var retries = 0
        var dismissed = 0
        render(width = 240, height = 480, dark = true, fontScale = 2f) {
            ConnectionStatus("$endpoint is not responding.", connecting = false, onDismiss = { dismissed++ },
                onRetry = { retries++ }, showGenericHint = false, checkup = checkup)
        }
        val body = compose.onNodeWithTag("connection-status-body")
        // One node per step, so a screen reader hears its name and outcome together.
        body.performScrollToNode(hasTestTag("checkup-reach"))
        compose.onNodeWithTag("checkup-reach").assertIsDisplayed()
            .assert(hasText(text(R.string.checkup_stage_reach, endpoint)))
            .assert(hasText(text(R.string.checkup_status_passed)))
        body.performScrollToNode(hasTestTag("checkup-authorize"))
        compose.onNodeWithTag("checkup-authorize").assertIsDisplayed()
            .assert(hasText(text(R.string.checkup_stage_authorize)))
            .assert(hasText(text(R.string.checkup_status_failed_timed, String.format(Locale.getDefault(), "%.1f", 10.0))))
            .assert(hasText(text(R.string.checkup_next, text(R.string.checkup_action_authorize))))
        body.performScrollToNode(hasTestTag("checkup-session"))
        compose.onNodeWithTag("checkup-session").assertIsDisplayed()
            .assert(hasText(text(R.string.checkup_status_not_reached)))
        // Only the step that failed carries advice.
        assertEquals(1, compose.onAllNodes(hasText(text(R.string.checkup_next, ""), substring = true))
            .fetchSemanticsNodes().size)
        compose.onNodeWithText(text(R.string.reconnect)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.back_to_devices)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(1, dismissed) }
    }

    @Test fun aLostSessionIsNotGivenADurationAndARunningStepIsMarkedInProgress() {
        val done = listOf(CheckupStep(CheckupStage.REACH, StageStatus.PASSED, null),
            CheckupStep(CheckupStage.AUTHORIZE, StageStatus.PASSED, 40)) +
            CheckupStage.entries.subList(2, 5).map { CheckupStep(it, StageStatus.PASSED, 1_500) }
        val lost = ConnectionCheckup("phone.local:5555", done + CheckupStep(CheckupStage.SESSION, StageStatus.FAILED, 600_000),
            ConnectionRecoveryPolicy.FailureKind.TRANSIENT_TRANSPORT)
        val shown = mutableStateOf(lost)
        render(width = 360, height = 720, dark = false) {
            ConnectionStatus("Lost.", connecting = false, onDismiss = {}, checkup = shown.value)
        }
        compose.onNodeWithTag("connection-status-body").performScrollToNode(hasTestTag("checkup-session"))
        compose.onNodeWithTag("checkup-session")
            .assert(hasText(text(R.string.checkup_status_lost)))
            .assert(hasText(text(R.string.checkup_next, text(R.string.checkup_action_network))))
        compose.onNodeWithTag("checkup-authorize").assert(hasText(text(R.string.checkup_status_passed_ms, 40)))
        compose.onNodeWithTag("checkup-media")
            .assert(hasText(text(R.string.checkup_status_passed_timed, String.format(Locale.getDefault(), "%.1f", 1.5))))
        compose.runOnIdle {
            shown.value = ConnectionCheckup("phone.local:5555", listOf(CheckupStep(CheckupStage.REACH, StageStatus.RUNNING)) +
                CheckupStage.entries.drop(1).map { CheckupStep(it, StageStatus.NOT_REACHED) })
        }
        compose.onNodeWithTag("connection-status-body").performScrollToNode(hasTestTag("checkup-reach"))
        compose.onNodeWithTag("checkup-reach").assert(hasText(text(R.string.checkup_status_running)))
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.checkup_next, ""), substring = true))
            .fetchSemanticsNodes().size)
    }

    @Test fun labeledControlsAndKeyboardWorkInsideALightThemedActivity() {
        val sent = mutableListOf<String>()
        render(width = 320, height = 600, dark = false) {
            FloatingControlsOverlay(actions().copy(onText = { sent += it; true }, screenshotBusy = true), false)
        }
        compose.onNodeWithContentDescription(text(R.string.control_show)).performClick()
        compose.onNodeWithContentDescription(text(R.string.compact_more_controls)).performClick()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performScrollTo()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(text(R.string.ui_keyboard_draft)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_text_draft)).performScrollTo().performTextReplacement("Hello from Android")
        // The draft has to have actually landed in the field before Send is pressed. Under full
        // suite load the replacement could still be in flight, so Send committed an empty draft
        // and nothing was ever sent — which looked exactly like the send being broken.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("Hello from Android", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.ui_send_text))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(timeoutMillis = 10_000) { sent.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("Hello from Android"), sent) }
        compose.onNodeWithText(text(R.string.exit)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.control_keyboard)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription(text(R.string.capture_busy)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.hide)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.exit)).assertDoesNotExist()
    }

    @Test fun settingsSectionsRemainScrollableWithoutMovingTheSaveAction() {
        render(width = 320, height = 560) {
            SettingsDialog(MirrorSettings(), {}, { _, _ -> })
        }
        for (label in listOf(R.string.release_video_heading, R.string.release_audio_heading,
            R.string.setting_playback_sync, R.string.release_connection_heading,
            R.string.release_device_heading, R.string.release_input_heading)) {
            compose.onNodeWithText(text(label)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.save)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed()
        }
    }

    @Test fun textAndButtonColorPairsHaveReadableContrastInBothThemes() {
        val dark = mutableStateOf(false)
        var colors: ColorScheme? = null
        compose.runOnUiThread {
            compose.activity.setContent { ScrcpyRemoteTheme(darkTheme = dark.value) { colors = MaterialTheme.colorScheme } }
        }
        for (value in listOf(false, true)) {
            compose.runOnIdle { dark.value = value }
            compose.waitForIdle()
            compose.runOnIdle {
                val scheme = checkNotNull(colors)
                for ((foreground, background) in listOf(scheme.onSurface to scheme.surfaceContainer,
                    scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
                    scheme.onSurfaceVariant to scheme.surfaceContainerLowest,
                    scheme.onSurfaceVariant to scheme.surface,
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onSecondaryContainer to scheme.secondaryContainer)) {
                    val actual = contrast(foreground, background)
                    assertTrue("Composited text contrast must be at least 4.5:1, was $actual (dark=$value)", actual >= 4.5)
                }
            }
        }
    }

    private fun render(width: Int, height: Int, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Surface { Box(Modifier.requiredSize(width.dp, height.dp)) { content() } }
                    }
                }
            }
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val rendered = foreground.compositeOver(background)
        return (maxOf(rendered.luminance(), background.luminance()) + 0.05) /
            (minOf(rendered.luminance(), background.luminance()) + 0.05)
    }

    private fun actions() = MirrorActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { true }, onBackspace = {}, onEnter = {}, onDisconnect = {})
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}