package com.scrcpy.remote

import com.scrcpy.remote.data.canonicalHost
import com.scrcpy.remote.data.sameEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a link, a shortcut or a relaunch names a device that is already saved or already
 * connected. The device list, the mirror screen and port discovery must all agree, or a link to a
 * saved device silently connects without its profile, calibration or port discovery.
 */
class EndpointIdentityTest {
    @Test fun equivalentIpv6SpellingsNameOneDevice() {
        assertTrue(sameEndpoint("2001:db8::1", 5555, "2001:0db8:0:0:0:0:0:1", 5555))
        assertTrue(sameEndpoint("[2001:db8::1]", 5555, "2001:DB8::1", 5555))
        assertTrue("An IPv4-mapped address is that IPv4 device",
            sameEndpoint("::ffff:192.0.2.1", 5555, "192.0.2.1", 5555))
    }

    @Test fun interfaceZonesKeepTheirCase() {
        // Interface names are case-sensitive, so these can be different links on one host.
        assertFalse(sameEndpoint("fe80::1%WLAN0", 5555, "fe80::1%wlan0", 5555))
        assertTrue(sameEndpoint("[fe80::1%25wlan0]", 5555, "fe80::1%wlan0", 5555))
    }

    @Test fun theEarlierSpellingRulesStillHold() {
        assertTrue(sameEndpoint("PHONE.local.", 5555, "phone.local", 5555))
        assertTrue(sameEndpoint("192.0.2.10", 5555, "192.0.2.10", 5555))
        assertFalse(sameEndpoint("192.0.2.10", 5555, "192.0.2.10", 5556))
        assertFalse(sameEndpoint("192.0.2.10", 5555, "192.0.2.11", 5555))
        // A host the strict parser does not accept still compares by its plain spelling.
        assertEquals(canonicalHost("Odd_Host"), canonicalHost("odd_host"))
        assertNotEquals(canonicalHost("phone.local"), canonicalHost("phone.lan"))
    }

    @Test fun aRejectedSpellingCannotPassForAParsedHost() {
        // Neither parser accepts these, so they fall back to their own spelling, which happens to
        // look like the identity a valid host is given. That must not make them the same device.
        assertFalse(sameEndpoint("dns:phone.local", 5555, "phone.local", 5555))
        assertFalse(sameEndpoint("ip4:192.0.2.1", 5555, "192.0.2.1", 5555))
    }
}
