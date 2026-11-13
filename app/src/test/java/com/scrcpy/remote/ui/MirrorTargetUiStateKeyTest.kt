package com.scrcpy.remote.ui

import com.scrcpy.remote.data.canonicalHost
import java.io.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTargetUiStateKeyTest {
    @Test fun uiIdentityMatchesTheExistingSessionComparison() {
        val base = MirrorTarget("phone.local", 5555, null, null)
        val targets = listOf(
            base, base.copy(host = "PHONE.local."), base.copy(linkOptions = ""),
            base.copy(port = 5556), base.copy(host = "other.local"),
            base.copy(deviceId = "profile-a"), base.copy(deviceId = "profile-b"),
            base.copy(linkOptions = "fps=30"), base.copy(linkOptions = "fps=60"),
            base.copy(host = "[2001:db8::1]"), base.copy(host = "2001:DB8::1"),
        )
        for (first in targets) for (second in targets) {
            assertEquals("$first / $second", first.sameSessionAs(second),
                first.uiStateKey() == second.uiStateKey())
        }
    }

    @Test fun keyContainsOnlyStableBundleSaveableTargetParts() {
        val key = MirrorTarget("PHONE.local.", 5555, null, null).uiStateKey()
        assertEquals(listOf(canonicalHost("phone.local"), "5555", null, ""), key)
        assertTrue(key is Serializable)
    }

    @Test fun profileIdentityIsNotCollapsedIntoMissingProfileOrLinkOptions() {
        val base = MirrorTarget("phone.local", 5555, null, "")
        assertFalse(base.uiStateKey() == base.copy(deviceId = "").uiStateKey())
        assertFalse(base.copy(deviceId = "fps=30").uiStateKey() ==
            base.copy(linkOptions = "fps=30").uiStateKey())
    }
}
