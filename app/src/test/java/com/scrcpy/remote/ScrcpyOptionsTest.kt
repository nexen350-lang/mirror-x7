package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ScrcpyOptionsTest {

    @Test fun flexibleDesktopsAreOptInAndCannotReachOtherCaptureModes() {
        assertFalse(ScrcpyOptions(newDisplay = "").toServerArgs().contains("flex_display=true"))
        val flexible = ScrcpyOptions(newDisplay = "720x1600/280", flexDisplay = true)
        assertTrue(flexible.toServerArgs().contains("flex_display=true"))
        assertTrue(flexible.toServerArgs().contains("display_ime_policy=local"))
        for (invalid in listOf(
            flexible.copy(newDisplay = null),
            flexible.copy(video = false),
            flexible.copy(control = false),
            flexible.copy(crop = "100:100:0:0"),
            flexible.copy(camera = com.scrcpy.remote.scrcpy.CameraCapture(id = "0")),
        )) {
            assertThrows(IllegalArgumentException::class.java) { invalid.toServerArgs() }
        }
    }

    @Test
    fun firstArgumentIsTheChosenServersVersion() {
        // The server refuses a client naming another version, so the argument has to follow the
        // server the session actually starts, not the newest one bundled.
        assertEquals("5.0.1", ScrcpyOptions(scid = 1).toServerArgs().first())
        for (server in ScrcpyServerVersion.entries) {
            assertEquals(server.version, ScrcpyOptions(scid = 1, server = server).toServerArgs().first())
            val options = com.scrcpy.remote.data.MirrorSettings(serverVersion = server).toOptions()
            assertEquals(server, options.server)
            assertEquals(server.version, options.toServerArgs().first())
        }
    }

    @Test
    fun scidIsHexEncodedAndMatchesTheSocketName() {
        val options = ScrcpyOptions(scid = 0x0A1B2C3D)
        assertTrue(options.toServerArgs().contains("scid=0a1b2c3d"))
        assertEquals("scrcpy_0a1b2c3d", options.socketName)
    }

    @Test
    fun forwardTunnelIsAlwaysRequested() {
        // The client connects to the device's listening socket through an ADB stream.
        assertTrue(ScrcpyOptions().toServerArgs().contains("tunnel_forward=true"))
    }

    @Test
    fun audioQualityAndClipboardPrivacyReachTheServer() {
        // Both were already serialisable here but nothing ever set them, so the settings that
        // now exist have to be shown to arrive rather than assumed to.
        val settings = com.scrcpy.remote.data.MirrorSettings(
            audioBitRateKbps = 256,
            clipboardAutosync = false,
        )
        val args = settings.toOptions().toServerArgs()
        assertTrue(args.toString(), args.contains("audio_bit_rate=256000"))
        assertTrue(args.toString(), args.contains("clipboard_autosync=false"))

        // The defaults are the server's own, so they are left unsaid rather than restated.
        val plain = com.scrcpy.remote.data.MirrorSettings().toOptions().toServerArgs()
        assertTrue(plain.toString(), plain.contains("audio_bit_rate=128000"))
        assertFalse(plain.toString(), plain.any { it.startsWith("clipboard_autosync=") })
    }

    @Test
    fun aCameraSessionAsksForTheCameraAlongsideTheOrdinaryVideoOptions() {
        // This test originally asserted that max_size and crop were withheld from a camera
        // session, which matched the code and not the server: the camera capture reads both.
        // The assertion was wrong in the same way the comment beside the code was.
        val options = ScrcpyOptions(
            maxSize = 1600,
            camera = com.scrcpy.remote.scrcpy.CameraCapture(
                id = "1", size = "1856x1392", fps = 30,
            ),
        ).toServerArgs()
        assertTrue(options.toString(), options.contains("video_source=camera"))
        assertTrue(options.toString(), options.contains("camera_id=1"))
        assertTrue(options.toString(), options.contains("camera_size=1856x1392"))
        assertTrue(options.toString(), options.contains("camera_fps=30"))
        assertTrue(options.toString(), options.contains("max_size=1600"))

        // Screen capture is unchanged by the camera fields existing.
        val screen = ScrcpyOptions(maxSize = 1600, crop = "100:200:0:0").toServerArgs()
        assertTrue(screen.toString(), screen.contains("max_size=1600"))
        assertTrue(screen.toString(), screen.contains("crop=100:200:0:0"))
        assertFalse(screen.toString(), screen.any { it.startsWith("video_source=") })
    }

    @Test
    fun aCameraIsAskedForByIdOrByFacingButNeverBoth() {
        // The facing is a way of saying "whichever camera points that way" when the id is not
        // known yet. Sending both is a contradiction the server rejects, so the pair is resolved
        // before it can reach the command line.
        val both = com.scrcpy.remote.scrcpy.CameraCapture(
            id = "0", facing = com.scrcpy.remote.scrcpy.CameraFacing.BACK,
        ).normalized()
        assertEquals("0", both.id)
        assertEquals(null, both.facing)

        val byFacing = ScrcpyOptions(
            camera = com.scrcpy.remote.scrcpy.CameraCapture(
                facing = com.scrcpy.remote.scrcpy.CameraFacing.FRONT,
            ).normalized(),
        ).toServerArgs()
        assertTrue(byFacing.toString(), byFacing.contains("camera_facing=front"))
        assertFalse(byFacing.toString(), byFacing.any { it.startsWith("camera_id=") })

        // Blank text is not a choice; it must not become an empty option the server cannot read.
        val blank = com.scrcpy.remote.scrcpy.CameraCapture(id = "  ", size = "").normalized()
        assertEquals(null, blank.id)
        assertEquals(null, blank.size)
    }

    @Test
    fun aCameraSessionKeepsTheSizeLimitAndDropsTheScreenOptions() {
        // The server picks one capture implementation. A camera one ignores the options that name
        // a screen, but it does read max_size and crop — an earlier version of this code had that
        // backwards, withheld the size limit, and streamed the camera at full resolution over
        // whatever link the user happened to be on.
        val options = ScrcpyOptions(
            maxSize = 1600,
            crop = "100:200:0:0",
            displayId = 3,
            newDisplay = "1280x720",
            camera = com.scrcpy.remote.scrcpy.CameraCapture(id = "1"),
        ).toServerArgs()
        assertTrue(options.toString(), options.contains("video_source=camera"))
        assertTrue(options.toString(), options.contains("max_size=1600"))
        assertTrue(options.toString(), options.contains("crop=100:200:0:0"))
        // Asking for a display nobody will see is not harmless: it builds one on the device.
        assertFalse(options.toString(), options.any { it.startsWith("new_display=") })
        assertFalse(options.toString(), options.any { it.startsWith("display_id=") })
        assertFalse(options.toString(), options.any { it.startsWith("display_ime_policy=") })

        // Without a camera the display options are still sent exactly as before.
        val screen = ScrcpyOptions(maxSize = 1600, newDisplay = "1280x720").toServerArgs()
        assertTrue(screen.toString(), screen.contains("new_display=1280x720"))
        assertTrue(screen.toString(), screen.contains("display_ime_policy=local"))
    }

    @Test
    fun defaultsAreOmittedFromTheArgumentList() {
        val args = ScrcpyOptions(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.OPUS).toServerArgs()
        assertFalse(args.any { it.startsWith("video_codec=") })
        assertFalse(args.any { it.startsWith("audio_codec=") })
        assertFalse(args.contains("video=false"))
        assertFalse(args.contains("audio=false"))
        assertFalse(args.contains("control=false"))
    }

    @Test
    fun nonDefaultValuesAreSerialized() {
        val args = ScrcpyOptions(
            audio = false,
            videoCodec = VideoCodec.H265,
            maxSize = 1024,
            videoBitRate = 4_000_000,
            maxFps = 30,
            stayAwake = true,
            showTouches = true,
        ).toServerArgs()

        assertTrue(args.contains("audio=false"))
        assertTrue(args.contains("video_codec=h265"))
        assertTrue(args.contains("max_size=1024"))
        assertTrue(args.contains("video_bit_rate=4000000"))
        assertTrue(args.contains("max_fps=30"))
        assertTrue(args.contains("stay_awake=true"))
        assertTrue(args.contains("show_touches=true"))
    }

    @Test
    fun shellCommandMatchesScrcpyDocumentation() {
        val command = ScrcpyOptions(scid = 0x12345678, audio = false)
            .toShellCommand("/data/local/tmp/scrcpy-server.jar")

        assertTrue(
            command.startsWith(
                "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / " +
                    "com.genymobile.scrcpy.Server 5.0.1 scid=12345678 "
            )
        )
    }

    @Test
    fun ordinaryArgumentsAreNotQuoted() {
        // Readability matters here: the command is logged and compared against scrcpy's own docs.
        assertEquals("max_size=1600", ScrcpyOptions.shellQuote("max_size=1600"))
        assertEquals("/data/local/tmp/x.jar", ScrcpyOptions.shellQuote("/data/local/tmp/x.jar"))
        assertEquals("video_codec=h265", ScrcpyOptions.shellQuote("video_codec=h265"))
    }

    @Test
    fun dangerousArgumentsAreQuoted() {
        // The arguments become one shell command line, so anything that could ever be user
        // supplied must not be able to start a second command.
        assertEquals("'a; rm -rf /'", ScrcpyOptions.shellQuote("a; rm -rf /"))
        assertEquals("'\$(whoami)'", ScrcpyOptions.shellQuote("\$(whoami)"))
        assertEquals("'a b'", ScrcpyOptions.shellQuote("a b"))
        assertEquals("''", ScrcpyOptions.shellQuote(""))
        // Embedded single quotes are closed, escaped and reopened.
        assertEquals("'it'\\''s'", ScrcpyOptions.shellQuote("it's"))
    }

    @Test
    fun injectedCropValueCannotEscapeTheCommand() {
        val command = ScrcpyOptions(scid = 1, crop = "1:2:3:4; id")
            .toShellCommand("/data/local/tmp/scrcpy-server.jar")

        assertTrue(command.contains("'crop=1:2:3:4; id'"))
        // The semicolon stays inside the quotes, so no second command is formed.
        assertFalse(command.contains("4; id "))
    }

    @Test
    fun audioEncoderIsSentOnlyWhenNamedAndCannotEscapeTheCommand() {
        // The audio counterpart to video_encoder. Without it a device whose default audio encoder
        // is broken leaves no option but turning audio off entirely.
        assertFalse(ScrcpyOptions(scid = 1).toServerArgs().any { it.startsWith("audio_encoder") })
        assertFalse(ScrcpyOptions(scid = 1, audioEncoder = "  ").toServerArgs().any { it.startsWith("audio_encoder") })

        val args = ScrcpyOptions(scid = 1, audioEncoder = "c2.android.opus.encoder").toServerArgs()
        assertTrue(args.toString(), args.contains("audio_encoder=c2.android.opus.encoder"))

        val command = ScrcpyOptions(scid = 1, audioEncoder = "enc; id")
            .toShellCommand("/data/local/tmp/scrcpy-server.jar")
        assertTrue(command.contains("'audio_encoder=enc; id'"))
        assertFalse(command.contains("enc; id "))
    }

    @Test
    fun audioEncoderIsOmittedWhenAudioIsOff() {
        // Every other audio option is inside the same guard; an encoder for a stream that is not
        // being captured would be a contradiction the server has to reconcile.
        val args = ScrcpyOptions(scid = 1, audio = false, audioEncoder = "c2.android.opus.encoder").toServerArgs()
        assertFalse(args.toString(), args.any { it.startsWith("audio_encoder") })
    }

    @Test
    fun keepActiveIsSentSeparatelyFromStayAwake() {
        // scrcpy 4.0's keep_active signals activity instead of writing a global setting, so the
        // two are independent options rather than alternative spellings of one.
        val args = ScrcpyOptions(keepActive = true).toServerArgs()
        assertTrue(args.contains("keep_active=true"))
        assertFalse(args.contains("stay_awake=true"))
        assertFalse(ScrcpyOptions().toServerArgs().any { it.startsWith("keep_active") })
    }

    @Test
    fun audioSourceIsSerializedOnlyWhenItLeavesTheDefaultOutputCapture() {
        assertFalse(ScrcpyOptions().toServerArgs().any { it.startsWith("audio_source=") })
        assertTrue(
            ScrcpyOptions(audioSource = AudioSource.PLAYBACK).toServerArgs()
                .contains("audio_source=playback"),
        )
        assertTrue(
            ScrcpyOptions(audioSource = AudioSource.MIC).toServerArgs().contains("audio_source=mic"),
        )
    }

    @Test
    fun audioDuplicationIsOnlySentForPlaybackCapture() {
        // The server only builds a mix for playback capture; asking any other source to duplicate
        // would be an unsupported combination rather than a no-op.
        assertTrue(
            ScrcpyOptions(audioSource = AudioSource.PLAYBACK, audioDup = true).toServerArgs()
                .contains("audio_dup=true"),
        )
        for (source in listOf(AudioSource.OUTPUT, AudioSource.MIC)) {
            assertFalse(
                "audio_dup must not be sent for $source",
                ScrcpyOptions(audioSource = source, audioDup = true).toServerArgs()
                    .any { it.startsWith("audio_dup") },
            )
        }
    }

    @Test
    fun audioOptionsAreOmittedEntirelyWhenAudioIsDisabled() {
        val args = ScrcpyOptions(audio = false, audioSource = AudioSource.PLAYBACK, audioDup = true)
            .toServerArgs()
        assertTrue(args.contains("audio=false"))
        assertFalse(args.any { it.startsWith("audio_source") || it.startsWith("audio_dup") })
    }

    @Test
    fun captureOrientationIsOnlySentWhenItLocksTheCapture() {
        // Values verified against the scrcpy 4.1 server, whose parsing 5.0 keeps: Options.parseCaptureOrientation accepts a
        // bare '@' for "lock to the initial orientation" and '@' plus a rotation name otherwise.
        assertFalse(ScrcpyOptions().toServerArgs().any { it.startsWith("capture_orientation") })
        assertTrue(
            ScrcpyOptions(captureOrientation = CaptureOrientation.FOLLOW_DEVICE).toServerArgs()
                .none { it.startsWith("capture_orientation") },
        )
        assertTrue(
            ScrcpyOptions(captureOrientation = CaptureOrientation.LOCK_CURRENT).toServerArgs()
                .contains("capture_orientation=@"),
        )
        for ((orientation, wire) in mapOf(
            CaptureOrientation.LOCK_0 to "capture_orientation=@0",
            CaptureOrientation.LOCK_90 to "capture_orientation=@90",
            CaptureOrientation.LOCK_180 to "capture_orientation=@180",
            CaptureOrientation.LOCK_270 to "capture_orientation=@270",
        )) {
            assertTrue(orientation.name, ScrcpyOptions(captureOrientation = orientation).toServerArgs().contains(wire))
        }
    }

    @Test
    fun captureOrientationIsOmittedWithVideoDisabled() {
        val args = ScrcpyOptions(video = false, captureOrientation = CaptureOrientation.LOCK_90).toServerArgs()
        assertTrue(args.contains("video=false"))
        assertFalse(args.any { it.startsWith("capture_orientation") })
    }

    @Test
    fun socketCountMatchesEnabledStreams() {
        assertEquals(3, ScrcpyOptions().socketCount)
        assertEquals(2, ScrcpyOptions(audio = false).socketCount)
        assertEquals(1, ScrcpyOptions(audio = false, control = false).socketCount)
    }

    @Test
    fun generatedScidIsNonNegative() {
        repeat(200) {
            val scid = ScrcpyOptions.newScid()
            assertTrue("scid must be >= 0 but was $scid", scid >= 0)
        }
    }
}
