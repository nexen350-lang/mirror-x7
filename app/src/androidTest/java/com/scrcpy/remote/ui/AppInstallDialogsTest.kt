package com.scrcpy.remote.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.session.ApkDetails
import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.AppBundle
import com.scrcpy.remote.session.AppInstallOutcome
import com.scrcpy.remote.session.AppOffer
import com.scrcpy.remote.session.BundleSummary
import com.scrcpy.remote.session.DeviceAppsState
import com.scrcpy.remote.session.FileTransferState
import com.scrcpy.remote.session.FileTransfers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The questions and answers around installing an app on the device. */
@RunWith(AndroidJUnit4::class)
class AppInstallDialogsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val tool = ApkDetails(
        packageName = "com.example.tool",
        label = "Pocket Tool",
        versionName = "2.0",
        versionCode = 2,
        icon = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.BLUE) },
    )

    @Test fun aSentAppShowsWhatItIsAndOffersInstallCopyOrCancel() {
        val offer = AppOffer(name = "pocket-tool.apk", size = 8_593, canCopy = true, reading = false, app = tool)
        val chosen = mutableListOf<String>()
        render { AppOfferDialog(offer, { chosen += "install" }, { chosen += "copy" }, { chosen += "cancel" }) }
        compose.onNodeWithText(text(R.string.install_offer_choice_title)).assertIsDisplayed()
        compose.onNodeWithText("Pocket Tool").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_offer_version, "2.0")).assertIsDisplayed()
        compose.onNodeWithText("com.example.tool").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_offer_file, "pocket-tool.apk", FileTransfers.formatSize(8_593)))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_offer_choice)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_copy_action)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.install_action)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf("copy", "install", "cancel"), chosen) }
    }

    @Test fun nothingCanBeChosenWhileTheAppIsStillBeingRead() {
        val offer = mutableStateOf(AppOffer(name = "", size = 0, canCopy = true))
        var cancelled = 0
        render { AppOfferDialog(offer.value, {}, {}, { cancelled++ }) }
        compose.onNodeWithText(text(R.string.install_offer_reading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.install_copy_action)).assertIsNotEnabled()
        // Leaving is always possible, and stops the reading.
        compose.onNodeWithText(text(R.string.cancel)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, cancelled)
            offer.value = AppOffer(name = "pocket-tool.apk", size = 1, canCopy = true, reading = false, app = tool)
        }
        compose.onNodeWithText(text(R.string.install_action)).assertIsEnabled()
    }

    @Test fun anAppThisPhoneCannotReadCanStillBeInstalled() {
        // An older phone cannot read an app made for a newer Android; the device decides.
        render { AppOfferDialog(AppOffer("future.apk", 10, canCopy = false, reading = false), {}, {}, {}) }
        compose.onNodeWithText(text(R.string.install_offer_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_offer_unread)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_action)).assertIsEnabled()
        // Picked to install, so copying it is not offered.
        compose.onNodeWithText(text(R.string.install_copy_action)).assertDoesNotExist()
    }

    @Test fun theChoiceStaysWholeWithDoubleSizeText() {
        val offer = AppOffer(name = "pocket-tool-release-candidate-build.apk", size = 8_593, canCopy = true,
            reading = false, app = tool.copy(label = "Pocket Tool with a rather long name for its launcher"))
        render(fontScale = 2f) { AppOfferDialog(offer, {}, {}, {}) }
        for (label in listOf(R.string.install_action, R.string.install_copy_action, R.string.cancel)) {
            compose.onNodeWithText(text(label)).assertIsDisplayed()
        }
        compose.onNodeWithTag("app-offer").performScrollToNode(hasText(text(R.string.install_offer_note)))
        compose.onNodeWithText(text(R.string.install_offer_note)).assertIsDisplayed()
    }

    @Test fun aBundleSaysHowManyFilesItHoldsAndThatTheDeviceGetsOnlyWhatItNeeds() {
        val offer = AppOffer(name = "pocket-tool.apks", size = 261_922, canCopy = false, reading = false, app = tool,
            bundle = BundleSummary(files = 18))
        render { AppOfferDialog(offer, {}, {}, {}) }
        compose.onNodeWithText("Pocket Tool").assertIsDisplayed()
        compose.onNodeWithTag("app-offer").performScrollToNode(hasText(plural(R.plurals.install_offer_bundle, 18)))
        compose.onNodeWithText(plural(R.plurals.install_offer_bundle, 18)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_action)).assertIsEnabled()
    }

    @Test fun aBundleThatCannotBeInstalledSaysWhyAndCanStillBeCopied() {
        for ((summary, reason) in listOf(
            BundleSummary(13, AppBundle.Problem.EXPANSION_FILES) to text(R.string.install_bundle_expansion),
            BundleSummary(3, AppBundle.Problem.UNMATCHABLE_PARTS, listOf("astc", "etc2")) to
                text(R.string.install_bundle_unmatchable, "astc, etc2"),
            BundleSummary(0, AppBundle.Problem.UNREADABLE) to text(R.string.install_bundle_unreadable),
        )) {
            val offer = AppOffer(name = "game.xapk", size = 1, canCopy = true, reading = false,
                app = tool.takeIf { summary.problem != AppBundle.Problem.UNREADABLE }, bundle = summary)
            render { key(summary) { AppOfferDialog(offer, {}, {}, {}) } }
            compose.onNodeWithTag("app-offer").performScrollToNode(hasText(reason))
            compose.onNodeWithText(reason).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.install_action)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.install_copy_action)).assertIsEnabled()
            if (summary.problem == AppBundle.Problem.UNREADABLE) {
                // Nothing in it was read, so there is no count to give.
                compose.onNodeWithText(plural(R.plurals.install_offer_bundle, summary.files)).assertDoesNotExist()
            }
        }
    }

    @Test fun aDeviceThatDidNotSayWhatItIsIsNotSaidToHaveRefused() {
        val detail = text(R.string.install_bundle_no_spec_detail)
        val refused = ApkInstall.Result.Refused(ApkInstall.Refusal.UNDESCRIBED_DEVICE, null, detail, local = true)
        render { AppInstallOutcomeDialog(AppInstallOutcome("Bundle fixture", "com.example.bundle", refused), false, {}, {}) }
        compose.onNodeWithText(text(R.string.install_refused_undescribed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_refused_other)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.install_checked_here, detail)).assertIsDisplayed()
    }

    @Test fun aBundlesSizeAppearsOnceTheDeviceHasSaidWhichFilesItNeeds() {
        val state = mutableStateOf(FileTransferState(running = true, name = "Bundle fixture", install = true))
        render { FileTransferDialog(state.value) {} }
        compose.onNodeWithText("Bundle fixture").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.send_file_progress, FileTransfers.formatSize(0), FileTransfers.formatSize(0)))
            .assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(totalBytes = 46_311, sentBytes = 12_756) }
        compose.onNodeWithText(text(R.string.send_file_progress, FileTransfers.formatSize(12_756), FileTransfers.formatSize(46_311)))
            .assertIsDisplayed()
    }

    @Test fun aRefusalDecidedOnThisPhoneIsNotPutInTheDevicesMouth() {
        val detail = text(R.string.install_bundle_processor_detail, "riscv64", "x86_64, arm64-v8a")
        val refused = ApkInstall.Result.Refused(ApkInstall.Refusal.WRONG_PROCESSOR, null, detail, local = true)
        render { AppInstallOutcomeDialog(AppInstallOutcome("Bundle fixture", "com.example.bundle", refused), false, {}, {}) }
        compose.onNodeWithText(text(R.string.install_failed_message, "Bundle fixture")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_refused_processor)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_checked_here, detail)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_device_said, detail)).assertDoesNotExist()
    }
    @Test fun anInstalledAppIsOpenedOnlyWhenTheDeviceListsIt() {
        val outcome = AppInstallOutcome("Pocket Tool", "com.example.tool", ApkInstall.Result.Installed)
        val openable = mutableStateOf(false)
        var opened = 0
        var done = 0
        render { AppInstallOutcomeDialog(outcome, openable.value, { opened++ }, { done++ }) }
        compose.onNodeWithText(text(R.string.install_done_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_done_message, "Pocket Tool")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_open_action)).assertDoesNotExist()
        compose.runOnIdle { openable.value = true }
        compose.onNodeWithText(text(R.string.install_open_action)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.done)).performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(1, done) }
    }

    @Test fun aRefusalSaysWhyInPlainWordsAndInTheDevicesOwn() {
        val refused = ApkInstall.parse("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n") as ApkInstall.Result.Refused
        render { AppInstallOutcomeDialog(AppInstallOutcome("Pocket Tool", "com.example.tool", refused), true, {}, {}) }
        compose.onNodeWithText(text(R.string.install_failed_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_failed_message, "Pocket Tool")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_refused_newer)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_device_said, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
            .assertIsDisplayed()
        // Nothing was installed, so there is nothing to open whatever the list says.
        compose.onNodeWithText(text(R.string.install_open_action)).assertDoesNotExist()
    }

    @Test fun aMissingAnswerIsNotCalledAFailure() {
        render {
            AppInstallOutcomeDialog(AppInstallOutcome("Pocket Tool", null, ApkInstall.Result.Unconfirmed), false, {}, {})
        }
        compose.onNodeWithText(text(R.string.install_unconfirmed_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_unconfirmed, "Pocket Tool")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.install_failed_title)).assertDoesNotExist()
    }

    @Test fun anAppOnItsWayToTheDeviceCanStillBeCancelled() {
        var cancelled = 0
        render {
            FileTransferDialog(FileTransferState(running = true, name = "Pocket Tool", sentBytes = 4_096,
                totalBytes = 8_593, install = true)) { cancelled++ }
        }
        compose.onNodeWithText(text(R.string.install_progress_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun whileTheDeviceInstallsTheMirroredScreenStaysUsable() {
        // The device may ask for confirmation on its own screen, and a modal dialog over the
        // mirror would stop the user answering it.
        val state = mutableStateOf(FileTransferState(running = true, name = "Pocket Tool", sentBytes = 1,
            totalBytes = 8_593, install = true))
        render {
            Box(Modifier.fillMaxSize()) {
                TransferProgress(state.value, inputLocked = false, lockBanner = InputLockBannerPlacement(), onCancel = {})
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(sentBytes = 8_593, committing = true, installing = true) }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("installing-indicator").assertIsDisplayed()
            .assert(hasContentDescription(text(R.string.install_indicator_description, "Pocket Tool")))
        compose.runOnIdle { state.value = FileTransferState() }
        compose.onNodeWithTag("installing-indicator").assertDoesNotExist()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun aPlainTransferKeepsItsTitleAndCancel() {
        render { FileTransferDialog(FileTransferState(running = true, name = "notes.txt", totalBytes = 10)) {} }
        compose.onNodeWithText(text(R.string.send_file_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).assertIsDisplayed()
    }

    @Test fun theAppListOffersToInstallAnApp() {
        var installs = 0
        val apps = DeviceAppsState(apps = listOf(DeviceApp("Clock", "com.example.clock", system = true)), loaded = true)
        render { DeviceAppsDialog(apps, { _, _ -> }, {}, {}, onInstall = { installs++ }) }
        compose.onNodeWithText(text(R.string.apps_install)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    private fun render(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                        Surface { Box(Modifier.fillMaxSize()) { content() } }
                    }
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun plural(id: Int, count: Int) = compose.activity.resources.getQuantityString(id, count, count)
}
