package com.scrcpy.remote.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.BuildConfig
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun mainSettingsShowTheBuildsIdentityAndItsLegalPages() {
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        // Last in the sheet, under About, where settings screens keep it.
        compose.onNodeWithText(text(R.string.app_info_title)).performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("app-info-body").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.APPLICATION_ID).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BuildConfig.BUILD_TYPE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_privacy_policy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_licenses)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_back_to_settings)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.settings_title)).assertIsDisplayed()
    }

    @Test fun thePrivacyPolicyOpensFromAppInfoUnderTheAppsName() {
        show()
        compose.onNodeWithText(text(R.string.app_info_privacy_policy)).performScrollTo().performClick()
        compose.onNodeWithText("Privacy policy for ${text(R.string.app_name)}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("What the app handles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Children").performScrollTo().assertIsDisplayed()
        // A local build names no developer, so the policy points to the store listing instead.
        compose.onAllNodesWithText(PrivacyPolicy.UNNAMED_DEVELOPER, substring = true).fetchSemanticsNodes()
            .let { assertTrue(it.isNotEmpty()) }
        compose.onAllNodesWithText("{{", substring = true).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.app_info_back)).performClick()
        compose.onNodeWithText(text(R.string.app_info_licenses)).performScrollTo().assertIsDisplayed()
    }

    @Test fun everyBundledComponentIsListedAndOpensItsLicense() {
        val notices = OpenSourceNotices.parse(compose.activity.assets.open(OpenSourceNotices.INDEX).bufferedReader().readText())
        show()
        compose.onNodeWithText(text(R.string.app_info_licenses)).performScrollTo().performClick()
        for (component in notices.components) {
            compose.onNodeWithText(component.name).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Conscrypt").performScrollTo().performClick()
        compose.onNodeWithText("This product contains a modified portion of `Netty`", substring = true)
            .performScrollTo().assertIsDisplayed()
        // Conscrypt's own license and BoringSSL's, which its native code is built from.
        compose.onNodeWithText("Path = constants/src/gen/java/cert_verify_proc_blocklist.inc", substring = true).assertExists()
        compose.onNodeWithText("Licenses for support code", substring = true).assertExists()
        compose.onNodeWithText(text(R.string.app_info_back)).performClick()
        compose.onNodeWithText("Bouncy Castle").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_back)).performClick()
        compose.onNodeWithText(text(R.string.app_info_privacy_policy)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aLicenseStaysOpenAcrossRecreation() {
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        compose.onNodeWithText(text(R.string.app_info_title)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.app_info_licenses)).performScrollTo().performClick()
        compose.onNodeWithText("scrcpy server").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Copyright (C) 2018-2026 Romain Vimont", substring = true)
            .fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(text(R.string.app_info_back)).performClick()
        compose.onNodeWithText("libadb-android").performScrollTo().assertIsDisplayed()
    }

    @Test fun theDeveloperIsShownOnlyWhenTheBuildNamesOne() {
        show()
        compose.onAllNodesWithText(text(R.string.app_info_developer)).assertCountEquals(0)
        compose.onAllNodesWithText(text(R.string.app_info_view_online)).assertCountEquals(0)
        show(PublisherInfo("Jane Doe", "privacy@example.com", "https://example.com/privacy"))
        compose.onNodeWithText("Jane Doe").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("privacy@example.com").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_privacy_policy)).performScrollTo().performClick()
        compose.onNodeWithText("published by Jane Doe", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_view_online)).assertIsDisplayed()
    }

    @Test fun returningFromAppInfoAndRestoringStatePreserveTheUnsavedSettingsDraft() {
        var saved: MirrorSettings? = null
        val baseline = MirrorSettings(showTouches = false)
        val mounted = mutableStateOf(true)
        compose.runOnUiThread {
            compose.activity.setContent {
                val holder = rememberSaveableStateHolder()
                if (mounted.value) holder.SaveableStateProvider("settings") {
                    ScrcpyRemoteTheme { SettingsDialog(baseline, {}, { _, edited -> saved = edited }) }
                }
            }
        }
        compose.onNodeWithText(text(R.string.setting_show_touches)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.app_info_title)).performScrollTo().performClick()
        compose.runOnIdle { mounted.value = false }
        compose.runOnIdle { mounted.value = true }
        compose.onNodeWithTag("app-info-body").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_back_to_settings)).performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(baseline.copy(showTouches = true), saved) }
    }

    @Test fun appInfoIsScrollableAtDoubleTextWithoutHidingTheReturnAction() {
        val density = compose.activity.resources.displayMetrics.density
        val closed = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme {
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        AppInfoDialog { closed.value = true }
                    }
                }
            }
        }
        compose.onNodeWithText(text(R.string.app_info_licenses)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.app_info_back_to_settings)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(closed.value) }
    }

    private fun show(publisher: PublisherInfo = PublisherInfo.fromBuild()) {
        compose.runOnUiThread {
            compose.activity.setContent {
                androidx.compose.runtime.key(publisher) {
                    ScrcpyRemoteTheme { AppInfoDialog(publisher = publisher, onDismiss = {}) }
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
