package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.session.DeviceAppPages
import com.scrcpy.remote.session.DeviceAppPages.Page
import com.scrcpy.remote.session.DeviceAppPages.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening the device's own pages about one of its apps: App info, and the question whether to
 * uninstall it.
 *
 * The answers below are verbatim from `am start` on the Android 8 and Android 16 emulators this
 * release was tested against. Android 8 repeats the package it was asked about; Android 16 does
 * not, and prints a failure as an exception rather than as a line of its own.
 */
class DeviceAppPagesTest {
    @Test fun appInfoAndUninstallAreTheDevicesOwnPagesForThatPackage() {
        // Settings keeps App info in a task of its own, and on Android 16 `am start` alone only
        // brought that task back, showing whichever app's page was open there last.
        assertEquals(
            "am start --activity-clear-task -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.example.notes",
            DeviceAppPages.command(Page.APP_INFO, "com.example.notes", displayId = 0),
        )
        // The package installer asks about the app it is given every time, and clearing its task
        // could take an install question still waiting on the device with it.
        assertEquals("am start -a android.intent.action.DELETE -d package:com.example.notes",
            DeviceAppPages.command(Page.UNINSTALL, "com.example.notes", displayId = 0))
    }

    @Test fun aSecondaryDisplaySessionGetsThePageOnTheDisplayItShows() {
        assertEquals(
            "am start --activity-clear-task --display 2 -a android.settings.APPLICATION_DETAILS_SETTINGS " +
                "-d package:com.example.notes",
            DeviceAppPages.command(Page.APP_INFO, "com.example.notes", displayId = 2),
        )
        assertEquals("am start --display 2 -a android.intent.action.DELETE -d package:com.example.notes",
            DeviceAppPages.command(Page.UNINSTALL, "com.example.notes", displayId = 2))
    }

    @Test fun onlyAPackageNameEverReachesTheShell() {
        for (name in listOf("", "com.example;reboot", "com.example notes", "com.example\nreboot", "-a", "\$(id)",
            "com.example'x", "../etc", "com..example", ".com", "com.example.", "com.exam/ple", "1com.example",
            "com.example&&reboot", "com.example|sh", "com.example`id`")) {
            assertNull("'$name' must be refused", DeviceAppPages.command(Page.APP_INFO, name, displayId = 0))
            assertNull("'$name' must be refused", DeviceAppPages.command(Page.UNINSTALL, name, displayId = 0))
        }
        assertNull(DeviceAppPages.command(Page.APP_INFO, "com.example.notes", displayId = -1))
    }

    @Test fun uninstallIsOnlyOfferedForAppsTheUserAdded() {
        // Android 16 asks "Do you want to uninstall this app?" even about its own Settings app,
        // so the device's question is not a safeguard against offering it.
        assertEquals(listOf(Page.APP_INFO),
            DeviceAppPages.offered(DeviceApp("Settings", "com.android.settings", system = true)))
        assertEquals(listOf(Page.APP_INFO, Page.UNINSTALL),
            DeviceAppPages.offered(DeviceApp("Notes", "com.example.notes", system = false)))
    }

    @Test fun thePagesNeedOneOfTheDevicesOwnDisplays() {
        assertTrue(DeviceAppPages.availableFor(MirrorSettings()))
        assertTrue(DeviceAppPages.availableFor(MirrorSettings(displayId = 2)))
        // The device chooses a new display's id, and the page would open somewhere unseen.
        assertFalse(DeviceAppPages.availableFor(MirrorSettings(virtualDisplay = "")))
        assertFalse(DeviceAppPages.availableFor(MirrorSettings(virtualDisplay = "1280x720/240")))
        assertFalse("A camera shows no screen to open them on",
            DeviceAppPages.availableFor(MirrorSettings(camera = CameraCapture(id = "0"))))
        assertFalse("Not without a session", DeviceAppPages.availableFor(null))
    }

    @Test fun anOpenedPageIsOpenedWhateverTheDeviceAddsAsAWarning() {
        // Android 8, the first time and when the same page is already open.
        assertEquals(Result.Opened, DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package:com.scrcpy.remote.installfixture }\n"))
        assertEquals(Result.Opened, DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package:com.scrcpy.remote.installfixture }\n" +
                "Warning: Activity not started, its current task has been brought to the front\n"))
        // Android 16 leaves the package out.
        assertEquals(Result.Opened, DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package: }\r\n" +
                "Warning: Activity not started, intent has been delivered to currently running top-most instance.\r\n"))
        assertEquals(Result.Opened, DeviceAppPages.parse("Starting: Intent { act=android.intent.action.DELETE dat=package: }\n"))
    }

    @Test fun aPackageNamedLikeAnErrorIsStillOpened() {
        // Android 8 repeats the package back, and nothing stops a package being called this.
        assertEquals(Result.Opened, DeviceAppPages.parse(
            "Starting: Intent { act=android.intent.action.DELETE dat=package:com.example.Error.exceptionlog }\n"))
    }

    @Test fun aRefusalKeepsTheDevicesOwnReason() {
        val unresolved = DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.NOPE_NOT_REAL dat=package:com.android.settings }\n" +
                "Error: Activity not started, unable to resolve Intent { act=android.settings.NOPE_NOT_REAL " +
                "dat=package:com.android.settings flg=0x10000000 }\n")
        assertTrue(unresolved is Result.Failed)
        assertTrue((unresolved as Result.Failed).detail,
            unresolved.detail.startsWith("Error: Activity not started, unable to resolve Intent"))

        // Android 8, asked for a display the shell may not use.
        val android8 = DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package:com.android.settings }\n" +
                "Security exception: Permission Denial: starting Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS " +
                "dat=package:com.android.settings flg=0x10000000 cmp=com.android.settings/.applications.InstalledAppDetails } " +
                "from null (pid=6221, uid=2000) with launchDisplayId=77\n\n" +
                "java.lang.SecurityException: Permission Denial: starting Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS " +
                "dat=package:com.android.settings flg=0x10000000 cmp=com.android.settings/.applications.InstalledAppDetails } " +
                "from null (pid=6221, uid=2000) with launchDisplayId=77\n" +
                "\tat com.android.server.am.ActivityStackSupervisor.checkStartAnyActivityPermission(ActivityStackSupervisor.java:1666)\n" +
                "\tat com.android.server.am.ActivityStarter.startActivity(ActivityStarter.java:434)\n")
        assertTrue(android8 is Result.Failed)
        val android8Detail = (android8 as Result.Failed).detail
        assertTrue(android8Detail, android8Detail.startsWith("Security exception: Permission Denial"))
        assertTrue("Bounded: ${android8Detail.length}", android8Detail.length <= DeviceAppPages.MAX_DETAIL_LENGTH)
        assertFalse("A stack frame is not a reason", "ActivityStackSupervisor" in android8Detail)

        // The same refusal on Android 16.
        val android16 = DeviceAppPages.parse(
            "Starting: Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS dat=package: }\n\n" +
                "Exception occurred while executing 'start':\n" +
                "java.lang.SecurityException: Permission Denial: starting Intent { act=android.settings.APPLICATION_DETAILS_SETTINGS " +
                "dat=package: flg=0x10000000 xflg=0x4 cmp=com.android.settings/.applications.InstalledAppDetails } " +
                "from null (pid=19602, uid=2000) with launchDisplayId=77\n" +
                "\tat com.android.server.wm.SafeActivityOptions.checkPermissions(SafeActivityOptions.java:295)\n")
        assertTrue(android16 is Result.Failed)
        val android16Detail = (android16 as Result.Failed).detail
        assertTrue(android16Detail, "SecurityException: Permission Denial" in android16Detail)
        assertFalse("A stack frame is not a reason", "SafeActivityOptions" in android16Detail)
    }

    @Test fun anAppThatNoLongerHasThePageIsRefused() {
        // Both lines of Android's answer when the component the intent names has gone.
        assertTrue(DeviceAppPages.parse(
            "Starting: Intent { act=android.intent.action.DELETE dat=package:com.example.notes }\n" +
                "Error type 3\nError: Activity class {com.example/.Gone} does not exist.\n") is Result.Failed)
    }

    @Test fun noAnswerIsNotTakenForAnOpenedPage() {
        // `am start` always says what it is starting, so silence means it never ran.
        assertTrue(DeviceAppPages.parse("") is Result.Failed)
        assertTrue(DeviceAppPages.parse("\n  \r\n") is Result.Failed)
    }

    @Test fun whetherAnAppIsStillInstalledIsAskedByItsExactName() {
        assertEquals("pm list packages com.example.notes", DeviceAppPages.installedCheckCommand("com.example.notes"))
        assertNull(DeviceAppPages.installedCheckCommand("com.example;reboot"))
        assertNull(DeviceAppPages.installedCheckCommand(""))
        // Verbatim from both emulators. `pm list packages` matches by substring, and Android 16
        // lists an overlay package beside Settings, so only the exact line counts.
        assertEquals(true, DeviceAppPages.stillInstalled(
            "package:com.android.settings\npackage:com.android.settings.auto_generated_rro_product__\n",
            "com.android.settings"))
        assertEquals(true, DeviceAppPages.stillInstalled(
            "package:com.scrcpy.remote.test\r\npackage:com.scrcpy.remote\r\n", "com.scrcpy.remote"))
        assertEquals(false, DeviceAppPages.stillInstalled("package:com.scrcpy.remote.test\n", "com.scrcpy.remote"))
        // A package that is gone gets no line at all, on Android 8 and 16 alike.
        assertEquals(false, DeviceAppPages.stillInstalled("", "com.does.not.exist"))
    }

    @Test fun anythingButAPackageListSettlesNothing() {
        // stderr arrives with stdout, and an error must never take an app off the list.
        assertNull(DeviceAppPages.stillInstalled("/system/bin/sh: pm: not found\n", "com.example.notes"))
        assertNull(DeviceAppPages.stillInstalled(
            "Exception occurred while executing 'list':\njava.lang.SecurityException: Shell does not have permission\n",
            "com.example.notes"))
        assertNull(DeviceAppPages.stillInstalled("package:com.example.notes\nError: something\n", "com.example.notes"))
    }
}
