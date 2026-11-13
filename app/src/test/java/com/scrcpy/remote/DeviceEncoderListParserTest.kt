package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.DeviceEncoderListParser
import com.scrcpy.remote.scrcpy.EncoderName
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpySession
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DeviceEncoderListParserTest {

    /** Reproduces the server's own formatting, including its padding to column 70. */
    private fun entry(type: String, codec: String, name: String, suffix: String = ""): String {
        val line = "    --$type-codec=$codec --$type-encoder=$name"
        val padding = (70 - line.length).coerceAtLeast(0)
        return line + " ".repeat(padding) + suffix
    }

    /** The server prints the video list and the audio list back to back, in one run. */
    private val realOutput = buildString {
        appendLine("INFO: ${DeviceEncoderListParser.VIDEO_HEADER}")
        appendLine(entry("video", "h264", "c2.android.avc.encoder", " (sw)"))
        appendLine(entry("video", "h264", "c2.qti.avc.encoder", " (hw) [vendor]"))
        appendLine(entry("video", "h265", "c2.android.hevc.encoder", " (sw)"))
        appendLine("INFO: List of audio encoders:")
        appendLine(entry("audio", "opus", "c2.android.opus.encoder", " (sw)"))
        appendLine(entry("audio", "aac", "c2.android.aac.encoder", " (sw)"))
    }

    @Test fun readsTheNameCodecAndAccelerationOfEachVideoEncoder() {
        assertEquals(
            listOf(
                DeviceEncoder("h264", "c2.android.avc.encoder", "sw", vendor = false),
                DeviceEncoder("h264", "c2.qti.avc.encoder", "hw", vendor = true),
                DeviceEncoder("h265", "c2.android.hevc.encoder", "sw", vendor = false),
            ),
            DeviceEncoderListParser.parse(realOutput),
        )
    }

    @Test fun audioEncodersAreNotMistakenForVideoOnes() {
        // Both lists are printed in the same run, so asking for video must not return an audio
        // encoder — naming one as the video encoder would make the server refuse to start.
        val video = DeviceEncoderListParser.parse(realOutput, "video")
        assertTrue(video.toString(), video.none { "opus" in it.name || "aac" in it.name })
        assertEquals(
            listOf("c2.android.opus.encoder", "c2.android.aac.encoder"),
            DeviceEncoderListParser.parse(realOutput, "audio").map { it.name },
        )
    }

    @Test fun anAliasLineStillYieldsItsEncoder() {
        // Android reports aliases with a trailing "(alias for X)"; the encoder is still usable.
        val output = "INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\n" +
            entry("video", "h264", "OMX.google.h264.encoder", " (sw) (alias for c2.android.avc.encoder)") + "\n"
        assertEquals(
            listOf(DeviceEncoder("h264", "OMX.google.h264.encoder", "sw", vendor = false)),
            DeviceEncoderListParser.parse(output),
        )
    }

    @Test fun anEncoderReportedTwiceIsOfferedOnce() {
        val output = "INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\n" +
            entry("video", "h264", "c2.android.avc.encoder", " (sw)") + "\n" +
            entry("video", "h264", "c2.android.avc.encoder", " (sw)") + "\n"
        assertEquals(1, DeviceEncoderListParser.parse(output).size)
    }

    @Test fun olderDevicesReportNoAccelerationClassAtAll() {
        // The server only appends (sw)/(hw) on Android 10 and newer.
        val output = "INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\n" +
            "    --video-codec=h264 --video-encoder=OMX.qcom.video.encoder.avc\n"
        assertEquals(
            listOf(DeviceEncoder("h264", "OMX.qcom.video.encoder.avc", null, vendor = false)),
            DeviceEncoderListParser.parse(output),
        )
    }

    @Test fun outputWithoutTheListHeaderIsReportedAsAFailure() {
        // A missing jar makes app_process abort with no Java-level error, so "no encoders" and
        // "the listing never ran" are indistinguishable without checking for the header.
        val failure = assertThrows(IOException::class.java) {
            DeviceEncoderListParser.parseServerOutput("Aborted")
        }
        assertTrue(failure.message, failure.message!!.contains("Aborted"))
        assertTrue(
            assertThrows(IOException::class.java) { DeviceEncoderListParser.parseServerOutput("") }
                .message!!.contains("no output")
        )
    }

    @Test fun aDeviceReportingNoVideoEncodersIsNotTreatedAsAFailure() {
        assertEquals(
            emptyList<DeviceEncoder>(),
            DeviceEncoderListParser.parseServerOutput("INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\n"),
        )
    }

    @Test fun anAudioListingThatNeverReachedItsOwnSectionIsAFailure() {
        // The audio list is printed after the video one. Stopping in between used to yield an
        // empty audio list, shown as a device with no audio encoders rather than as an error.
        val truncated = "INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\n" +
            "INFO:     --video-codec=h264 --video-encoder=c2.android.avc.encoder           (sw)\n" +
            "ERROR: audio enumeration failed\n"
        val failure = assertThrows(IOException::class.java) {
            DeviceEncoderListParser.parseServerOutput(truncated, type = "audio")
        }
        assertTrue(failure.message, failure.message!!.contains("audio enumeration failed"))
        assertEquals(1, DeviceEncoderListParser.parseServerOutput(truncated).size)
        assertEquals(
            emptyList<DeviceEncoder>(),
            DeviceEncoderListParser.parseServerOutput(
                "INFO: ${DeviceEncoderListParser.VIDEO_HEADER}\nINFO: List of audio encoders:\n", type = "audio"),
        )
    }

    @Test fun theListCommandNeverLetsTheServerDeleteItself() {
        // The server unlinks its own jar on startup when cleanup is enabled, which would remove
        // the deployed server that the live session is still using.
        val command = ScrcpySession.listEncodersCommand(ScrcpyServerVersion.V5_0)
        assertTrue(command, command.contains("cleanup=false"))
        assertTrue(command, command.contains("list_encoders=true"))
        assertFalse(command, command.contains("list_apps"))
    }

    @Test fun theEncoderReachesTheServerOnlyWhenOneWasChosen() {
        assertTrue(ScrcpyOptions().toServerArgs().none { it.startsWith("video_encoder") })
        assertTrue(
            ScrcpyOptions(videoEncoder = "c2.qti.avc.encoder").toServerArgs()
                .contains("video_encoder=c2.qti.avc.encoder")
        )
        // Blank is the same as unset; sending an empty value would be meaningless.
        assertTrue(ScrcpyOptions(videoEncoder = "  ").toServerArgs().none { it.startsWith("video_encoder") })
    }

    @Test fun onlyPlausibleEncoderNamesAreAccepted() {
        for (name in listOf("c2.android.avc.encoder", "OMX.google.h264.encoder", "c2.qti-avc_encoder")) {
            assertTrue(name, EncoderName.isUsable(name))
        }
        for (name in listOf("", " ", ".leading", "has space", "semi;colon", "a".repeat(EncoderName.MAX_LENGTH + 1))) {
            assertFalse(name, EncoderName.isUsable(name))
        }
    }
}
