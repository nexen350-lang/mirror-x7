package com.scrcpy.remote

import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionFailureGuidance
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.scrcpy.MediaProtocolException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ConnectionFailureGuidanceTest {

    @Test fun everyClassifiedFailureIsDecidedExplicitly() {
        // A new FailureKind must not silently inherit "show the raw exception": this loops over
        // the enum so adding one without deciding its message fails here rather than in the UI.
        for (kind in FailureKind.entries) {
            val guidance = ConnectionFailureGuidance.guidanceFor(kind)
            when (kind) {
                FailureKind.CANCELLED, FailureKind.OTHER ->
                    assertNull("$kind carries no extra information", guidance)
                else -> assertNotNull("$kind must explain itself to the user", guidance)
            }
        }
    }

    @Test fun actionableKindsUseDistinctMessages() {
        val ids = FailureKind.entries.mapNotNull { ConnectionFailureGuidance.guidanceFor(it) }
        assertEquals("each actionable kind needs its own wording", ids.size, ids.toSet().size)
    }

    @Test fun aConnectionLostAfterTheDeviceAnsweredIsNotCalledUnreachable() {
        // The checkup knows how far the attempt got. Once the port has answered, "could not
        // reach" sends the user checking an address that was right.
        val lost = FailureKind.TRANSIENT_TRANSPORT
        assertEquals(R.string.error_unreachable, ConnectionFailureGuidance.guidanceFor(lost))
        assertEquals(R.string.error_unreachable, ConnectionFailureGuidance.guidanceFor(lost, CheckupStage.REACH))
        for (stage in CheckupStage.entries.filter { it > CheckupStage.REACH }) {
            assertEquals("$stage", R.string.error_connection_lost, ConnectionFailureGuidance.guidanceFor(lost, stage))
        }
        // Every other kind already names its own cause, wherever the attempt stopped, but for a
        // running session that stopped answering (aSessionThatStoppedAnsweringIsWordedAsALostConnection).
        for (kind in FailureKind.entries.filter { it != lost }) {
            for (stage in CheckupStage.entries) {
                if (kind == FailureKind.NOT_RESPONDING && stage == CheckupStage.SESSION) continue
                assertEquals("$kind at $stage", ConnectionFailureGuidance.guidanceFor(kind),
                    ConnectionFailureGuidance.guidanceFor(kind, stage))
            }
        }
    }

    @Test fun eachTransportMessageFormatsTheAttemptedEndpoint() {
        // The endpoint argument exists so the user can see which address actually failed; any
        // other message formatted with it would throw at runtime instead of helping.
        for (kind in FailureKind.entries) {
            assertEquals(kind.toString(), kind.retryable,
                ConnectionFailureGuidance.needsEndpoint(kind))
        }
    }

    @Test fun theCommonUnreachableCausesAllResolveToGuidanceRatherThanRawText() {
        // These are the everyday failures: device asleep, wrong address, wireless debugging off
        // or its port rotated. Before this mapping they reached the screen as Java exception text.
        for ((error, expected) in listOf(
            ConnectException("Connection refused") to FailureKind.CONNECTION_REFUSED,
            SocketTimeoutException("timeout") to FailureKind.NOT_RESPONDING,
            java.io.IOException("Stream closed.") to FailureKind.TRANSIENT_TRANSPORT,
        )) {
            val kind = com.scrcpy.remote.session.ConnectionRecoveryPolicy.classify(error)
            assertEquals(error.toString(), expected, kind)
            assertNotNull(error.toString(), ConnectionFailureGuidance.guidanceFor(kind))
        }
    }

    @Test fun guidanceAgreesWithWhetherTheAppKeepsRetrying() {
        // The explanation and the retry behaviour come from one classification, so a message that
        // tells the user to wait can only appear when the app is in fact still retrying.
        val retryable = com.scrcpy.remote.session.ConnectionRecoveryPolicy.classify(
            ConnectException("refused"))
        val permanent = com.scrcpy.remote.session.ConnectionRecoveryPolicy.classify(
            AdbPairingRequiredException("pair first"))
        val protocol = com.scrcpy.remote.session.ConnectionRecoveryPolicy.classify(
            MediaProtocolException("bad header"))

        assertTrue(retryable.retryable)
        assertFalse(permanent.retryable)
        assertFalse(protocol.retryable)
        assertNotNull(ConnectionFailureGuidance.guidanceFor(permanent))
        assertNotNull(ConnectionFailureGuidance.guidanceFor(protocol))
    }

    @Test fun theTechnicalLineIsTheFirstMessageInTheCauseChain() {
        // libadb reports a refused TCP connect as an IOException with no message of its own.
        val refused = ConnectException(
            "failed to connect to /192.0.2.20 (port 5555): connect failed: ECONNREFUSED (Connection refused)")
        val wrapped = java.io.IOException().apply { initCause(refused) }
        assertEquals(refused.message, ConnectionFailureGuidance.technicalDetail(wrapped))
        assertEquals("Connection failed",
            ConnectionFailureGuidance.technicalDetail(java.io.IOException("Connection failed", refused)))
        assertEquals("IOException", ConnectionFailureGuidance.technicalDetail(java.io.IOException(" ")))

        val first = java.io.IOException()
        val second = java.io.IOException()
        first.initCause(second)
        second.initCause(first)
        assertEquals("A cause cycle must still end", "IOException", ConnectionFailureGuidance.technicalDetail(first))
    }

    @Test
    fun aRefusedSessionChoiceIsNotBlamedOnTheSavedSettings() {
        val kind = com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind.INVALID_INPUT
        val start = com.scrcpy.remote.session.CheckupStage.START
        org.junit.Assert.assertEquals(com.scrcpy.remote.R.string.error_invalid_session_choice,
            com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(kind, start, sessionChoices = true))
        org.junit.Assert.assertEquals(com.scrcpy.remote.R.string.error_invalid_settings,
            com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(kind, start, sessionChoices = false))
        // Every other failure is worded as before, with or without session choices.
        for (other in com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind.entries - kind) {
            for (stage in listOf(null) + com.scrcpy.remote.session.CheckupStage.entries) {
                org.junit.Assert.assertEquals("$other at $stage",
                    com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(other, stage),
                    com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(other, stage, sessionChoices = true))
            }
        }
    }

    @Test
    fun aSessionThatStoppedAnsweringIsWordedAsALostConnection() {
        // Input the device stopped taking during a running session is not a step that timed out
        // while connecting, and checking the address would not help.
        val kind = com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind.NOT_RESPONDING
        org.junit.Assert.assertEquals(com.scrcpy.remote.R.string.error_connection_lost,
            com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(kind, com.scrcpy.remote.session.CheckupStage.SESSION))
        for (stage in com.scrcpy.remote.session.CheckupStage.entries - com.scrcpy.remote.session.CheckupStage.SESSION) {
            org.junit.Assert.assertEquals("$stage", com.scrcpy.remote.R.string.error_not_responding,
                com.scrcpy.remote.session.ConnectionFailureGuidance.guidanceFor(kind, stage))
        }
    }
}