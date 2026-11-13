package com.scrcpy.remote.session

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.DeviceApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * The device's own App info and uninstall pages, opened through a real session over the loopback
 * adbd of a disposable emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * Uses the install fixture from tools/make-install-fixtures.ps1 as the app, so nothing the
 * emulator came with is ever offered for removal.
 */
@RunWith(AndroidJUnit4::class)
class AppPagesLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val displays = app.getSystemService(DisplayManager::class.java)

    @Before fun seeEveryWindow() {
        // A run that failed midway can leave its overlay display behind.
        shell("settings delete global overlay_display_devices")
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @After fun cleanUp() {
        // Whatever page a failed run left open on the device goes, and the fixture with it.
        repeat(3) { if (resumedActivity()?.contains("UninstallerActivity") == true) shell("input keyevent 4") }
        shell("input keyevent 3")
        shell("pm uninstall $FIXTURE")
        TestShareProvider.clear(app)
    }

    @Test fun appInfoOpensTheDevicesOwnPageForTheAppAskedFor() {
        val port = livePort()
        withSession(port) { model ->
            installFixture(model)
            goHome()
            assertTrue(main { model.openAppPage(FIXTURE_APP, DeviceAppPages.Page.APP_INFO) })
            await("the device's App info for the fixture", { "resumed: ${resumedActivity()}; showing: ${shownTexts()}" }) {
                resumedActivity()?.takeIf { "com.android.settings/" in it && showing(FIXTURE_LABEL) }
            }
            // The fixture's page left in Settings' task by a display that has since gone. Android 16
            // then only brought that task back for a plain `am start`, showing the fixture again.
            strandAppInfo(FIXTURE)
            goHome()
            assertTrue(main { model.openAppPage(SETTINGS_APP, DeviceAppPages.Page.APP_INFO) })
            await("the device's App info for Settings itself", { "resumed: ${resumedActivity()}; showing: ${shownTexts()}" }) {
                resumedActivity()?.takeIf {
                    "com.android.settings/" in it && showing(SETTINGS_APP.name) && !showing(FIXTURE_LABEL)
                }
            }
            assertNull("Opened, so there is nothing to report", model.notice.value)
            assertTrue("The session carries on", model.state.value is MirrorUiState.Connected)
        }
    }

    @Test fun aSessionOnASecondaryDisplayGetsAppInfoOnThatDisplay() {
        val port = livePort()
        shell("settings put global overlay_display_devices 720x480/160")
        try {
            val display = overlayDisplay()
            shell("input keyevent 3")
            withSession(port, MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false,
                displayId = display)) { model ->
                installFixture(model)
                assertTrue(main { model.openAppPage(FIXTURE_APP, DeviceAppPages.Page.APP_INFO) })
                await("App info on display $display") {
                    displaySection(display)?.takeIf { section ->
                        section.lineSequence().any { "ResumedActivity" in it && "com.android.settings/" in it }
                    }
                }
                val primary = requireNotNull(displaySection(0))
                assertFalse("Not on the main screen behind it: $primary", primary.lineSequence()
                    .any { "ResumedActivity" in it && "com.android.settings/" in it })
            }
        } finally {
            shell("settings delete global overlay_display_devices")
        }
    }

    @Test fun uninstallingIsTheDevicesQuestionAndCancellingKeepsTheApp() {
        val port = livePort()
        withSession(port) { model ->
            installFixture(model)
            listed(model)
            assertTrue(main { model.openAppPage(FIXTURE_APP, DeviceAppPages.Page.UNINSTALL) })
            awaitUninstallQuestion()
            await("the list marked for reloading") { model.apps.value.takeIf { !it.loaded } }
            assertTrue("Nothing is removed before the device is answered", installed())
            shell("input keyevent 4")
            await("the question gone") { resumedActivity()?.takeIf { "UninstallerActivity" !in it } }
            assertTrue("Cancelled on the device, so still installed", installed())
            assertTrue(model.state.value is MirrorUiState.Connected)
            main { model.loadApps() }
            await("the reloaded list") { model.apps.value.takeIf { it.loaded } }
            // Opened again, the list asks whether the app is still there, and keeps it.
            main { model.loadApps() }
            await("the check answered") { true.takeIf { !main { model.appRemovalCheckRunning() } } }
            assertTrue("Still listed", model.apps.value.apps.any { it.packageName == FIXTURE })
        }
    }

    @Test fun answeringTheDeviceRemovesTheAppAndTheListReloadsWithoutIt() {
        val port = livePort()
        withSession(port) { model ->
            installFixture(model)
            listed(model)
            assertTrue(main { model.openAppPage(FIXTURE_APP, DeviceAppPages.Page.UNINSTALL) })
            awaitUninstallQuestion()
            answerOk()
            await("removed by the device") { true.takeIf { !installed() } }
            main { model.loadApps() }
            val reloaded = await("the reloaded list") { model.apps.value.takeIf { it.loaded } }
            assertTrue("No longer listed", reloaded.apps.none { it.packageName == FIXTURE })
            assertTrue(reloaded.apps.isNotEmpty())
        }
    }

    @Test fun aListReloadedWhileTheQuestionIsOpenDropsTheAppOnceTheDeviceIsAnswered() {
        // Found by review: a reload while the question was still open listed the app again and
        // was trusted from then on, so an app removed on the device stayed listed all session.
        val port = livePort()
        withSession(port) { model ->
            installFixture(model)
            listed(model)
            assertTrue(main { model.openAppPage(FIXTURE_APP, DeviceAppPages.Page.UNINSTALL) })
            awaitUninstallQuestion()
            await("the list marked for reloading") { model.apps.value.takeIf { !it.loaded } }
            main { model.loadApps() }
            val meanwhile = await("the list reloaded while the question is open") { model.apps.value.takeIf { it.loaded } }
            assertTrue("Not answered yet, so still listed", meanwhile.apps.any { it.packageName == FIXTURE })
            answerOk()
            await("removed by the device") { true.takeIf { !installed() } }
            main { model.loadApps() }
            await("the list without the fixture", { "listed: ${model.apps.value.apps.map { it.packageName }}" }) {
                model.apps.value.takeIf { state -> state.loaded && state.apps.none { it.packageName == FIXTURE } }
            }
            assertTrue("The rest of the list stays", model.apps.value.apps.isNotEmpty())
        }
    }

    @Test fun aRefusalOrALostAnswerIsReportedAndTheSessionCarriesOn() {
        val port = livePort()
        withSession(port) { model ->
            val commands = mutableListOf<String>()
            assertTrue(main {
                model.openAppPage(NOTES, DeviceAppPages.Page.APP_INFO) { _, command -> commands += command; REFUSAL }
            })
            await("the refusal reported") {
                model.notice.value?.takeIf { it == app.getString(R.string.app_info_failed, NOTES.name) }
            }
            assertEquals("On the main display, so no display is named",
                listOf("am start --activity-clear-task -a android.settings.APPLICATION_DETAILS_SETTINGS " +
                    "-d package:${NOTES.packageName}"), commands)
            main { model.clearNotice() }
            assertTrue(main {
                model.openAppPage(NOTES, DeviceAppPages.Page.UNINSTALL) { _, _ -> throw IOException("Stream closed") }
            })
            await("the lost answer reported") {
                model.notice.value?.takeIf { it == app.getString(R.string.app_uninstall_failed, NOTES.name) }
            }
            SystemClock.sleep(500)
            assertTrue("A page that did not open never ends the session", model.state.value is MirrorUiState.Connected)
        }
    }

    @Test fun aSessionOnADisplayMadeForItOpensNoPages() {
        val port = livePort()
        withSession(port, MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false, virtualDisplay = "")) { model ->
            val commands = mutableListOf<String>()
            assertFalse(main {
                model.openAppPage(NOTES, DeviceAppPages.Page.APP_INFO) { _, command -> commands += command; "" }
            })
            assertEquals(app.getString(R.string.app_pages_unavailable), model.notice.value)
            assertEquals("Nothing was run on the device", emptyList<String>(), commands)
        }
    }

    private fun installFixture(model: MirrorViewModel) {
        val name = "install-fixture-v2.apk"
        val uri = TestShareProvider.offer(app, name,
            instrumentation.context.assets.open("install-fixtures/install-fixture-v2.bin").use { it.readBytes() })
        main { model.installPickedApp(uri) }
        await("$name read") { model.appOffer.value?.takeIf { !it.reading } }
        main { model.acceptAppOffer(install = true) }
        val outcome = await("$name installed") { model.installOutcome.value?.takeIf { !model.fileTransfer.value.running } }
        assertEquals(ApkInstall.Result.Installed, outcome.result)
        main { model.dismissInstallOutcome() }
        assertTrue(installed())
    }

    private fun listed(model: MirrorViewModel) {
        main { model.loadApps(force = true) }
        await("the fixture listed") {
            model.apps.value.takeIf { it.loaded }?.apps?.firstOrNull { it.packageName == FIXTURE }
        }
    }

    private fun awaitUninstallQuestion() {
        await("the device's own question about the fixture") {
            shell("dumpsys activity activities").takeIf { dump ->
                dump.lineSequence().any { "ResumedActivity" in it && "UninstallerActivity" in it } &&
                    "dat=package:$FIXTURE" in dump
            }
        }
        // Named on screen too, so OK is never pressed on a question about another app.
        await("the question naming the fixture") { true.takeIf { showing(FIXTURE_LABEL) } }
    }

    /** Presses OK on the device's own uninstall question, already confirmed to be about the fixture. */
    private fun answerOk() {
        val ok = await("the device's OK button") {
            instrumentation.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
        }
        assertTrue(ok.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** The part of `dumpsys activity activities` about one display, or null when it has none. */
    private fun displaySection(display: Int): String? {
        val dump = shell("dumpsys activity activities")
        val start = dump.indexOf("Display #$display ").takeIf { it >= 0 } ?: return null
        val end = dump.indexOf("Display #", start + 1).takeIf { it >= 0 } ?: dump.length
        return dump.substring(start, end)
    }

    /**
     * Opens [packageName]'s App info on an overlay display and then removes the display, which moves
     * the task to the main screen in the state where a plain `am start` only brings it back.
     */
    private fun strandAppInfo(packageName: String) {
        shell("settings put global overlay_display_devices 720x480/160")
        var display = -1
        try {
            display = overlayDisplay()
            shell("am start --activity-clear-task --display $display " +
                "-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$packageName")
            await("App info on display $display") {
                displaySection(display)?.takeIf { section ->
                    section.lineSequence().any { "ResumedActivity" in it && "com.android.settings/" in it }
                }
            }
        } finally {
            shell("settings delete global overlay_display_devices")
        }
        await("display $display gone") { true.takeIf { displays.getDisplay(display) == null } }
        // The task moves to the main screen after the display is gone, and on its own schedule: a
        // Home pressed before it lands is overtaken by it.
        await("the page moved to the main screen", { "resumed: ${resumedActivity()}" }) {
            displaySection(Display.DEFAULT_DISPLAY)?.takeIf { section ->
                section.lineSequence().any { "ResumedActivity" in it && "com.android.settings/" in it }
            }
        }
    }

    /** Presses Home until the launcher is in front, which a task still moving can briefly undo. */
    private fun goHome() {
        await("home", { "resumed: ${resumedActivity()}" }) {
            shell("input keyevent 3")
            SystemClock.sleep(400)
            resumedActivity()?.takeIf { "com.android.settings/" !in it && "UninstallerActivity" !in it }
        }
    }

    /** The overlay display just created: the only one other than the main screen. */
    private fun overlayDisplay(): Int = await("the overlay display") {
        displays.displays.map { it.displayId }.filter { it != Display.DEFAULT_DISPLAY }.maxOrNull()
    }

    private fun installed(): Boolean = shell("pm list packages $FIXTURE").lineSequence().any { it.trim() == "package:$FIXTURE" }

    /**
     * Whether [text] is on screen in any window. Walks the trees rather than asking each window to
     * search: Settings' Compose pages did not always answer that search though the text was there.
     */
    private fun showing(text: String): Boolean = screenTexts().any { it == text }

    /** The first texts on screen, for a failure to show what was there instead. */
    private fun shownTexts(): List<String> = screenTexts().take(12)

    private fun screenTexts(): List<String> {
        val automation = instrumentation.uiAutomation
        val texts = mutableListOf<String>()
        val pending = ArrayDeque(automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
        var visited = 0
        while (pending.isNotEmpty() && visited < 600) {
            val node = pending.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.addLast(it) }
        }
        return texts
    }

    private fun resumedActivity(): String? =
        shell("dumpsys activity activities").lineSequence().firstOrNull { "ResumedActivity" in it }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    private fun withSession(
        port: Int,
        settings: MirrorSettings = MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false),
        block: (MirrorViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("app-pages-live-test", model)
            model.connect(HOST, port, settings)
        }
        try {
            await("connected") { model.state.value as? MirrorUiState.Connected }
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> await(description: String, diagnose: () -> String = { "" }, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("$description: timed out ${diagnose()}".trimEnd())
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXTURE = "com.scrcpy.remote.installfixture"
        const val FIXTURE_LABEL = "Install fixture"
        val FIXTURE_APP = DeviceApp(FIXTURE_LABEL, FIXTURE, system = false)
        /** App info is offered for an app that came with the device too; only Uninstall is not. */
        val SETTINGS_APP = DeviceApp("Settings", "com.android.settings", system = true)
        val NOTES = DeviceApp("Notes", "com.example.notes", system = false)

        /** Android 8's answer for an intent nothing on the device handles. */
        const val REFUSAL = "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package:com.example.notes }\n" +
            "Error: Activity not started, unable to resolve Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS " +
            "dat=package:com.example.notes flg=0x10000000 }\n"
    }
}
