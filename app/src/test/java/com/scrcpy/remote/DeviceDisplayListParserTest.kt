package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.DeviceDisplay
import com.scrcpy.remote.scrcpy.DeviceDisplayListParser
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceDisplayListParserTest {

    /** Reproduces the server's own formatting from LogUtils.buildDisplayListMessage. */
    private fun entry(id: Int, size: String) = "    --display-id=$id    ($size)"

    @Test fun readsEveryDisplayAndItsSize() {
        val output = buildString {
            appendLine("INFO: ${DeviceDisplayListParser.HEADER}")
            appendLine(entry(0, "1080x2400"))
            appendLine(entry(2, "1920x1080"))
        }
        assertEquals(
            listOf(DeviceDisplay(0, 1080, 2400), DeviceDisplay(2, 1920, 1080)),
            DeviceDisplayListParser.parse(output),
        )
    }

    @Test fun aDisplayTheDeviceCannotMeasureIsStillOffered() {
        // The server prints "size unknown" when it has no DisplayInfo. Dropping the entry would
        // hide a display that can still be mirrored.
        val output = "INFO: ${DeviceDisplayListParser.HEADER}\n" + entry(3, "size unknown") + "\n"
        val displays = DeviceDisplayListParser.parse(output)
        assertEquals(listOf(DeviceDisplay(3, 0, 0)), displays)
        assertFalse(displays.single().sizeKnown)
    }

    @Test fun aDeviceWithNoDisplaysIsNotTreatedAsAFailure() {
        // The server prints "(none)" rather than omitting the section.
        val output = "INFO: ${DeviceDisplayListParser.HEADER}\n    (none)\n"
        assertEquals(emptyList<DeviceDisplay>(), DeviceDisplayListParser.parseServerOutput(output))
    }

    @Test fun ignoresLogPrefixesAndUnrelatedOutput() {
        val output = """
            INFO: Device: [Google] google sdk_gphone64_x86_64 (Android 16)
            INFO: ${DeviceDisplayListParser.HEADER}
            ${entry(0, "1080x2400")}
            WARN: something unrelated
        """.trimIndent()
        assertEquals(listOf(DeviceDisplay(0, 1080, 2400)), DeviceDisplayListParser.parse(output))
    }

    @Test fun aRepeatedIdIsReportedOnce() {
        val output = "INFO: ${DeviceDisplayListParser.HEADER}\n" +
            entry(0, "1080x2400") + "\n" + entry(0, "1080x2400") + "\n"
        assertEquals(1, DeviceDisplayListParser.parse(output).size)
    }

    @Test fun outputWithoutTheListHeaderIsReportedAsAFailure() {
        // A missing jar makes app_process abort with no Java-level error at all, so "no displays"
        // and "the list never ran" are indistinguishable without checking for the header.
        val failure = assertThrows(IOException::class.java) {
            DeviceDisplayListParser.parseServerOutput("Aborted")
        }
        assertTrue(failure.message, failure.message!!.contains("Aborted"))
        assertTrue(
            assertThrows(IOException::class.java) { DeviceDisplayListParser.parseServerOutput("") }
                .message!!.contains("no output")
        )
    }

    @Test fun theListCommandNeverLetsTheServerDeleteItself() {
        // The server unlinks its own jar on startup when cleanup is enabled, which would remove
        // the deployed server that live sessions reuse.
        val command = ScrcpySession.listDisplaysCommand(ScrcpyServerVersion.V5_0)
        assertTrue(command, command.contains("cleanup=false"))
        assertTrue(command, command.contains("list_displays=true"))
    }
}
