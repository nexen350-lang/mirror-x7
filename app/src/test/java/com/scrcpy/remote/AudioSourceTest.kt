package com.scrcpy.remote

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.ui.LinkAudio
import com.scrcpy.remote.ui.parseLinkEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AudioSourceTest {

    /** Every value the app sends must be one the bundled server's own AudioSource enum names. */
    @Test fun everySourceIsNamedByEveryBundledServer() {
        for (server in ScrcpyServerVersion.entries) {
            val dex = ZipFile(File("src/main/assets/${server.assetName}")).use { zip ->
                zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
            }
            val text = String(dex, Charsets.ISO_8859_1)
            for (source in AudioSource.entries) {
                assertTrue("${server.version} lacks ${source.serverValue}", text.contains(source.serverValue))
            }
        }
    }

    @Test fun allSourcesRemainAvailableAfterLegacySettingsMigration() {
        for (token in listOf("4.1", "5.0", "V4_1", "V5_0", "5.0.1")) {
            for (source in AudioSource.entries) {
                val decoded = SettingsCodec.decodeSettings("""{"serverVersion":"$token","audioSource":"${source.name}"}""")
                assertTrue(decoded.issues.isEmpty())
                assertEquals(source, decoded.value.audioSource)
                assertEquals("5.0.1", decoded.value.toOptions().toServerArgs().first())
            }
        }
    }

    @Test fun serverValuesAreUnique() {
        assertEquals(AudioSource.entries.size, AudioSource.entries.map { it.serverValue }.toSet().size)
    }

    @Test fun onlyCallSourcesArePrivileged() {
        assertEquals(
            setOf(AudioSource.VOICE_CALL, AudioSource.VOICE_CALL_UPLINK, AudioSource.VOICE_CALL_DOWNLINK),
            AudioSource.entries.filter { it.privileged }.toSet(),
        )
        assertTrue(AudioSource.entries.filter { it.privileged }.all { it.extended })
    }

    @Test fun extendedSourcesReachTheServerCommandLine() {
        for (source in AudioSource.entries.filter { it.extended }) {
            val args = ScrcpyOptions(audioSource = source).toServerArgs()
            assertTrue(args.contains("audio_source=${source.serverValue}"))
            // Duplication only exists for playback capture.
            assertFalse(ScrcpyOptions(audioSource = source, audioDup = true).toServerArgs()
                .any { it.startsWith("audio_dup") })
        }
    }

    @Test fun extendedSourcesSurviveTheSettingsCodec() {
        for (source in AudioSource.entries) {
            val settings = MirrorSettings(audioSource = source)
            val decoded = SettingsCodec.decodeSettings(SettingsCodec.encodeSettings(settings))
            assertEquals(source, decoded.value.audioSource)
            assertTrue(decoded.issues.isEmpty())
        }
    }

    /** Links name every source by the server's own word, so the HTML launcher and the app agree. */
    @Test fun everySourceHasALinkTokenEqualToItsServerValue() {
        val linked = LinkAudio.entries.mapNotNull { it.source }
        assertEquals(AudioSource.entries.toSet(), linked.toSet())
        for (choice in LinkAudio.entries) {
            val source = choice.source ?: continue
            if (source == AudioSource.OUTPUT) assertEquals("device", choice.token)
            else assertEquals(source.serverValue, choice.token)
        }
    }

    @Test fun aLinkCanAskForAnExtendedSource() {
        val link = parseLinkEndpoint("scrcpy://connect?host=192.0.2.20&port=5555&audio=mic-camcorder")!!
        assertEquals(LinkAudio.MIC_CAMCORDER, link.request.audio)
    }
}
