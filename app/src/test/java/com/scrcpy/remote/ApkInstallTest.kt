package com.scrcpy.remote

import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.ApkInstall.Refusal
import com.scrcpy.remote.session.ApkInstall.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the device's package manager answers when it is asked to install an app.
 *
 * The answers below are verbatim from `pm install` on the Android 8 and Android 16 emulators
 * this release was tested against: the same failure reads differently on each, and a file that
 * is not an app at all produces no failure code on either.
 */
class ApkInstallTest {
    @Test fun successIsTheOnlyAnswerThatMeansInstalled() {
        assertEquals(Result.Installed, ApkInstall.parse("Success\n"))
        assertEquals(Result.Installed, ApkInstall.parse("Success\r\n"))
        // Android 5 and 6 name the package file first.
        assertEquals(Result.Installed, ApkInstall.parse("\tpkg: /data/local/tmp/a.apk\nSuccess\n"))
        assertTrue(ApkInstall.parse("Successful? no\n") is Result.Refused)
        assertTrue("A refusal that mentions success is still a refusal",
            ApkInstall.parse("Success\nFailure [INSTALL_FAILED_ABORTED]\n") is Result.Refused)
    }

    @Test fun failuresKeepTheirCodeAndReasonOnBothAndroidVersions() {
        val android8 = ApkInstall.parse("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n") as Result.Refused
        assertEquals(Refusal.NEWER_INSTALLED, android8.reason)
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", android8.code)
        assertEquals("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", android8.detail)

        val android16 = ApkInstall.parse("Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: " +
            "Update version code 1 is older than current 2]\n") as Result.Refused
        assertEquals(Refusal.NEWER_INSTALLED, android16.reason)
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", android16.code)

        for (answer in listOf(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.scrcpy.remote.installfixture " +
                "signatures do not match the previously installed version; ignoring!]",
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.scrcpy.remote.installfixture " +
                "signatures do not match newer version; ignoring!]",
        )) {
            val refused = ApkInstall.parse(answer) as Result.Refused
            assertEquals(answer, Refusal.SIGNATURE_MISMATCH, refused.reason)
            assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", refused.code)
            assertEquals(answer, refused.detail)
        }
    }

    @Test fun eachKnownCodeHasTheReasonItsMessageExplains() {
        for ((code, reason) in listOf(
            "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE" to Refusal.SIGNATURE_MISMATCH,
            "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" to Refusal.SIGNATURE_MISMATCH,
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" to Refusal.NO_SPACE,
            "INSTALL_FAILED_OLDER_SDK" to Refusal.NEEDS_NEWER_ANDROID,
            "INSTALL_FAILED_NO_MATCHING_ABIS" to Refusal.WRONG_PROCESSOR,
            "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" to Refusal.WRONG_PROCESSOR,
            "INSTALL_FAILED_INVALID_APK" to Refusal.NOT_AN_APP,
            "INSTALL_PARSE_FAILED_NOT_APK" to Refusal.NOT_AN_APP,
            "INSTALL_PARSE_FAILED_NO_CERTIFICATES" to Refusal.NOT_AN_APP,
            "INSTALL_PARSE_FAILED_BAD_MANIFEST" to Refusal.NOT_AN_APP,
            "INSTALL_FAILED_MISSING_SPLIT" to Refusal.SPLIT_APP,
            "INSTALL_FAILED_USER_RESTRICTED" to Refusal.BLOCKED_BY_DEVICE,
            "INSTALL_FAILED_VERIFICATION_FAILURE" to Refusal.VERIFICATION,
            "INSTALL_FAILED_VERIFICATION_TIMEOUT" to Refusal.VERIFICATION,
            "INSTALL_FAILED_ABORTED" to Refusal.CANCELLED_ON_DEVICE,
            "INSTALL_FAILED_DEPRECATED_SDK_VERSION" to Refusal.OLD_TARGET,
            "INSTALL_FAILED_DUPLICATE_PERMISSION" to Refusal.CONFLICT,
            "INSTALL_FAILED_CONFLICTING_PROVIDER" to Refusal.CONFLICT,
            // Never expected, since test builds are allowed, but not to be mistaken for anything else.
            "INSTALL_FAILED_TEST_ONLY" to Refusal.OTHER,
            "INSTALL_FAILED_INTERNAL_ERROR" to Refusal.OTHER,
        )) {
            val refused = ApkInstall.parse("Failure [$code: details]\n") as Result.Refused
            assertEquals(code, reason, refused.reason)
            assertEquals(code, refused.code)
        }
    }

    @Test fun aFileThatIsNotAnAppIsSaidSoOnBothAndroidVersions() {
        val android8 = ApkInstall.parse("Error: Failed to parse APK file: android.content.pm.PackageParser" +
            "\$PackageParserException: Failed to parse /data/local/tmp/spike-bad.apk\n") as Result.Refused
        assertEquals(Refusal.NOT_AN_APP, android8.reason)
        assertEquals(null, android8.code)
        assertTrue(android8.detail, android8.detail.startsWith("Error: Failed to parse APK file"))

        val android16 = ApkInstall.parse("""
            |
            |Exception occurred while executing 'install':
            |java.lang.IllegalArgumentException: Error: Failed to parse APK file: /data/local/tmp/spike-bad.apk: Failed to parse /data/local/tmp/spike-bad.apk
            |	at com.android.server.pm.PackageManagerShellCommand.setParamsSize(PackageManagerShellCommand.java:697)
            |	at com.android.server.pm.PackageManagerShellCommand.doRunInstall(PackageManagerShellCommand.java:1622)
            |Caused by: java.io.IOException: Failed to load asset path /data/local/tmp/spike-bad.apk from fd 684
            |	at android.content.res.ApkAssets.nativeLoadFd(Native Method)
            |""".trimMargin()) as Result.Refused
        assertEquals(Refusal.NOT_AN_APP, android16.reason)
        assertEquals(null, android16.code)
        // The device's own sentence, not its stack trace.
        assertTrue(android16.detail, android16.detail.startsWith("Error: Failed to parse APK file"))
        assertFalse(android16.detail, android16.detail.contains("PackageManagerShellCommand"))
    }

    @Test fun anUnexplainedRefusalKeepsTheDevicesOwnLine() {
        val android8 = ApkInstall.parse("Error: Can't open non-file: /data/local/tmp/spike-missing.apk\n") as Result.Refused
        assertEquals(Refusal.OTHER, android8.reason)
        assertEquals("Error: Can't open non-file: /data/local/tmp/spike-missing.apk", android8.detail)

        val android16 = ApkInstall.parse("""
            |Error: Unable to open file: /data/local/tmp/spike-missing.apk
            |Error: Can't open file: /data/local/tmp/spike-missing.apk
            |
            |Exception occurred while executing 'install':
            |java.lang.IllegalArgumentException: Error: Can't open file: /data/local/tmp/spike-missing.apk
            |	at com.android.server.pm.PackageManagerShellCommand.setParamsSize(PackageManagerShellCommand.java:688)
            |""".trimMargin()) as Result.Refused
        assertEquals(Refusal.OTHER, android16.reason)
        assertEquals("Error: Unable to open file: /data/local/tmp/spike-missing.apk", android16.detail)

        val unknown = ApkInstall.parse("something unexpected\n") as Result.Refused
        assertEquals(Refusal.OTHER, unknown.reason)
        assertEquals("something unexpected", unknown.detail)
    }

    @Test fun noAnswerAtAllIsUnconfirmedRatherThanAFailure() {
        // The device may well have installed it; only its answer is missing.
        assertEquals(Result.Unconfirmed, ApkInstall.parse(""))
        assertEquals(Result.Unconfirmed, ApkInstall.parse(" \r\n\n"))
    }

    @Test fun theDevicesLineIsBounded() {
        val refused = ApkInstall.parse("Failure [INSTALL_FAILED_INTERNAL_ERROR: ${"x".repeat(5_000)}]") as Result.Refused
        assertTrue(refused.detail.length.toString(), refused.detail.length <= ApkInstall.MAX_DETAIL_LENGTH)
        assertTrue(refused.detail, refused.detail.endsWith("…"))
    }

    @Test fun everyReasonHasItsOwnMessage() {
        val messages = Refusal.entries.map { ApkInstall.message(it) }
        assertEquals("Each reason needs its own wording", messages.size, messages.toSet().size)
        assertEquals(R.string.install_refused_other, ApkInstall.message(Refusal.OTHER))
        assertEquals(R.string.install_refused_newer, ApkInstall.message(Refusal.NEWER_INSTALLED))
    }

    @Test fun theCommandsQuoteThePathAndAllowReplacementAndTestBuilds() {
        val path = ApkInstall.remotePath("0123456789abcdef")
        assertEquals("/data/local/tmp/scrcpy-remote-install-0123456789abcdef.apk", path)
        // -r replaces an installed copy (an update); -t accepts the test-only builds Android
        // Studio makes, which Android otherwise refuses with INSTALL_FAILED_TEST_ONLY.
        assertEquals("pm install -r -t $path", ApkInstall.installCommand(path))
        assertEquals("rm -f $path", ApkInstall.removeCommand(path))
        assertEquals("pm install -r -t '/data/local/tmp/a b.apk'", ApkInstall.installCommand("/data/local/tmp/a b.apk"))
        assertEquals("rm -f '/data/local/tmp/it'\\''s.apk'", ApkInstall.removeCommand("/data/local/tmp/it's.apk"))
    }

    @Test fun eachInstallWaitsInAFileOfItsOwn() {
        val first = ApkInstall.newRemotePath()
        val second = ApkInstall.newRemotePath()
        assertNotEquals(first, second)
        for (path in listOf(first, second)) {
            assertTrue(path, path.matches(Regex("/data/local/tmp/scrcpy-remote-install-[0-9a-f]{32}\\.apk")))
        }
    }

    @Test fun onlyThisAppsInstallCopiesLeftForAnHourAreSwept() {
        // A copy is stranded only when the connection drops mid-install, so anything this young
        // may still be another install in progress.
        assertEquals("find /data/local/tmp -maxdepth 1 -name 'scrcpy-remote-install-*.apk' -mmin +60 " +
            "-exec rm -f {} +", ApkInstall.sweepCommand())
    }

    @Test fun onlyAWholeAppPackageCountsAsOne() {
        assertTrue(ApkInstall.looksLikeApk("app-release.apk", null))
        assertTrue(ApkInstall.looksLikeApk("SHOUTING.APK", null))
        assertTrue(ApkInstall.looksLikeApk("download", ApkInstall.MIME_TYPE))
        assertTrue(ApkInstall.looksLikeApk(null, "Application/Vnd.Android.Package-Archive"))
        // Bundles are several files and need a different installer.
        assertFalse(ApkInstall.looksLikeApk("game.apks", null))
        assertFalse(ApkInstall.looksLikeApk("game.xapk", null))
        assertFalse(ApkInstall.looksLikeApk("base.apkm", null))
        assertFalse(ApkInstall.looksLikeApk("notes.apk.txt", "text/plain"))
        assertFalse(ApkInstall.looksLikeApk(".apk", null))
        assertFalse(ApkInstall.looksLikeApk(null, null))
        assertFalse(ApkInstall.looksLikeApk("photo.jpg", "image/jpeg"))
    }
}
