package com.scrcpy.remote

import android.content.pm.ServiceInfo
import com.scrcpy.remote.R
import com.scrcpy.remote.session.SessionHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason the process is held up decides which foreground service type is declared, and Play
 * reviews each declared type against what the app actually does. A wrong mapping is not a visible
 * bug — it is a policy rejection — so it is pinned here rather than left to review.
 */
class SessionHoldTest {

    @Test fun onlyRealPlaybackClaimsMediaPlayback() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            SessionHold.AUDIO.foregroundServiceType(),
        )
        val playing = setOf(SessionHold.AUDIO, SessionHold.TRANSFER_WITH_AUDIO)
        for (hold in SessionHold.entries - playing) {
            assertEquals(
                "$hold must not claim media playback", 0,
                hold.foregroundServiceType() and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
    }

    @Test fun aConnectedSessionIsAConnectionToAnExternalDevice() {
        // Play's foreground service policy names this case: continuous data transfer to an external
        // device over a network connection. dataSync is for uploads, downloads and imports, and
        // Android 15 stops it after six hours in any twenty-four, which ended a long session's hold.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            SessionHold.CONNECTED.foregroundServiceType(),
        )
    }

    @Test fun sendingAFileIsDataSync() {
        // Play lists uploads and downloads under dataSync, and a transfer that has not connected
        // yet has no external device to describe.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            SessionHold.TRANSFER.foregroundServiceType(),
        )
    }

    @Test fun audioThatKeepsPlayingDuringATransferIsStillDeclaredAsPlayback() {
        // A transfer outranks the other reasons for the notification, but the background audio
        // it would otherwise have hidden is still playing and must stay declared.
        val both = SessionHold.from(connected = true, backgroundAudio = true, transfer = true)
        val type = both.foregroundServiceType()
        assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
        assertTrue(type and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        assertEquals(R.string.transfer_notification_text, both.notificationText())

        assertEquals(SessionHold.TRANSFER, SessionHold.from(connected = true, backgroundAudio = false, transfer = true))
        assertEquals(SessionHold.AUDIO, SessionHold.from(connected = true, backgroundAudio = true, transfer = false))
        assertEquals(SessionHold.CONNECTED, SessionHold.from(connected = true, backgroundAudio = false, transfer = false))
        assertEquals(SessionHold.NONE, SessionHold.from(connected = false, backgroundAudio = false, transfer = false))
    }

    @Test fun onlyAnAudioHoldIsDescribedAsPlayingAudio() {
        assertEquals(R.string.background_notification_text, SessionHold.AUDIO.notificationText())
        assertEquals(R.string.transfer_notification_text, SessionHold.TRANSFER.notificationText())
        assertEquals(R.string.background_notification_connected, SessionHold.CONNECTED.notificationText())
        for (hold in SessionHold.entries - SessionHold.AUDIO) {
            assertNotEquals(hold.name, R.string.background_notification_text, hold.notificationText())
        }
    }

    @Test fun noReasonUsesATypeThatNeedsARuntimePermissionThisAppNeverAsksFor() {
        // Each of these needs location, camera, microphone or body-sensor access granted first.
        // Without it, startForeground throws a SecurityException and takes the service down.
        val gated = setOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
        )
        for (hold in SessionHold.entries) {
            assertTrue(
                "$hold uses a type this app cannot legally start",
                gated.none { hold.foregroundServiceType() and it != 0 },
            )
        }
    }

    @Test fun connectedDeviceIsUsedOnlyWithAPrerequisiteHeldOnEveryVersion() {
        // Android 14 and newer refuse a connectedDevice start unless the app holds one of these.
        // Without one the start throws a SecurityException, which crashed the app on every connect
        // when the type was first tried. A maxSdkVersion would drop the permission on exactly the
        // versions that check it, so only a permission held everywhere counts.
        val prerequisites = setOf(
            "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "NFC", "TRANSMIT_IR",
        )
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val heldEverywhere = Regex("<uses-permission\\s[^>]*>").findAll(manifest).map { it.value }
            .filter { "maxSdkVersion" !in it && "tools:node=\"remove\"" !in it }
            .mapNotNull { Regex("android:name=\"android\\.permission\\.(\\w+)\"").find(it)?.groupValues?.get(1) }
            .toSet()
        val usesConnectedDevice = SessionHold.entries.any {
            it.foregroundServiceType() and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0
        }
        assertTrue("A connected session declares connectedDevice", usesConnectedDevice)
        assertTrue(
            "connectedDevice needs one of $prerequisites on every version; held: $heldEverywhere",
            heldEverywhere.any { it in prerequisites },
        )
    }

    @Test fun everyReasonMapsToATypeTheManifestDeclares() {
        // Starting with a type the manifest does not declare throws at runtime, so a new reason
        // must not be able to introduce one silently.
        val declared = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        for (hold in SessionHold.entries) {
            val type = hold.foregroundServiceType()
            assertTrue(hold.name, type != 0 && type and declared.inv() == 0)
        }
    }

    @Test fun theManifestDeclaresEveryTypeAndPermissionThoseReasonsNeed() {
        // Starting a foreground service with an undeclared type, or without its permission,
        // throws — and only on the device, at the moment the user connects.
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("MirrorSessionService").substringBefore("/>")
        for (type in listOf("mediaPlayback", "dataSync", "connectedDevice")) {
            assertTrue("foregroundServiceType is missing $type", type in service)
        }
        for (permission in listOf(
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "FOREGROUND_SERVICE_DATA_SYNC",
            "FOREGROUND_SERVICE_CONNECTED_DEVICE",
        )) {
            assertTrue("Missing permission $permission", permission in manifest)
        }
    }

    @Test fun theShippedManifestAsksForNoPermissionTheAppDoesNotUse() {
        // libadb's AAR declares no targetSdkVersion, so the merger implies the legacy permissions
        // an ancient app would have needed — including the dangerous READ_PHONE_STATE. A screen
        // mirroring app asking for Phone access is a trust and review problem, and it is only
        // visible in the *merged* manifest, which is why this reads that and not the source.
        val merged = sequenceOf(
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
            "build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
        ).map { java.io.File(it) }.firstOrNull { it.isFile }
        // Only meaningful once a build has produced one; the assertions below carry the intent.
        val text = merged?.readText() ?: return
        for (unwanted in listOf(
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        )) {
            assertTrue("$unwanted reached the shipped manifest", unwanted !in text)
        }
    }

    @Test
    fun aNamedTransferSaysWhatItIsDoingRatherThanThatItConnected() {
        // A share holds the process before anything has connected, and can fail to.
        for (hold in listOf(SessionHold.TRANSFER, SessionHold.TRANSFER_WITH_AUDIO)) {
            org.junit.Assert.assertEquals(hold.name, com.scrcpy.remote.R.string.transfer_notification_device, hold.namedNotificationText())
        }
        for (hold in listOf(SessionHold.CONNECTED, SessionHold.AUDIO)) {
            org.junit.Assert.assertEquals(hold.name, com.scrcpy.remote.R.string.background_notification_device, hold.namedNotificationText())
        }
    }
}
