package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.scrcpy.DeviceAppListParser
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.ui.visibleApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceAppListParserTest {

    /** Reproduces the server's own formatting: a 30 character name column after a `*`/`-` marker. */
    private fun entry(system: Boolean, name: String, packageName: String): String {
        val marker = if (system) "*" else "-"
        val padding = 30 - name.length
        return if (padding > 0) " $marker $name${" ".repeat(padding)} $packageName"
        else " $marker $name\n   ${" ".repeat(30)} $packageName"
    }

    @Test fun readsNamesPackagesAndTheSystemFlag() {
        val output = buildString {
            appendLine("INFO: List of apps:")
            appendLine(entry(system = true, "Calendar", "com.android.calendar"))
            appendLine(entry(system = false, "My Notes", "com.example.notes"))
        }
        assertEquals(
            listOf(
                DeviceApp("Calendar", "com.android.calendar", system = true),
                DeviceApp("My Notes", "com.example.notes", system = false),
            ),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun readsAnAppWhoseNameFillsTheColumnAndWrapsItsPackage() {
        // The server puts the package on the next line once the name reaches the column width.
        // Without handling that, every long app name would lose its package and be unlaunchable.
        val long = "An Extremely Long Application Name"
        assertTrue(long.length >= 30)
        val output = "INFO: List of apps:\n" + entry(system = false, long, "com.example.verylong") + "\n"
        assertEquals(
            listOf(DeviceApp(long, "com.example.verylong", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun keepsNamesThatContainRunsOfSpaces() {
        // Splitting at the first wide gap would truncate this name; the package is the last token.
        val output = "INFO: List of apps:\n" + entry(system = false, "Photo  Editor", "com.example.photo") + "\n"
        assertEquals(
            listOf(DeviceApp("Photo  Editor", "com.example.photo", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun keepsAnAppWhoseLabelStartsWithSpaces() {
        // The server writes exactly one space after the marker, so anything further along is part
        // of the label. Letting the marker pattern eat those spaces moved the name column by as
        // many as the label began with, and the package then failed its offset check and the app
        // was dropped from the list entirely.
        val output = "INFO: List of apps:\n" + entry(system = false, "  Maps", "com.example.maps") + "\n"
        assertEquals(
            listOf(DeviceApp("Maps", "com.example.maps", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun aWrappedNameEndingInASpacedWordKeepsItsRealPackage() {
        // The label is long enough that the server wraps the package, and its own run of spaces
        // falls right before the last word. Splitting on the widest gap would take "Suite" for
        // the package and drop the real one on the next line, leaving the app unlaunchable.
        val name = "Professional Photo Editor  Suite"
        assertTrue(name.length >= 30)
        val output = "INFO: List of apps:\n" + entry(system = false, name, "com.example.suite") + "\n"
        assertEquals(
            listOf(DeviceApp(name, "com.example.suite", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun aWrappedNameWhoseOwnSpaceLandsOnTheColumnKeepsItsRealPackage() {
        // Here the label's space sits exactly where the separator would be, so the offset alone
        // would split it. "Pro" is not a package name, which is what rejects the split.
        val name = "Professional Photo Editor Grid Pro"
        assertEquals(' ', name[30])
        val output = "INFO: List of apps:\n" + entry(system = false, name, "com.example.grid") + "\n"
        assertEquals(
            listOf(DeviceApp(name, "com.example.grid", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun aWrappedLabelEndingInAVersionKeepsItsRealPackage() {
        // The label is long enough that the server wraps the package, and its own text happens to
        // put a space exactly on the name column with a package-shaped word after it. Splitting
        // on the column alone took "v1.2" for the package and dropped the real one, leaving an
        // app that could not be launched.
        val name = "123456789012345678901234567890 v1.2"
        assertTrue(name.length >= 30)
        assertEquals(' ', name[30])
        val output = "INFO: List of apps:\n" + entry(system = false, name, "com.example.real") + "\n"
        assertEquals(
            listOf(DeviceApp(name, "com.example.real", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun anUnrelatedIndentedLineIsNotMistakenForAWrappedPackage() {
        // The continuation is matched by its exact upstream indent, so ordinary device output
        // that merely happens to be indented cannot steal an entry's package.
        val output = buildString {
            appendLine("INFO: List of apps:")
            appendLine(entry(system = false, "Calendar", "com.android.calendar"))
            appendLine("    com.something.else")
        }
        assertEquals(
            listOf(DeviceApp("Calendar", "com.android.calendar", system = false)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun ignoresHeadersLogPrefixesAndUnrelatedOutput() {
        val output = """
            INFO: Device: [Google] google sdk_gphone64_x86_64 (Android 16)
            INFO: List of apps:
            ${entry(system = true, "Settings", "com.android.settings")}
            WARN: something unrelated
        """.trimIndent()
        assertEquals(
            listOf(DeviceApp("Settings", "com.android.settings", system = true)),
            DeviceAppListParser.parse(output),
        )
    }

    @Test fun aDanglingNameWithoutAPackageIsDroppedRatherThanInvented() {
        val output = "INFO: List of apps:\n - An Extremely Long Application Name\n"
        assertEquals(emptyList<DeviceApp>(), DeviceAppListParser.parse(output))
    }

    @Test fun emptyAndUnrelatedOutputYieldsNoApps() {
        assertEquals(emptyList<DeviceApp>(), DeviceAppListParser.parse(""))
        assertEquals(emptyList<DeviceApp>(), DeviceAppListParser.parse("ERROR: could not list"))
    }

    @Test fun theListCommandNeverLetsTheServerDeleteItself() {
        // The server unlinks its own jar on startup when cleanup is enabled, which would remove
        // the deployed server that live sessions reuse.
        val command = ScrcpySession.listAppsCommand(ScrcpyServerVersion.V5_0)
        assertTrue(command, command.contains("cleanup=false"))
        assertTrue(command, command.contains("list_apps=true"))
        assertTrue(command, command.contains("com.genymobile.scrcpy.Server 5.0.1 "))
        assertTrue(command, command.startsWith("CLASSPATH=/data/local/tmp/scrcpy-server-5.0.1.jar "))
    }

    @Test fun theListCommandsRunTheServerTheSessionRuns() {
        // Every listing uses the sole bundled current server and its matching protocol version.
        for (command in listOf(
            ScrcpySession.listAppsCommand(ScrcpyServerVersion.DEFAULT),
            ScrcpySession.listEncodersCommand(ScrcpyServerVersion.DEFAULT),
            ScrcpySession.listDisplaysCommand(ScrcpyServerVersion.DEFAULT),
            ScrcpySession.listCamerasCommand(ScrcpyServerVersion.DEFAULT),
        )) {
            assertTrue(command, command.startsWith("CLASSPATH=/data/local/tmp/scrcpy-server-5.0.1.jar "))
            assertTrue(command, command.contains("com.genymobile.scrcpy.Server 5.0.1 "))
        }
    }

    @Test fun searchingMatchesBothTheNameAndThePackage() {
        val apps = listOf(
            DeviceApp("Calendar", "com.android.calendar", true),
            DeviceApp("Notes", "com.example.notes", false),
        )
        assertEquals(apps, visibleApps(apps, "  "))
        assertEquals(listOf(apps[1]), visibleApps(apps, "note"))
        assertEquals(listOf(apps[0]), visibleApps(apps, "com.android"))
        assertEquals(listOf(apps[0]), visibleApps(apps, "CALENDAR"))
        assertEquals(emptyList<DeviceApp>(), visibleApps(apps, "nothing"))
    }

    @Test fun outputWithoutTheListHeaderIsReportedAsAFailure() {
        // A missing jar makes app_process abort with no Java-level error at all, so "no apps"
        // and "the list never ran" are indistinguishable without checking for the header.
        val failure = assertThrows(IOException::class.java) {
            DeviceAppListParser.parseServerOutput("Aborted")
        }
        assertTrue(failure.message, failure.message!!.contains("Aborted"))
        assertTrue(
            assertThrows(IOException::class.java) { DeviceAppListParser.parseServerOutput("") }
                .message!!.contains("no output")
        )
    }

    @Test fun aDeviceWithNoLaunchableAppsIsNotTreatedAsAFailure() {
        assertEquals(
            emptyList<DeviceApp>(),
            DeviceAppListParser.parseServerOutput("INFO: ${DeviceAppListParser.HEADER}\n"),
        )
    }

    @Test fun forceStoppingIsRequestedWithTheServersOwnPrefix() {
        // The server reads a leading '+' as "force-stop first"; anything else silently becomes a
        // package name it cannot find.
        assertEquals("com.example.app", ControlMessage.startApp("com.example.app").name)
        assertEquals(
            "+com.example.app",
            ControlMessage.startApp("com.example.app", forceStop = true).name,
        )
    }
}
