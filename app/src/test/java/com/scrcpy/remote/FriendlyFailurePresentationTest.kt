package com.scrcpy.remote

import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionFailureGuidance
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyFailurePresentationTest {

    @Test
    fun guidedFailureKeepsFriendlyTextSeparateFromTechnicalDetail() {
        val copy = ConnectionFailureGuidance.buildFailureCopy(
            kind = FailureKind.CONNECTION_REFUSED,
            stage = CheckupStage.REACH,
            sessionChoices = false,
            rawDetail = "failed to connect to /192.168.1.20 (port 5555): ECONNREFUSED",
            resolveGuided = { id, withEndpoint ->
                assertEquals(R.string.error_connection_refused, id)
                assertTrue(withEndpoint)
                "Turn on Wireless debugging on 192.168.1.20:5555."
            },
            fallbackMessage = "Unexpected error",
        )

        assertTrue(copy.guided)
        assertEquals("Turn on Wireless debugging on 192.168.1.20:5555.", copy.message)
        assertFalse(copy.message.contains("ECONNREFUSED"))
        assertEquals("failed to connect to /192.168.1.20 (port 5555): ECONNREFUSED", copy.details)
    }

    @Test
    fun unclassifiedFailureUsesSimpleFallbackAndPreservesDetails() {
        val copy = ConnectionFailureGuidance.buildFailureCopy(
            kind = FailureKind.OTHER,
            stage = CheckupStage.START,
            sessionChoices = false,
            rawDetail = "IllegalStateException: encoder died",
            resolveGuided = { _, _ -> error("Should not resolve guidance for OTHER") },
            fallbackMessage = "Could not finish the connection.",
        )

        assertFalse(copy.guided)
        assertEquals("Could not finish the connection.", copy.message)
        assertEquals("IllegalStateException: encoder died", copy.details)
    }

    @Test
    fun blankTechnicalDetailBecomesNull() {
        val copy = ConnectionFailureGuidance.buildFailureCopy(
            kind = FailureKind.PAIRING_REQUIRED,
            stage = CheckupStage.AUTHORIZE,
            sessionChoices = false,
            rawDetail = "   ",
            resolveGuided = { _, _ -> "Pair this phone first." },
            fallbackMessage = "Unexpected error",
        )

        assertNull(copy.details)
        assertTrue(copy.guided)
    }
}
