package com.scrcpy.remote.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTargetTest {

    private fun target(
        host: String = "192.168.1.20",
        port: Int = 5555,
        deviceId: String? = null,
        linkOptions: String? = null,
    ) = MirrorTarget(host, port, deviceId, linkOptions)

    @Test fun therepeatOfARunningSessionIsRecognised() {
        assertTrue(target().sameSessionAs(target()))
    }

    @Test fun aDifferentDestinationIsNotTheSameSession() {
        // The reason this matters: the screen is reused, so failing to notice the difference
        // leaves the previous device mirrored under the new device's name.
        assertFalse(target(host = "192.168.1.21").sameSessionAs(target()))
        assertFalse(target(port = 5556).sameSessionAs(target()))
    }

    @Test fun theSameAddressSpelledDifferentlyIsStillTheSameSession() {
        // Restarting a working stream because a link wrote the host in capitals would be a
        // visible stall for no reason.
        assertTrue(target(host = "PHONE.local").sameSessionAs(target(host = "phone.local")))
        assertTrue(target(host = "phone.local.").sameSessionAs(target(host = "phone.local")))
        assertTrue(target(host = "[2001:db8::1]").sameSessionAs(target(host = "2001:db8::1")))
    }

    @Test fun aDifferentSavedDeviceIsADifferentSession() {
        // The saved device decides the settings and calibration the session runs with, so the
        // same address under a different profile is a different session.
        assertFalse(target(deviceId = "a").sameSessionAs(target(deviceId = "b")))
        assertFalse(target(deviceId = "a").sameSessionAs(target(deviceId = null)))
        assertTrue(target(deviceId = "a").sameSessionAs(target(deviceId = "a")))
    }

    @Test fun differentLinkOptionsAreADifferentSession() {
        // A link naming a codec or frame rate can only take effect by connecting again, so it
        // has to count as a different request rather than being quietly ignored.
        assertFalse(target(linkOptions = "fps=30").sameSessionAs(target(linkOptions = null)))
        assertFalse(target(linkOptions = "fps=30").sameSessionAs(target(linkOptions = "fps=45")))
        assertTrue(target(linkOptions = "fps=30").sameSessionAs(target(linkOptions = "fps=30")))
    }

    @Test fun noOptionsAndEmptyOptionsMeanTheSameThing() {
        assertTrue(target(linkOptions = null).sameSessionAs(target(linkOptions = "")))
    }

    @Test fun theComparisonDoesNotDependOnWhichSideItIsCalledOn() {
        val a = target(host = "PHONE.local", deviceId = "a", linkOptions = "fps=30")
        val b = target(host = "phone.local", deviceId = "a", linkOptions = "fps=30")
        assertTrue(a.sameSessionAs(b))
        assertTrue(b.sameSessionAs(a))
        val c = target(host = "other.local")
        assertFalse(a.sameSessionAs(c))
        assertFalse(c.sameSessionAs(a))
    }
}
