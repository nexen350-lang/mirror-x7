package com.scrcpy.remote

import com.scrcpy.remote.data.ProfileBackupCodec
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.ui.parseLinkEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise each independent parser, not just registry lookup, against historical selections. */
class SingleServerMigrationTest {
    private val historical = listOf("4.1", "5.0", "V4_1", "V5_0", "5.0.1")

    @Test fun globalAndSavedDeviceSettingsRetainEverythingExceptTheObsoleteServerSelection() {
        for (token in historical) {
            val settings = """{"serverVersion":"$token","maxFps":45,"audioSource":"MIC_CAMCORDER","audioDup":false}"""
            val globals = SettingsCodec.decodeSettings(settings)
            assertTrue("$token: ${globals.issues}", globals.issues.isEmpty())
            assertEquals(45, globals.value.maxFps)
            assertEquals(AudioSource.MIC_CAMCORDER, globals.value.audioSource)
            assertEquals("5.0.1", globals.value.toOptions().toServerArgs().first())
            val devices = SettingsCodec.decodeDevices("""[{"id":"retained","name":"Tablet","host":"192.0.2.10","port":5555,"isFavorite":true,"mirroringSettings":$settings}]""")
            assertTrue("$token: ${devices.issues}", devices.issues.isEmpty())
            val device = devices.value.single()
            assertEquals("retained", device.id)
            assertEquals("Tablet", device.name)
            assertTrue(device.isFavorite)
            assertEquals(45, device.mirroringSettings?.maxFps)
            assertEquals(AudioSource.MIC_CAMCORDER, device.mirroringSettings?.audioSource)
            assertEquals(ScrcpyServerVersion.DEFAULT, device.mirroringSettings?.serverVersion)
            assertFalse(SettingsCodec.encodeDevices(devices.value).contains("V4_1"))
        }
    }

    @Test fun portableBackupsMigrateGlobalAndDeviceProfilesWithoutLosingPreferences() {
        for (token in historical) {
            val settings = """{"serverVersion":"$token","maxFps":45,"audioSource":"MIC_CAMCORDER"}"""
            val raw = """{"format":"scrcpy-remote-settings","version":${ProfileBackupCodec.VERSION},"settings":$settings,"devices":[{"id":"retained","name":"Tablet","host":"192.0.2.10","mirroringSettings":$settings}]}"""
            val snapshot = ProfileBackupCodec.decode(raw.toByteArray())
            assertEquals(45, snapshot.settings.maxFps)
            assertEquals(AudioSource.MIC_CAMCORDER, snapshot.settings.audioSource)
            assertEquals("5.0.1", snapshot.settings.toOptions().toServerArgs().first())
            val device = snapshot.devices.single()
            assertEquals("retained", device.id)
            assertEquals(45, device.mirroringSettings?.maxFps)
            assertEquals(ScrcpyServerVersion.DEFAULT, device.mirroringSettings?.serverVersion)
            val exported = ProfileBackupCodec.encode(snapshot).toString(Charsets.UTF_8)
            assertFalse(exported.contains("V4_1"))
            assertEquals(snapshot, ProfileBackupCodec.decode(exported.toByteArray()))
        }
    }

    @Test fun oldLinksMigrateAndOnlyGenerateTheCurrentVersion() {
        for (token in historical) {
            val endpoint = parseLinkEndpoint("scrcpy://connect?host=192.0.2.10&server=$token&fps=45&audio=mic-camcorder")
            assertNotNull(token, endpoint)
            val request = endpoint!!.request
            val applied = request.applyTo(MirrorSettings())
            assertEquals(45, applied.maxFps)
            assertEquals(AudioSource.MIC_CAMCORDER, applied.audioSource)
            assertEquals("5.0.1", applied.toOptions().toServerArgs().first())
            assertTrue(request.toQuery().contains("server=5.0.1"))
            assertFalse(request.toQuery().contains("server=4.1"))
        }
    }
}
