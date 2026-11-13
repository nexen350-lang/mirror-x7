package com.scrcpy.remote.session

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What happens to a picked app package on this phone, before anything reaches a device: it is
 * copied, read and offered, and every copy goes once the offer ends.
 */
@RunWith(AndroidJUnit4::class)
class AppInstallFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val uploads = File(app.cacheDir, "shared-uploads")
    private lateinit var existing: Set<String>

    @Before fun noteExistingCopies() {
        existing = uploads.list()?.toSet().orEmpty()
    }

    /** A failing test can leave a copy behind; later runs count copies, so it goes here. */
    @After fun clearShares() {
        TestShareProvider.clear(app)
        uploads.listFiles()?.filter { it.name !in existing }?.forEach { it.delete() }
    }

    @Test fun aPickedAppIsReadOnThisPhoneAndItsCopyGoesWithTheOffer() {
        val bytes = fixture("install-fixture-v2.apk")
        val uri = TestShareProvider.offer(app, "pocket.apk", bytes)
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(uri) }
            val offer = await("the app read") { model.appOffer.value?.takeIf { !it.reading } }
            assertFalse("Picked to install, so copying it is not offered", offer.canCopy)
            assertEquals("pocket.apk", offer.name)
            assertEquals(bytes.size.toLong(), offer.size)
            val details = requireNotNull(offer.app) { "This phone reads the fixture" }
            assertEquals(FIXTURE, details.packageName)
            assertEquals("Install fixture", details.label)
            assertEquals("2.0", details.versionName)
            assertEquals(2L, details.versionCode)
            assertNotNull("An app without an icon of its own still shows the default one", details.icon)
            assertEquals("The offer works from a copy of its own", before + 1, cachedCopies())
            main { model.dismissAppOffer() }
            assertNull(model.appOffer.value)
            assertEquals("Dismissing deletes the copy", before, cachedCopies())
        }
    }

    @Test fun aPickedFileThatIsNotAnAppIsTurnedAwayWithoutACopyLeftBehind() {
        val uri = TestShareProvider.offer(app, "notes.txt", "Not an app".toByteArray())
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(uri) }
            await("turned away") { model.notice.value?.takeIf { it == app.getString(R.string.install_not_an_app) } }
            assertNull(model.appOffer.value)
            assertEquals(before, cachedCopies())
        }
    }

    @Test fun anAppWithAPlainNameIsRecognisedByWhatItContains() {
        val uri = TestShareProvider.offer(app, "download.bin", fixture("install-fixture-v2.apk"))
        withModel { model ->
            main { model.installPickedApp(uri) }
            val offer = await("the app read") { model.appOffer.value?.takeIf { !it.reading } }
            assertEquals(FIXTURE, offer.app?.packageName)
            main { model.dismissAppOffer() }
        }
    }

    @Test fun aDamagedFileNamedAsAnAppIsLeftForTheDeviceToJudge() {
        // This phone cannot read it, but neither can an old phone read an app made for a newer
        // Android, so the name is enough to offer it and the device has the last word.
        val uri = TestShareProvider.offer(app, "broken.apk", ByteArray(256) { it.toByte() })
        withModel { model ->
            main { model.installPickedApp(uri) }
            val offer = await("the file read") { model.appOffer.value?.takeIf { !it.reading } }
            assertNull(offer.app)
            main { model.dismissAppOffer() }
        }
    }

    @Test fun anAnswerGivenWithoutASessionKeepsTheQuestionUntilLeaving() {
        val uri = TestShareProvider.offer(app, "pocket.apk", fixture("install-fixture-v2.apk"))
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(uri) }
            await("the app read") { model.appOffer.value?.takeIf { !it.reading } }
            main { model.acceptAppOffer(install = true) }
            assertEquals(app.getString(R.string.install_needs_session), model.notice.value)
            assertNotNull("Kept, so it can be answered once a session is back", model.appOffer.value)
            main { model.disconnect() }
            assertNull("Leaving ends the question", model.appOffer.value)
            assertEquals("and deletes its copy", before, cachedCopies())
        }
    }

    @Test fun aSecondPickReplacesTheFirstAndItsCopy() {
        val first = TestShareProvider.offer(app, "first.apk", fixture("install-fixture-v1.apk"))
        val second = TestShareProvider.offer(app, "second.apk", fixture("install-fixture-v2.apk"))
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(first) }
            await("the first read") { model.appOffer.value?.takeIf { !it.reading } }
            main { model.installPickedApp(second) }
            val offer = await("the second read") { model.appOffer.value?.takeIf { !it.reading && it.name == "second.apk" } }
            assertEquals("2.0", offer.app?.versionName)
            assertEquals("Only the current offer keeps a copy", before + 1, cachedCopies())
            main { model.dismissAppOffer() }
            assertEquals(before, cachedCopies())
        }
    }

    @Test fun leavingWhileTheAppIsStillBeingReadLeavesNoCopy() {
        val uri = TestShareProvider.offer(app, "slow.apk", fixture("install-fixture-v2.apk"))
        val release = java.util.concurrent.CountDownLatch(1)
        // Holds the provider's answer about the file, so the offer is still reading when it ends.
        TestShareProvider.beforeQuery = { release.await() }
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(uri) }
            assertEquals(true, model.appOffer.value?.reading)
            main { model.dismissAppOffer() }
            release.countDown()
            SystemClock.sleep(500)
            assertNull(model.appOffer.value)
            assertEquals(before, cachedCopies())
        }
    }

    @Test fun anOfferEndedJustAsItsCopyFinishesLeavesNoCopy() {
        // The copy's last step, naming the finished file, comes after its last cancellation
        // check. An offer ended then still has to delete what the copy wrote.
        val uri = TestShareProvider.offer(app, "late.apk", fixture("install-fixture-v2.apk"))
        val queries = java.util.concurrent.atomic.AtomicInteger()
        val naming = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // The first query describes the picked file; the second names the copy just written.
        TestShareProvider.beforeQuery = {
            if (queries.incrementAndGet() == 2) {
                naming.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        withModel { model ->
            val before = cachedCopies()
            main { model.installPickedApp(uri) }
            assertTrue("The copy reached its last step", naming.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("Written, but not yet handed over", before + 1, cachedCopies())
            main { model.dismissAppOffer() }
            release.countDown()
            val until = SystemClock.elapsedRealtime() + 5_000
            while (cachedCopies() != before && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            assertNull(model.appOffer.value)
            assertEquals("The finished copy is deleted", before, cachedCopies())
        }
    }

    @Test fun aPickedBundleIsReadAsOneAppWithTheFilesItHolds() {
        for ((name, files) in listOf("pocket-game.apks" to 17, "pocket-game.xapk" to 12, "pocket-game.apkm" to 12)) {
            val bytes = fixture("bundle-fixture.${name.substringAfterLast('.')}")
            val uri = TestShareProvider.offer(app, name, bytes)
            withModel { model ->
                val before = cacheFiles()
                main { model.installPickedApp(uri) }
                val offer = await("$name read") { model.appOffer.value?.takeIf { !it.reading } }
                assertEquals(name, bytes.size.toLong(), offer.size)
                val details = requireNotNull(offer.app) { "$name: this phone reads the bundle's base" }
                assertEquals(name, BUNDLE, details.packageName)
                assertEquals(name, "Bundle fixture", details.label)
                assertEquals(name, "1.0", details.versionName)
                assertEquals(name, BundleSummary(files), offer.bundle)
                assertEquals("$name: only the offer's own copy, nothing from reading its base", 1, (cacheFiles() - before).size)
                main { model.dismissAppOffer() }
                assertEquals("$name: dismissing deletes the copy", emptySet<String>(), cacheFiles() - before)
            }
        }
    }

    @Test fun aSingleAppIsNeverTakenForABundle() {
        val uri = TestShareProvider.offer(app, "pocket.apk", fixture("install-fixture-v2.apk"))
        withModel { model ->
            main { model.installPickedApp(uri) }
            val offer = await("the app read") { model.appOffer.value?.takeIf { !it.reading } }
            assertEquals(FIXTURE, offer.app?.packageName)
            assertNull(offer.bundle)
            main { model.dismissAppOffer() }
        }
    }

    @Test fun aBundleThatCannotBeInstalledFromHereIsTurnedAwayWithItsReason() {
        val entries = java.util.zip.ZipInputStream(fixture("bundle-fixture.xapk").inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
        } + ("Android/obb/$BUNDLE/main.1.$BUNDLE.obb" to ByteArray(64))
        val withObb = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                for ((entry, bytes) in entries) { zip.putNextEntry(java.util.zip.ZipEntry(entry)); zip.write(bytes); zip.closeEntry() }
            }
        }.toByteArray()
        for ((name, bytes, reason) in listOf(
            Triple("game.xapk", withObb, app.getString(R.string.install_bundle_expansion)),
            Triple("protected.apkm", ByteArray(512) { (it * 7).toByte() }, app.getString(R.string.install_bundle_unreadable)),
        )) {
            val uri = TestShareProvider.offer(app, name, bytes)
            withModel { model ->
                val before = cacheFiles()
                main { model.installPickedApp(uri) }
                await("$name turned away") { model.notice.value?.takeIf { it == reason } }
                assertNull(name, model.appOffer.value)
                assertEquals("$name leaves nothing behind", emptySet<String>(), cacheFiles() - before)
            }
        }
    }

    /** Every file in this app's cache, so a copy left anywhere is noticed. */
    private fun cacheFiles(): Set<String> =
        app.cacheDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(app.cacheDir).path }.toSet()
    /** The fixtures are stored as `.bin` test data; `.apk` files are never committed. */
    private fun fixture(name: String): ByteArray =
        instrumentation.context.assets.open("install-fixtures/${name.removeSuffix(".apk")}.bin").use { it.readBytes() }

    private fun cachedCopies(): Int = uploads.listFiles()?.size ?: 0

    private fun withModel(block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("app-install-flow-test", model)
        }
        try {
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
        val until = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(25)
        }
        throw AssertionError("$description: timed out")
    }

    private companion object {
        const val FIXTURE = "com.scrcpy.remote.installfixture"
        const val BUNDLE = "com.scrcpy.remote.bundlefixture"
    }
}
