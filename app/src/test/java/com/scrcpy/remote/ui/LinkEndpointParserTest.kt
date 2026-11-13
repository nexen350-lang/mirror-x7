package com.scrcpy.remote.ui

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.StreamingPreset
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LinkEndpointParserTest {
    @Test
    fun acceptsBothDocumentedFormats() {
        val expected = LinkEndpoint("192.168.1.20", 5555)
        assertEquals(expected, parseLinkEndpoint("scrcpy://connect?host=192.168.1.20&port=5555"))
        assertEquals(expected, parseLinkEndpoint("scrcpy://192.168.1.20:5555"))
    }

    @Test
    fun onlyAbsentPortsUseDefault() {
        assertEquals(LinkEndpoint("device.local", 5555), parseLinkEndpoint("scrcpy://device.local"))
        assertEquals(LinkEndpoint("device.local", 5555), parseLinkEndpoint("scrcpy://connect?host=device.local"))
        for (port in listOf("", "bad", "0", "-1", "65536", "2147483648", "+5555", "55.55", "%35x")) {
            assertNull(port, parseLinkEndpoint("scrcpy://device.local:$port"))
            assertNull(port, parseLinkEndpoint("scrcpy://connect?host=device.local&port=$port"))
        }
    }

    @Test
    fun shortFormAcceptsHostsJavaNetUriCannotParse() {
        assertEquals(LinkEndpoint("Galaxy_S21.lan", 5555), parseLinkEndpoint("scrcpy://Galaxy_S21.lan:5555"))
        assertEquals(LinkEndpoint("Galaxy_S21.lan", 5555), parseLinkEndpoint("scrcpy://Galaxy_S21.lan"))
        assertEquals(LinkEndpoint("Galaxy_S21.lan", 1234), parseLinkEndpoint("scrcpy://Galaxy_S21.lan:1234"))
        assertNull(parseLinkEndpoint("scrcpy://Galaxy_S21.lan:0"))
        assertNull(parseLinkEndpoint("scrcpy://Galaxy_S21.lan:bad"))
        assertNull(parseLinkEndpoint("scrcpy://user@Galaxy_S21.lan:5555"))
        assertNull(parseLinkEndpoint("scrcpy://Galaxy_S21.lan:5555?x=1"))
        assertNull(parseLinkEndpoint("scrcpy://_:5555/extra"))
        // Same verdict as the connect form, which runs the same host validation.
        assertEquals(
            parseLinkEndpoint("scrcpy://connect?host=x.123&port=5555"),
            parseLinkEndpoint("scrcpy://x.123:5555"),
        )
    }

    @Test
    fun loneSurrogatesInANameRefuseTheLink() {
        val base = "scrcpy://connect?host=10.0.0.2&name="
        assertNull(parseLinkEndpoint(base + "a\uD83Db"))
        assertNull(parseLinkEndpoint(base + "a\uDE00b"))
        assertNull(parseLinkEndpoint(base + "a\uD83D"))
        assertNull(parseLinkEndpoint(base + "%F0%9F%98")) // truncated bytes, still invalid
        assertEquals("a\uD83D\uDE00b", parseLinkEndpoint(base + "a\uD83D\uDE00b")?.name)
        assertEquals("a\uD83D\uDE00b", parseLinkEndpoint(base + "a%F0%9F%98%80b")?.name)
    }

    @Test
    fun acceptsBoundaryPorts() {
        assertEquals(LinkEndpoint("device.local", 1), parseLinkEndpoint("scrcpy://device.local:1"))
        assertEquals(LinkEndpoint("device.local", 65535), parseLinkEndpoint("scrcpy://device.local:65535"))
    }

    @Test
    fun rejectsMissingInvalidAndOpaqueUris() {
        for (value in listOf(null, "", " ", "scrcpy:", "scrcpy:connect?host=device.local",
            "scrcpy://", "scrcpy:///device.local", "scrcpy://connect", "scrcpy://connect?port=5555",
            "scrcpy://connect?host=", "http://device.local:5555", "not a URI",
        )) {
            assertNull(value, parseLinkEndpoint(value))
        }
    }

    @Test
    fun rejectsAmbiguousAuthoritiesPathsAndDuplicateParameters() {
        for (value in listOf(
            "scrcpy://user@device.local:5555",
            "scrcpy://device.local:5555/path",
            "scrcpy://device.local:5555#fragment",
            "scrcpy://device.local:5555?host=other.local",
            "scrcpy://connect?host=device.local&host=other.local",
            "scrcpy://connect?host=device.local&port=1&port=2",
            "scrcpy://connect?host=device.local&unexpected=value",
            "scrcpy://connect?host=device.local&",
            "scrcpy://connect?host=%",
        )) {
            assertNull(value, parseLinkEndpoint(value))
        }
    }

    @Test
    fun rejectsInvalidHostsInsteadOfUsingConnectAsHost() {
        for (host in listOf("%20", "%20device.local", "device.local%20", "device%0Alocal",
            "device%2Flocal", "device%40local", "999.1.2.3", "1.2.3", "-invalid.local", "invalid..local",
        )) {
            assertNull(host, parseLinkEndpoint("scrcpy://connect?host=$host"))
        }
    }

    @Test
    fun supportsIpv6WithoutLeakingUriBracketsToConnection() {
        val expected = LinkEndpoint("2001:db8::1", 5555)
        assertEquals(expected, parseLinkEndpoint("scrcpy://[2001:db8::1]:5555"))
        assertEquals(expected, parseLinkEndpoint("scrcpy://connect?host=2001%3Adb8%3A%3A1&port=5555"))
        assertEquals(expected, parseLinkEndpoint("scrcpy://connect?host=%5B2001%3Adb8%3A%3A1%5D"))
        assertNull(parseLinkEndpoint("scrcpy://2001:db8::1:5555"))
        assertNull(parseLinkEndpoint("scrcpy://[2001:db8::1]:"))
    }

    @Test
    fun schemeAndRouteAreLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(LinkEndpoint("device.local", 5555), parseLinkEndpoint("SCRCPY://CONNECT?host=device.local"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun aGeneratedLinkParsesBackToTheSameDestination() {
        // The app now produces links as well as accepting them, so the two halves have to agree:
        // anything Share hands out must come back as the device it named.
        for (triple in listOf(
            Triple("192.168.1.20", 5555, "Desk phone"),
            Triple("10.0.0.2", 41234, "Tablet & dock"),
            Triple("phone.local", 5555, null),
            Triple("::1", 5555, "Loopback"),
            Triple("2001:db8::1", 40000, "IPv6 phone"),
        )) {
            val (host, port, name) = triple
            val link = connectionLink(host, port, name)
            val parsed = parseLinkEndpoint(link)
            assertNotNull(link, parsed)
            assertEquals(link, host, parsed!!.host)
            assertEquals(link, port, parsed.port)
            assertEquals(link, name, parsed.name)
        }
    }

    @Test fun aNameSurvivesCharactersThatWouldOtherwiseBreakTheQuery() {
        // A device name is free text, so it has to be encoded rather than concatenated.
        val awkward = "A&B=C? #1 100%"
        val parsed = parseLinkEndpoint(connectionLink("10.0.0.5", 5555, awkward))
        assertEquals(awkward, parsed?.name)
        assertEquals("10.0.0.5", parsed?.host)
    }

    @Test fun aLinkWithoutANameStillConnects() {
        assertEquals(null, parseLinkEndpoint("scrcpy://10.0.0.9:5555")?.name)
        assertEquals("10.0.0.9", parseLinkEndpoint("scrcpy://10.0.0.9:5555")?.host)
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9")?.name)
    }

    @Test fun anEmptyOrControlOnlyNameIsDroppedRatherThanShown() {
        // The name is only a label; a blank or unprintable one would make a confirmation dialog
        // that says nothing about where it is about to connect.
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=")?.name)
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%20%20")?.name)
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%01%02")?.name)
    }

    @Test fun aNameOfInvisibleCharactersIsDroppedRatherThanShownAsNothing() {
        // The label sits in the prompt that guards an unknown address, so one that renders as
        // nothing at all is worse than none: it passes the "not empty" check and then says
        // nothing about where the link is about to connect. Zero-width joiner, zero-width space,
        // a bidi override, a C1 control and a line separator.
        for (invisible in listOf("%E2%80%8D", "%E2%80%8B", "%E2%80%AE", "%C2%85", "%E2%80%A8")) {
            assertEquals(invisible, null,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=$invisible")?.name)
        }
    }

    @Test fun anEmojiNameSurvivesIntact() {
        // Emoji are surrogate pairs, so a filter written against "unusual" characters can easily
        // destroy an ordinary name someone chose on purpose.
        assertEquals("Desk \uD83D\uDCF1",
            parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=Desk%20%F0%9F%93%B1")?.name)
    }

    @Test fun anOverlongNameKeepsWholeCharactersAtTheCut() {
        // Cutting between the halves of a surrogate pair leaves a placeholder box in the prompt.
        val name = "a".repeat(99) + "\uD83D\uDE00"
        val encoded = "a".repeat(99) + "%F0%9F%98%80"
        val parsed = parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=$encoded")?.name
        assertEquals(99, parsed?.length)
        assertEquals(name.take(99), parsed)
    }

    @Test fun anOverlongNameIsTruncatedRatherThanRejected() {
        val long = "n".repeat(500)
        val parsed = parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=$long")
        assertEquals(100, parsed?.name?.length)
    }

    @Test fun aRepeatedNameParameterIsStillRejected() {
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=a&name=b"))
    }

    @Test fun anUnknownParameterIsStillRejected() {
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&colour=red"))
    }

    @Test fun aLinkCanAskForACodecSoundAndScreenBehaviour() {
        val link = parseLinkEndpoint("scrcpy://connect?host=192.0.2.20&port=555&codec=h265&audio=device&screenOff=true")
        assertNotNull(link)
        assertEquals("192.0.2.20", link!!.host)
        assertEquals(555, link.port)
        assertEquals(VideoCodec.H265, link.request.codec)
        assertEquals(LinkAudio.DEVICE, link.request.audio)
        assertEquals(true, link.request.screenOff)
        assertTrue(link.hasOverrides)
    }

    @Test fun thoseRequestsSurviveBeingBuiltAndParsedAgain() {
        val request = LinkRequest(
            codec = VideoCodec.H265, audio = LinkAudio.DEVICE, screenOff = true)
        val built = connectionLink("192.0.2.20", 555, "Desk phone", request)
        val parsed = parseLinkEndpoint(built)
        assertEquals(built, request, parsed?.request)
        assertEquals(built, "Desk phone", parsed?.name)
    }

    @Test fun legacyServerLinksMigrateAndNewLinksNameThePatchRelease() {
        for (version in listOf("4.1", "5.0", "V4_1", "V5_0", "5.0.1")) {
            val parsed = parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&server=$version")
            assertEquals(ScrcpyServerVersion.V5_0, parsed?.request?.server)
            assertEquals("server=5.0.1", parsed?.request?.toQuery())
        }
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&server=5.0.2"))
    }

    @Test fun everyOptionSurvivesBeingBuiltAndParsedAgain() {
        // One link carrying the whole surface at once, so a parameter that was added to the
        // builder but not the parser (or the reverse) cannot pass unnoticed.
        val request = LinkRequest(
            preset = StreamingPreset.MOST_RESPONSIVE,
            maxSize = 1280,
            bitrateMbps = 12,
            maxFps = 45,
            codec = VideoCodec.AV1,
            orientation = CaptureOrientation.LOCK_90,
            displayId = 2,
            audio = LinkAudio.PLAYBACK,
            audioCodec = AudioCodec.FLAC,
            screenOff = true,
            stayAwake = true,
            showTouches = false,
            audioDup = true,
            audioBitrateKbps = 192,
            keepActive = true,
            server = ScrcpyServerVersion.DEFAULT,
        )
        val built = connectionLink("10.0.0.2", 5555, "Everything", request)
        assertEquals(built, request, parseLinkEndpoint(built)?.request)
    }

    @Test fun everyOptionIsNamedInTheParameterList() {
        // A parameter the parser accepts but the link filter does not know rejects its own link.
        val named = LinkRequest(
            preset = StreamingPreset.BALANCED, maxSize = 1600, bitrateMbps = 8, maxFps = 60,
            codec = VideoCodec.H264, orientation = CaptureOrientation.FOLLOW_DEVICE, displayId = 0,
            audio = LinkAudio.PLAYBACK, audioCodec = AudioCodec.OPUS,
            screenOff = false, stayAwake = false, showTouches = false,
            audioDup = false, audioBitrateKbps = 128, keepActive = false, server = ScrcpyServerVersion.V5_0,
        ).toQuery().split('&').map { it.substringBefore('=') }
        assertEquals(LinkRequest.NAMES, named.toSet())
        assertEquals(LinkRequest.NAMES.size, named.size)
    }

    @Test fun aLinkThatOnlyNamesADestinationAsksForNothing() {
        val link = parseLinkEndpoint("scrcpy://connect?host=10.0.0.2")
        assertEquals(false, link?.hasOverrides)
        assertEquals(LinkRequest.EMPTY, link?.request)
    }

    @Test fun everyCodecAndAudioChoiceIsAccepted() {
        for (codec in VideoCodec.entries) {
            assertEquals(codec,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&codec=${codec.serverValue}")?.request?.codec)
        }
        for (audio in LinkAudio.entries) {
            assertEquals(audio,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=${audio.token}")?.request?.audio)
        }
        for (codec in AudioCodec.entries) {
            assertEquals(codec,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audioCodec=${codec.serverValue}")?.request?.audioCodec)
        }
        for (preset in StreamingPreset.entries) {
            assertEquals(preset,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&preset=${preset.token}")?.request?.preset)
        }
        for (orientation in CaptureOrientation.entries) {
            val token = LinkRequest.orientationToken(orientation)
            assertEquals(token, orientation,
                parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&orientation=$token")?.request?.orientation)
        }
    }

    @Test fun anUnrecognisedRequestIsRefusedRatherThanIgnored() {
        // A link that silently did something other than what it says would be worse than one
        // that does not open at all.
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&codec=h999"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=loud"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&screenOff=maybe"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&preset=fastest"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audioCodec=mp3"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&orientation=sideways"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&orientation=45"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&stayAwake=yes"))
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&showTouches=1"))
    }

    @Test fun aNumberOutsideWhatTheSettingsAllowIsRefused() {
        // The same bounds the settings screen enforces, so a link cannot ask for something the
        // app would not let anyone type.
        for (bad in listOf("maxSize=639", "maxSize=2561", "maxSize=big", "maxSize=",
            "bitrate=0", "bitrate=31", "fps=14", "fps=121", "displayId=-1", "displayId=4097")) {
            assertEquals(bad, null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&$bad"))
        }
        for (good in listOf("maxSize=640", "maxSize=2560", "bitrate=1", "bitrate=30",
            "fps=15", "fps=120", "displayId=0", "displayId=4096")) {
            assertNotNull(good, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&$good"))
        }
    }

    @Test fun oneBadValueRefusesTheWholeLinkEvenBesideGoodOnes() {
        assertEquals(null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&codec=h265&fps=999"))
    }

    @Test fun aValueBesideAPresetWins() {
        // "Responsive, but 45 fps" has to mean exactly that, or the preset would quietly
        // overwrite the more specific thing the link asked for.
        val applied = LinkRequest(preset = StreamingPreset.MOST_RESPONSIVE, maxFps = 45)
            .applyTo(MirrorSettings())
        assertEquals(StreamingPreset.MOST_RESPONSIVE.maxSize, applied.maxSize)
        assertEquals(StreamingPreset.MOST_RESPONSIVE.videoBitRateMbps, applied.videoBitRateMbps)
        assertEquals(45, applied.maxFps)
    }

    @Test fun aRequestedCodecDropsAnEncoderThatBelongedToAnotherCodec() {
        // The server refuses a mismatched codec/encoder pair, and the user only sees a session
        // that never starts.
        val settings = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.android.avc.encoder")
        val applied = LinkRequest(codec = VideoCodec.H265).applyTo(settings)
        assertEquals(VideoCodec.H265, applied.videoCodec)
        assertEquals(null, applied.videoEncoder)
    }

    @Test fun aRequestedAudioCodecDropsAnEncoderThatBelongedToAnotherCodec() {
        val settings = MirrorSettings(audioCodec = AudioCodec.OPUS, audioEncoder = "c2.android.opus.encoder")
        val applied = LinkRequest(audioCodec = AudioCodec.AAC).applyTo(settings)
        assertEquals(AudioCodec.AAC, applied.audioCodec)
        assertEquals(null, applied.audioEncoder)
    }

    @Test fun namingTheCodecAlreadyInUseKeepsItsEncoder() {
        val settings = MirrorSettings(videoCodec = VideoCodec.H264, videoEncoder = "c2.android.avc.encoder")
        val applied = LinkRequest(codec = VideoCodec.H264).applyTo(settings)
        assertEquals("c2.android.avc.encoder", applied.videoEncoder)
    }

    @Test fun namingADisplayStopsASavedVirtualDisplayFromSwallowingIt() {
        // The server refuses display_id and new_display together, so the options builder drops
        // display_id whenever a virtual display is configured. A device saved that way would
        // therefore take the link's "display 2", promise it in the prompt, and then mirror the
        // virtual display instead — the link doing something other than what it says.
        val settings = MirrorSettings(virtualDisplay = "1920x1080")
        val applied = LinkRequest(displayId = 2).applyTo(settings)
        assertEquals(2, applied.displayId)
        assertNull(applied.virtualDisplay)

        val args = applied.toOptions().toServerArgs()
        assertTrue(args.toString(), "display_id=2" in args)
        assertTrue(args.toString(), args.none { it.startsWith("new_display") })
    }

    @Test fun namingAPhysicalDisplayOverridesSavedCameraForTheSessionOnly() {
        val camera = CameraCapture(id = "0", size = "1280x720", fps = 30)
        val settings = MirrorSettings(camera = camera, displayId = 5, virtualDisplay = "1920x1080")
        for (display in listOf(0, 2)) {
            val link = checkNotNull(parseLinkEndpoint("scrcpy://connect?host=phone.local&displayId=$display"))
            val applied = link.request.applyTo(settings)

            assertEquals(display, applied.displayId)
            assertNull("An explicit display must not leave camera capture active", applied.camera)
            assertNull(applied.virtualDisplay)
            val args = applied.toOptions().toServerArgs()
            assertTrue(args.toString(), args.none {
                it == "video_source=camera" || it.startsWith("camera_") || it.startsWith("new_display")
            })
            if (display == 0) {
                assertTrue(args.toString(), args.none { it.startsWith("display_id=") })
            } else {
                assertTrue(args.toString(), "display_id=$display" in args)
            }
            assertEquals(camera, settings.camera)
            assertEquals(5, settings.displayId)
            assertEquals("1920x1080", settings.virtualDisplay)
        }
    }

    @Test fun aLinkWithoutADisplayOverrideKeepsASavedCamera() {
        val camera = CameraCapture(id = "0", size = "1280x720", fps = 30)
        val settings = MirrorSettings(camera = camera, displayId = 5, virtualDisplay = "1920x1080")
        val link = checkNotNull(parseLinkEndpoint("scrcpy://connect?host=phone.local&fps=45"))
        val applied = link.request.applyTo(settings)

        assertEquals(45, applied.maxFps)
        assertEquals(camera, applied.camera)
        assertEquals(settings.displayId, applied.displayId)
        assertEquals(settings.virtualDisplay, applied.virtualDisplay)
    }

    @Test fun aLinkThatSaysNothingAboutDisplaysLeavesAVirtualDisplayAlone() {
        val settings = MirrorSettings(virtualDisplay = "1920x1080")
        val applied = LinkRequest(codec = VideoCodec.H265).applyTo(settings)
        assertEquals("1920x1080", applied.virtualDisplay)
    }

    @Test fun aRequestOnlyChangesWhatItNames() {
        val settings = MirrorSettings(
            maxSize = 1280, touchOffsetXDp = 9, audioEnabled = true, audioSource = AudioSource.MIC)
        val applied = LinkRequest(screenOff = true).applyTo(settings)
        assertEquals(true, applied.turnScreenOff)
        assertEquals(1280, applied.maxSize)
        assertEquals(9, applied.touchOffsetXDp)
        assertEquals(AudioSource.MIC, applied.audioSource)
    }

    @Test fun anEmptyRequestChangesNothingAtAll() {
        val settings = MirrorSettings(
            maxSize = 1280, videoBitRateMbps = 3, maxFps = 30, videoCodec = VideoCodec.VP9,
            videoEncoder = "custom", displayId = 3, stayAwake = true, showTouches = true)
        assertEquals(settings, LinkRequest.EMPTY.applyTo(settings))
    }

    @Test fun aLinkCannotReachAnythingWithALastingEffect() {
        // A link arrives from outside the app, so launching an app or creating a display on the
        // remote phone stays out of reach no matter what the URL says.
        for (forbidden in listOf("startApp=com.android.settings", "virtualDisplay=1920x1080",
            "videoEncoder=c2.android.avc.encoder", "audioEncoder=c2.android.opus.encoder",
            "powerOffOnClose=true", "autoReconnect=true")) {
            assertEquals(forbidden, null, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&$forbidden"))
        }
    }

    @Test fun aRequestSurvivesTheIntentThatCarriesIt() {
        val request = LinkRequest(
            preset = StreamingPreset.BEST_PICTURE, maxFps = 90, codec = VideoCodec.H265,
            orientation = CaptureOrientation.LOCK_180, audioCodec = AudioCodec.AAC,
            showTouches = true)
        assertEquals(request, LinkRequest.parseQuery(request.toQuery()))
    }

    @Test fun aDamagedCarriedRequestAsksForNothingRatherThanGuessing() {
        assertEquals(LinkRequest.EMPTY, LinkRequest.parseQuery("fps=notanumber"))
        assertEquals(LinkRequest.EMPTY, LinkRequest.parseQuery(""))
        assertEquals(LinkRequest.EMPTY, LinkRequest.parseQuery(null))
    }

    @Test fun turningSoundOffLeavesTheSourceAlone() {
        val applied = LinkRequest(audio = LinkAudio.OFF).applyTo(MirrorSettings(audioSource = AudioSource.MIC))
        assertEquals(false, applied.audioEnabled)
        assertEquals(AudioSource.MIC, applied.audioSource)
    }

    @Test fun invisibleSupplementaryCharactersCannotFormOrHideInAName() {
        // U+E0001 LANGUAGE TAG is a format character outside the Basic Multilingual Plane, so it
        // reaches Kotlin as two surrogate halves that each look like ordinary characters.
        val tag = String(Character.toChars(0xE0001))
        assertNull(validatedLinkName(tag))
        assertNull(validatedLinkName(tag + "\u200B"))
        assertEquals("Phone", validatedLinkName("Pho" + tag + "ne"))
        assertEquals("Emoji are visible and kept", "Phone \uD83D\uDCF1", validatedLinkName("Phone \uD83D\uDCF1"))
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%F3%A0%80%81")?.name)
    }

    @Test fun blankFillerCharactersDoNotMakeAVisibleName() {
        for (filler in listOf(0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x17B5, 0xFE0F, 0x0301)) {
            val blank = String(Character.toChars(filler))
            assertNull("U+%04X alone".format(filler), validatedLinkName(blank))
            assertNull("U+%04X padded".format(filler), validatedLinkName(" $blank$blank "))
            // A combining mark is legitimate inside text; only alone is it blank.
            if (filler != 0x0301) assertEquals("Phone", validatedLinkName("Pho${blank}ne"))
        }
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%E3%85%A4")?.name)
        assertEquals("Kitchen \u00e9", validatedLinkName("Kitchen \u00e9"))
    }

    @Test fun malformedPercentEncodingRefusesTheLink() {
        // %C3 starts a two-byte sequence that "(" cannot finish. Replacing it would show a label
        // the link never carried, so the link is refused like any other invalid value.
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%C3%28"))
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=%"))
        assertEquals("Desk phone", parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=Desk+phone")?.name)
        assertEquals("Caf\u00E9", parseLinkEndpoint("scrcpy://connect?host=10.0.0.9&name=Caf%C3%A9")?.name)
    }
    @Test fun aLinkCanChooseWhichServerTheDeviceRuns() {
        for (server in ScrcpyServerVersion.entries) {
            val link = parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&server=${server.version}")
            assertEquals(server, link?.request?.server)
            assertEquals(server, link!!.request.applyTo(MirrorSettings()).serverVersion)
        }
        // A version the app does not carry is refused rather than replaced by another.
        for (bad in listOf("6.0", "4", "v4.1", "", "4.1.0")) {
            assertNull(bad, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&server=$bad"))
        }
    }

    @Test fun keepingSoundOnTheDeviceNeedsPlaybackCaptureInTheSameLink() {
        val both = parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=playback&audioDup=true")
        val applied = both!!.request.applyTo(MirrorSettings(audioSource = AudioSource.OUTPUT))
        assertEquals(AudioSource.PLAYBACK to true, applied.audioSource to applied.audioDup)
        assertEquals(false, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=playback&audioDup=false")
            ?.request?.applyTo(MirrorSettings(audioSource = AudioSource.PLAYBACK, audioDup = true))?.audioDup)
        // Duplication means nothing for another source, and a link must not quietly change the
        // source to make it mean something, nor quietly drop it: the whole link is refused.
        for (conflict in listOf("audioDup=true", "audioDup=false", "audio=device&audioDup=true",
            "audio=mic&audioDup=true", "audio=off&audioDup=true", "audio=playback&audioDup=maybe")) {
            assertNull(conflict, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&$conflict"))
        }
    }

    @Test fun soundQualityStaysWithinWhatTheSettingsAllow() {
        for (good in listOf(16, 128, 320)) {
            val link = parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audioBitrate=$good")
            assertEquals(good, link?.request?.applyTo(MirrorSettings())?.audioBitRateKbps)
        }
        for (bad in listOf("15", "321", "128k", "", "-1")) {
            assertNull(bad, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audioBitrate=$bad"))
        }
        // A quality for sound the same link turns off says two things at once.
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&audio=off&audioBitrate=128"))
    }

    @Test fun keepActiveIsAsBooleanAsTheOtherDeviceOptions() {
        assertEquals(true, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&keepActive=true")
            ?.request?.applyTo(MirrorSettings())?.keepActive)
        assertEquals(false, parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&keepActive=false")
            ?.request?.applyTo(MirrorSettings(keepActive = true))?.keepActive)
        assertNull(parseLinkEndpoint("scrcpy://connect?host=10.0.0.2&keepActive=1"))
    }

    @Test fun theNewOptionsSurviveTheIntentThatCarriesThem() {
        val request = LinkRequest(audio = LinkAudio.PLAYBACK, audioDup = true, audioBitrateKbps = 64,
            keepActive = true, server = ScrcpyServerVersion.DEFAULT)
        assertEquals(request, LinkRequest.parseQuery(request.toQuery()))
        // A carried request is checked again: a damaged pair asks for nothing rather than half of it.
        assertEquals(LinkRequest.EMPTY, LinkRequest.parseQuery("audioDup=true&server=4.1"))
    }
}