package com.scrcpy.remote

import com.scrcpy.remote.AppBundleTest.Companion.fixtureFile
import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.AppBundle
import com.scrcpy.remote.session.BundleChoice
import com.scrcpy.remote.session.BundleSelection
import com.scrcpy.remote.session.DeviceSpec
import com.scrcpy.remote.session.DeviceSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of an app bundle's files a device gets: the variant for its Android version, and in each
 * module the base or feature, one split for its processor, one for its screen density and one for
 * each of its languages. Anything less is a partial app, which Android 8 installs without a word.
 */
class BundleSelectionTest {
    private val android16 = DeviceSpec(36, listOf("x86_64", "arm64-v8a"), 420, listOf("en"))
    private val android8 = DeviceSpec(26, listOf("x86_64", "x86"), 420, listOf("en"))

    @Test fun anAndroid16DeviceGetsTheNewestVariantsBaseWithItsProcessorAndDensity() {
        assertEquals(listOf("splits/base-master_3.apk", "splits/base-x86_64_2.apk", "splits/base-xxhdpi.apk"),
            installed(apkSet(), android16))
    }

    @Test fun eachDeviceGetsTheVariantForItsAndroidVersion() {
        assertEquals(listOf("splits/base-master_2.apk", "splits/base-x86_64_2.apk", "splits/base-xxhdpi.apk"),
            installed(apkSet(), android8))
        assertEquals("Variants start at their own versions", listOf("splits/base-master_2.apk", "splits/base-x86_64_2.apk",
            "splits/base-xxhdpi.apk"), installed(apkSet(), android8.copy(sdk = 23)))
        assertEquals(listOf("splits/base-master.apk", "splits/base-armeabi_v7a.apk", "splits/base-hdpi.apk"),
            installed(apkSet(), DeviceSpec(22, listOf("armeabi-v7a", "armeabi"), 240, listOf("en"))))
    }

    @Test fun theDevicesLanguagesAddTheirTranslations() {
        assertEquals(listOf("splits/base-master_3.apk", "splits/base-x86_64_2.apk", "splits/base-xxhdpi.apk", "splits/base-fr.apk"),
            installed(apkSet(), android16.copy(languages = listOf("de", "fr"))))
    }

    @Test fun theDevicesFirstProcessorTheBundleHasCodeForIsUsed() {
        assertEquals(listOf("splits/base-master_3.apk", "splits/base-arm64_v8a_2.apk", "splits/base-xxhdpi.apk"),
            installed(apkSet(), android16.copy(abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"))))
        assertEquals(listOf("splits/base-master_3.apk", "splits/base-armeabi_v7a_2.apk", "splits/base-xxhdpi.apk"),
            installed(apkSet(), android16.copy(abis = listOf("riscv64", "armeabi-v7a"))))
    }

    @Test fun aBundleWithoutCodeForTheDevicesProcessorIsRefusedBeforeAnythingIsSent() {
        val riscv = requireNotNull(AppBundle.read(fixtureFile("bundle-fixture-riscv.apks.bin"), "riscv.apks"))
        assertEquals(BundleChoice.NoMatchingProcessor(listOf("riscv64"), listOf("x86_64", "arm64-v8a")),
            BundleSelection.choose(riscv, android16))
    }

    @Test fun aDeviceOlderThanEveryVariantIsRefused() {
        assertEquals(BundleChoice.NeedsNewerAndroid(21, 19), BundleSelection.choose(apkSet(), android16.copy(sdk = 19)))
    }

    @Test fun xapkAndApkmFilesGiveTheSameFiles() {
        val xapk = requireNotNull(AppBundle.read(fixtureFile("bundle-fixture.xapk.bin"), "app.xapk"))
        assertEquals(listOf("com.scrcpy.remote.bundlefixture.apk", "config.x86_64.apk", "config.xxhdpi.apk"), installed(xapk, android16))
        val apkm = requireNotNull(AppBundle.read(fixtureFile("bundle-fixture.apkm.bin"), "app.apkm"))
        assertEquals(listOf("base.apk", "split_config.x86_64.apk", "split_config.xxhdpi.apk", "split_config.fr.apk"),
            installed(apkm, android8.copy(languages = listOf("fr"))))
    }

    @Test fun everyFeatureGetsItsOwnSplitsForOneSharedProcessor() {
        // The feature has code only for arm64, and one process cannot mix processors, so the base
        // takes arm64 too although the device prefers x86_64.
        val bundle = bundle(null, "config.x86_64", "config.arm64_v8a", "config.xxhdpi", "config.mdpi",
            "extras", "extras.config.arm64_v8a", "extras.config.xxhdpi", "extras.config.mdpi", "extras.config.fr")
        assertEquals(listOf("base", "config.arm64_v8a", "config.xxhdpi", "extras", "extras.config.arm64_v8a",
            "extras.config.xxhdpi"), installed(bundle, android16))
        // Only arm64 runs the whole app, which is what the refusal names.
        assertEquals(BundleChoice.NoMatchingProcessor(listOf("arm64-v8a"), listOf("x86_64", "x86")),
            BundleSelection.choose(bundle, android8))
    }

    @Test fun modulesForLaterAreLeftOut() {
        val bundle = bundle(null, "config.x86_64", "camera", "camera.config.x86_64", onDemand = setOf("camera", "camera.config.x86_64"))
        assertEquals(listOf("base", "config.x86_64"), installed(bundle, android16))
    }

    @Test fun aBundleWithoutSplitsForADimensionNeedsNoneOfThem() {
        assertEquals(listOf("base"), installed(bundle(null), android16))
        assertEquals(listOf("base", "config.fr"), installed(bundle(null, "config.fr", "config.de"), android16.copy(languages = listOf("fr"))))
        assertEquals("No language of the device's: the base's own text", listOf("base"),
            installed(bundle(null, "config.fr", "config.de"), android16))
    }

    @Test fun densityFollowsAndroidsOwnChoiceOfResources() {
        val all = listOf(120, 160, 213, 240, 320, 480, 640)
        val expected = mapOf(100 to 120, 120 to 120, 160 to 160, 200 to 213, 213 to 213, 260 to 240, 280 to 320,
            320 to 320, 400 to 480, 420 to 480, 480 to 480, 560 to 640, 640 to 640, 700 to 640)
        for ((device, best) in expected) assertEquals("$device dpi", best, BundleSelection.bestDensity(all, device))
        assertEquals("Both below: the larger", 160, BundleSelection.bestDensity(listOf(120, 160), 420))
        assertEquals("Both above: the smaller", 480, BundleSelection.bestDensity(listOf(480, 640), 160))
        assertEquals(480, BundleSelection.bestDensity(listOf(160, 480), 320))
        assertEquals(160, BundleSelection.bestDensity(listOf(160, 480), 200))
    }

    @Test fun languagesMatchUnderTheirOldAndNewCodes() {
        for ((device, split) in listOf("he" to "config.iw", "iw" to "config.he", "id" to "config.in", "in" to "config.id",
            "yi" to "config.ji", "ji" to "config.yi")) {
            assertEquals("$device on the device, $split in the bundle", listOf("base", split),
                installed(bundle(null, split), android16.copy(languages = listOf(device))))
        }
    }

    @Test fun theDeviceDescribesItselfInOneCommand() {
        for (asked in listOf("ro.build.version.sdk", "ro.product.cpu.abilist", "ro.product.cpu.abi", "wm density",
            "system_locales", "persist.sys.locale", "ro.product.locale")) {
            assertTrue(asked, asked in DeviceSpecs.COMMAND)
        }
        // As the Android 8 and 16 emulators answer.
        assertEquals(android8, DeviceSpecs.parse(
            "sdk=26\nabis=x86_64,x86\nabi=x86_64\nPhysical density: 420\nlocales=null\nlocale=\nproduct=en-US\n"))
        assertEquals(android16, DeviceSpecs.parse(
            "sdk=36\nabis=x86_64,arm64-v8a\nabi=x86_64\nPhysical density: 420\nlocales=null\nlocale=\nproduct=en-US\n"))
        // A changed display size is what apps draw at; the user's languages come first.
        assertEquals(DeviceSpec(34, listOf("arm64-v8a", "armeabi-v7a", "armeabi"), 560, listOf("fr", "en")), DeviceSpecs.parse(
            "sdk=34\nabis=arm64-v8a,armeabi-v7a,armeabi\nabi=arm64-v8a\nPhysical density: 420\nOverride density: 560\n" +
                "locales=fr-FR,en-US\nlocale=fr-FR\nproduct=en-US\n"))
        // An older answer: Windows line ends, one processor, a language set before the list existed.
        assertEquals(DeviceSpec(21, listOf("armeabi-v7a"), 240, listOf("de")), DeviceSpecs.parse(
            "sdk=21\r\nabis=\r\nabi=armeabi-v7a\r\nPhysical density: 240\r\nlocales=\r\nlocale=de-DE\r\nproduct=en-US\r\n"))
        assertNull("Nothing to choose by", DeviceSpecs.parse(""))
        assertNull("No processor", DeviceSpecs.parse("sdk=26\nabis=\nabi=\nPhysical density: 420\n"))
        assertNull("No density", DeviceSpecs.parse("sdk=26\nabis=x86\nabi=x86\n"))
        assertNull("No version", DeviceSpecs.parse("sdk=\nabis=x86\nabi=x86\nPhysical density: 420\n"))
    }

    @Test fun aDeviceWithoutWindowManagerDensityStillSaysItsDensity() {
        assertTrue("ro.sf.lcd_density" in DeviceSpecs.COMMAND)
        assertEquals(DeviceSpec(30, listOf("arm64-v8a"), 320, listOf("en")), DeviceSpecs.parse(
            "sdk=30\nabis=arm64-v8a\nabi=arm64-v8a\nlcd=320\nlocales=en-GB\nlocale=\nproduct=en-US\n"))
        assertEquals("What apps draw at comes first", 420, DeviceSpecs.parse(
            "sdk=30\nabis=arm64-v8a\nabi=arm64-v8a\nPhysical density: 420\nlcd=320\nlocales=en-GB\n")?.densityDpi)
    }

    @Test fun eachRefusalOnThisPhoneHasItsOwnReason() {
        assertEquals(ApkInstall.Refusal.WRONG_PROCESSOR,
            BundleSelection.refusal(BundleChoice.NoMatchingProcessor(listOf("riscv64"), listOf("x86_64"))))
        assertEquals(ApkInstall.Refusal.NEEDS_NEWER_ANDROID, BundleSelection.refusal(BundleChoice.NeedsNewerAndroid(29, 26)))
        // A device that did not say what it is was asked nothing else: it is not "could not install".
        assertEquals(ApkInstall.Refusal.UNDESCRIBED_DEVICE, BundleSelection.refusal(null))
        assertEquals(R.string.install_refused_undescribed, ApkInstall.message(ApkInstall.Refusal.UNDESCRIBED_DEVICE))
    }
    private fun apkSet(): AppBundle = requireNotNull(AppBundle.read(fixtureFile("bundle-fixture.apks.bin"), "app.apks"))

    private fun installed(bundle: AppBundle, device: DeviceSpec): List<String> {
        val choice = BundleSelection.choose(bundle, device)
        check(choice is BundleChoice.Install) { "Expected an install, got $choice" }
        return choice.parts.map { it.entry }
    }

    /** A bundle of one variant whose files are named after their splits, the base as `base`. */
    private fun bundle(vararg splits: String?, onDemand: Set<String> = emptySet()): AppBundle =
        AppBundle(
            format = AppBundle.Format.ZIP,
            packageName = "com.example.app",
            variants = listOf(AppBundle.Variant(0, splits.map {
                AppBundle.Part(entry = it ?: "base", split = it, size = 1, onDemand = it in onDemand)
            })),
            files = splits.size,
            baseEntry = "base",
        )
}
