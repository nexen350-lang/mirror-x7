package com.scrcpy.remote.session

import android.app.Application
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Installing apps through a real session, judged by the device's own package manager, through
 * the loopback adbd of a disposable emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * The fixtures are tiny signed test-only apps built by tools/make-install-fixtures.ps1: versions
 * 1 and 2 share a signing key, version 3 has another.
 */
@RunWith(AndroidJUnit4::class)
class AppInstallLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @After fun cleanUp() {
        shell("am force-stop $FIXTURE")
        shell("pm uninstall $FIXTURE")
        shell("pm uninstall $BUNDLE")
        TestShareProvider.clear(app)
    }

    @Test fun anAppIsInstalledListedAndOpenedAndNothingIsLeftBehind() {
        val port = livePort()
        shell("pm uninstall $FIXTURE")
        withSession(port) { model ->
            val outcome = install(model, "install-fixture-v2.apk")
            assertEquals(ApkInstall.Result.Installed, outcome.result)
            assertEquals(FIXTURE, outcome.packageName)
            assertEquals("Install fixture", outcome.name)
            assertTrue(shell("pm list packages $FIXTURE"), shell("pm list packages $FIXTURE").contains("package:$FIXTURE"))
            assertTrue(shell("dumpsys package $FIXTURE"), shell("dumpsys package $FIXTURE").contains("versionCode=2 "))
            assertNoCopiesLeftOnTheDevice()
            await("the refreshed app list shows it") { model.apps.value.apps.firstOrNull { it.packageName == FIXTURE } }
            assertTrue(main { model.openInstalledApp() })
            await("the app opened on the device") {
                shell("dumpsys activity activities").lineSequence()
                    .firstOrNull { "ResumedActivity" in it && "$FIXTURE/.MainActivity" in it }
            }
            assertEquals("Opening closes the answer", null, model.installOutcome.value)
        }
    }

    @Test fun eachRefusalSaysWhyAndLeavesNothingBehind() {
        val port = livePort()
        shell("pm uninstall $FIXTURE")
        withSession(port) { model ->
            assertEquals(ApkInstall.Result.Installed, install(model, "install-fixture-v2.apk").result)
            main { model.dismissInstallOutcome() }

            val older = install(model, "install-fixture-v1.apk").result as ApkInstall.Result.Refused
            assertEquals(ApkInstall.Refusal.NEWER_INSTALLED, older.reason)
            assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", older.code)
            main { model.dismissInstallOutcome() }

            val otherKey = install(model, "install-fixture-v3-other-key.apk").result as ApkInstall.Result.Refused
            assertEquals(ApkInstall.Refusal.SIGNATURE_MISMATCH, otherKey.reason)
            assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", otherKey.code)
            main { model.dismissInstallOutcome() }

            val damaged = install(model, "damaged.apk", ByteArray(4_096) { (it * 31).toByte() })
                .result as ApkInstall.Result.Refused
            assertEquals(ApkInstall.Refusal.NOT_AN_APP, damaged.reason)
            main { model.dismissInstallOutcome() }

            assertTrue("Still version 2", shell("dumpsys package $FIXTURE").contains("versionCode=2 "))
            assertNoCopiesLeftOnTheDevice()
        }
    }

    @Test fun aBundleInstallsOnlyTheFilesTheDeviceNeedsAndNothingIsLeftBehind() {
        val port = livePort()
        shell("pm uninstall $BUNDLE")
        withSession(port) { model ->
            for (name in listOf("bundle-fixture.apks", "bundle-fixture.xapk", "bundle-fixture.apkm")) {
                val outcome = install(model, name)
                assertEquals(name, ApkInstall.Result.Installed, outcome.result)
                assertEquals(name, BUNDLE, outcome.packageName)
                assertEquals(name, "Bundle fixture", outcome.name)
                // An x86_64 emulator at 420 dpi in English: its processor and density, and no
                // translation it does not use. Android 16 refuses a base without the first two.
                val installed = shell("pm path $BUNDLE").lineSequence().map { it.trim().substringAfterLast('/') }
                    .filter { it.isNotEmpty() }.toSet()
                assertEquals(name, setOf("base.apk", "split_config.x86_64.apk", "split_config.xxhdpi.apk"), installed)
                assertNoCopiesLeftOnTheDevice()
                main { model.dismissInstallOutcome() }
                shell("pm uninstall $BUNDLE")
            }
        }
    }

    @Test fun aBundleWithoutCodeForTheDevicesProcessorIsRefusedOnThisPhone() {
        val port = livePort()
        shell("pm uninstall $BUNDLE")
        withSession(port) { model ->
            val refused = install(model, "bundle-fixture-riscv.apks").result as ApkInstall.Result.Refused
            assertEquals(ApkInstall.Refusal.WRONG_PROCESSOR, refused.reason)
            assertTrue("Decided on this phone, before anything was sent", refused.local)
            val abis = shell("getprop ro.product.cpu.abilist").trim().split(',').joinToString(", ")
            assertEquals(app.getString(R.string.install_bundle_processor_detail, "riscv64", abis), refused.detail)
            assertFalse(shell("pm list packages $BUNDLE").contains("package:$BUNDLE"))
            assertNoCopiesLeftOnTheDevice()
        }
    }
    @Test fun aCopyStrandedByAnEarlierDroppedConnectionIsSweptByTheNextInstall() {
        val port = livePort()
        val stranded = "/data/local/tmp/scrcpy-remote-install-stranded.apk"
        val recent = "/data/local/tmp/scrcpy-remote-install-recent.apk"
        shell("touch $stranded")
        shell("touch -t 202601010000 $stranded")
        shell("touch $recent")
        try {
            assertEquals("The fixture's stranded copy exists", stranded, shell("ls $stranded").trim())
            withSession(port) { model ->
                assertEquals(ApkInstall.Result.Installed, install(model, "install-fixture-v2.apk").result)
            }
            assertEquals("An hour-old copy is swept", "", shell("ls $stranded").trim())
            // Younger than that, it may be another install still in progress.
            assertEquals(recent, shell("ls $recent").trim())
        } finally {
            shell("rm -f $stranded $recent")
        }
    }

    @Test fun aSentAppIsAskedAboutAndCanStillBeCopiedToDownload() {
        val port = livePort()
        val name = "copied-${UUID.randomUUID()}.apk"
        val bytes = fixture("install-fixture-v1.apk")
        val uri = TestShareProvider.offer(app, name, bytes)
        try {
            withSession(port) { model ->
                main { model.sendFile(uri) }
                val offer = await("the question") { model.appOffer.value?.takeIf { !it.reading } }
                assertTrue("Sent as a file, so copying is offered", offer.canCopy)
                assertEquals("Nothing is sent before the answer", "", shell("ls /sdcard/Download/$name").trim())
                main { model.acceptAppOffer(install = false) }
                await("copied") {
                    model.notice.value?.takeIf {
                        it == app.getString(R.string.send_file_done, name) ||
                            it == app.getString(R.string.send_file_not_indexed, name)
                    }
                }
                assertEquals(bytes.size.toString(), shell("stat -c %s /sdcard/Download/$name").trim())
                assertFalse("Copied, not installed", shell("pm list packages $FIXTURE").contains("package:$FIXTURE"))
            }
        } finally {
            shell("rm -f /sdcard/Download/$name")
        }
    }

    @Test fun aSentBundleIsAskedAboutAndInstalledOrCopiedAsChosen() {
        val port = livePort()
        val name = "bundle-${UUID.randomUUID()}.xapk"
        val bytes = fixture("bundle-fixture.xapk")
        shell("pm uninstall $BUNDLE")
        try {
            withSession(port) { model ->
                main { model.sendFile(TestShareProvider.offer(app, name, bytes)) }
                val offer = await("the question") { model.appOffer.value?.takeIf { !it.reading } }
                assertTrue("Sent as a file, so copying is offered", offer.canCopy)
                assertEquals(BundleSummary(12), offer.bundle)
                assertEquals(BUNDLE, offer.app?.packageName)
                assertEquals("Nothing is sent before the answer", "", shell("ls /sdcard/Download/$name").trim())
                main { model.acceptAppOffer(install = true) }
                val outcome = await("installed") { model.installOutcome.value?.takeIf { !model.fileTransfer.value.running } }
                assertEquals(ApkInstall.Result.Installed, outcome.result)
                assertTrue(shell("pm list packages $BUNDLE").contains("package:$BUNDLE"))
                assertEquals("Installed, not copied", "", shell("ls /sdcard/Download/$name").trim())
            }
        } finally {
            shell("rm -f /sdcard/Download/$name")
        }
    }
    @Test fun aSharedAppWaitsForAnAnswerAndItsCopyGoesWithIt() {
        val port = livePort()
        val copy = File(File(app.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
            .apply { writeBytes(fixture("install-fixture-v2.apk")) }
        try {
            withSession(port) { model ->
                main { model.queueSharedFile(copy.absolutePath, SHARED_NAME) }
                val offer = await("the question") { model.appOffer.value?.takeIf { !it.reading } }
                assertTrue(offer.canCopy)
                assertEquals(SHARED_NAME, offer.name)
                assertEquals(FIXTURE, offer.app?.packageName)
                assertTrue("The share's copy is kept while the question is open", copy.exists())
                main { model.dismissAppOffer() }
                assertFalse("and deleted once it is answered", copy.exists())
            }
        } finally {
            copy.delete()
            // Only there if the question was skipped and the file sent.
            shell("rm -f /sdcard/Download/$SHARED_NAME")
        }
    }

    /** Picks [name] from the fixtures, or [bytes] under that name, installs it and waits for the answer. */
    private fun install(model: MirrorViewModel, name: String, bytes: ByteArray = fixture(name)): AppInstallOutcome {
        val uri = TestShareProvider.offer(app, name, bytes)
        main { model.installPickedApp(uri) }
        await("$name read") { model.appOffer.value?.takeIf { !it.reading } }
        main { model.acceptAppOffer(install = true) }
        // Read together: the answer is published just before the slot is released.
        return await("$name answered") {
            model.installOutcome.value?.takeIf { !model.fileTransfer.value.running }
        }
    }

    private fun assertNoCopiesLeftOnTheDevice() {
        val left = shell("ls /data/local/tmp").lineSequence().filter { it.startsWith("scrcpy-remote-install-") }.toList()
        assertEquals("Every install removes its copy", emptyList<String>(), left)
    }

    /** The fixtures are stored as `.bin` test data; `.apk` files are never committed. */
    private fun fixture(name: String): ByteArray =
        instrumentation.context.assets.open("install-fixtures/${name.removeSuffix(".apk")}.bin").use { it.readBytes() }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    private fun withSession(port: Int, block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("app-install-live-test", model)
            model.connect(HOST, port, MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false))
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

    private fun <T : Any> await(description: String, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("$description: timed out")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val FIXTURE = "com.scrcpy.remote.installfixture"
        const val BUNDLE = "com.scrcpy.remote.bundlefixture"
        const val SHARED_NAME = "shared-tool.apk"
    }
}
