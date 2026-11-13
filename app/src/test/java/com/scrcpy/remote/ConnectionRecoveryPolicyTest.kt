package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ControlStalledException
import com.scrcpy.remote.scrcpy.MediaInputException
import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException
import com.scrcpy.remote.session.ConnectionRecoveryPolicy
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.Decision
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.State
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.StopReason
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbTransportException
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

class ConnectionRecoveryPolicyTest {
    @Test fun sixRetriesBackOffFromOneToThirtySeconds() {
        var state = State()
        for ((index, expected) in listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L).withIndex()) {
            val before = state
            val decision = ConnectionRecoveryPolicy.onFailure(state, EOFException(), enabled = true)
            assertTrue(decision is Decision.Retry)
            decision as Decision.Retry
            assertEquals(index + 1, decision.attempt)
            assertEquals(expected, decision.delayMillis)
            assertEquals(index, before.retriesUsed)
            state = decision.state
        }
        val exhausted = ConnectionRecoveryPolicy.onFailure(state, EOFException(), true) as Decision.Stop
        assertEquals(StopReason.EXHAUSTED, exhausted.reason)
        assertEquals(6, exhausted.state.retriesUsed)
        assertTrue(exhausted.state.stopped)
        assertThrows(IllegalArgumentException::class.java) { ConnectionRecoveryPolicy.backoffMillis(7) }
        assertThrows(IllegalArgumentException::class.java) { ConnectionRecoveryPolicy.backoffMillis(0) }
    }

    @Test fun disabledOrTerminalRequestsCannotBeRevivedByAnotherCallback() {
        val disabled = ConnectionRecoveryPolicy.onFailure(State(), ConnectException(), false) as Decision.Stop
        assertEquals(StopReason.DISABLED, disabled.reason)
        assertEquals(0, disabled.state.retriesUsed)
        val terminal = ConnectionRecoveryPolicy.onFailure(State(), MediaProtocolException("bad"), true)
        val late = ConnectionRecoveryPolicy.onFailure(terminal.state, EOFException(), true) as Decision.Stop
        assertEquals(StopReason.ALREADY_STOPPED, late.reason)
        assertTrue(ConnectionRecoveryPolicy.onFailure(State(), EOFException(), true) is Decision.Retry)
    }

    @Test fun onlyRecognizedTransientTransportFailuresRetry() {
        for (error in listOf(ConnectException(), NoRouteToHostException(), AdbTransportException(),
            EOFException(), SocketException("Connection reset"),
            SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)"),
            SocketException("Broken pipe"))) {
            assertEquals(error.toString(), FailureKind.TRANSIENT_TRANSPORT,
                ConnectionRecoveryPolicy.classify(IOException("wrapper", error)))
        }
        for (error in listOf(IOException("could not connect; accept the authorization prompt"),
            FileNotFoundException(), UnknownHostException("bad hostname"),
            SocketException("Permission denied"), SocketException("Protocol not supported"),
            SocketException("unknown failure"), IllegalStateException("programming failure"))) {
            assertEquals(error.toString(), FailureKind.OTHER, ConnectionRecoveryPolicy.classify(error))
        }
    }

    @Test fun refusalAndTimeoutAreDistinctButKeepTheSameBoundedRetryPolicy() {
        val cases = listOf(
            ConnectException("connect failed: ECONNREFUSED (Connection refused)") to FailureKind.CONNECTION_REFUSED,
            SocketException("Connection refused") to FailureKind.CONNECTION_REFUSED,
            SocketTimeoutException("Read timed out") to FailureKind.NOT_RESPONDING,
            ConnectException("connect failed: ETIMEDOUT (Connection timed out)") to FailureKind.NOT_RESPONDING,
        )
        for ((error, expected) in cases) {
            val wrapped = IOException("Connection failed", error)
            assertEquals(expected, ConnectionRecoveryPolicy.classify(wrapped))
            assertTrue(ConnectionRecoveryPolicy.onFailure(State(), wrapped, true) is Decision.Retry)
            assertEquals(StopReason.EXHAUSTED, (ConnectionRecoveryPolicy.onFailure(
                State(ConnectionRecoveryPolicy.MAX_RETRIES), wrapped, true) as Decision.Stop).reason)
        }
        assertEquals(FailureKind.TRANSIENT_TRANSPORT, ConnectionRecoveryPolicy.classify(
            ConnectException("Stream open actively rejected by remote peer.")))
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(
            IOException("File write refused")))
    }

    @Test fun aDeadlineForTheWholeStepIsNotMislabelledAsItsLastRefusedSocketAttempt() {
        val timeout = SocketTimeoutException("Timed out opening video").apply {
            initCause(ConnectException("Connection refused"))
        }
        assertEquals(FailureKind.NOT_RESPONDING, ConnectionRecoveryPolicy.classify(timeout))
        assertEquals(FailureKind.AUTHENTICATION, ConnectionRecoveryPolicy.classify(
            SocketTimeoutException().apply { initCause(SSLHandshakeException("certificate")) }))
        assertEquals(FailureKind.TRANSIENT_TRANSPORT, ConnectionRecoveryPolicy.classify(
            IOException("Wrapped channel loss", AdbTransportException())))
    }

    @Test fun authPairingProtocolCodecAndInputErrorsAreNotRetried() {
        val cases = listOf(
            AdbAuthenticationFailedException() to FailureKind.AUTHENTICATION,
            AdbPairingRequiredException("pairing required") to FailureKind.PAIRING_REQUIRED,
            SSLHandshakeException("certificate") to FailureKind.AUTHENTICATION,
            MediaProtocolException("oversized") to FailureKind.PROTOCOL,
            MediaInputException("decoder stalled") to FailureKind.CODEC_OR_INPUT,
            IllegalArgumentException("port") to FailureKind.INVALID_INPUT,
            CancellationException("stopped") to FailureKind.CANCELLED,
            InterruptedIOException("interrupted") to FailureKind.CANCELLED,
            InterruptedException("interrupted") to FailureKind.CANCELLED,
        )
        for ((error, kind) in cases) {
            val wrapper = ConnectException("connection refused").apply { initCause(error) }
            assertEquals(kind, ConnectionRecoveryPolicy.classify(wrapper))
            assertTrue(ConnectionRecoveryPolicy.onFailure(State(), wrapper, true) is Decision.Stop)
        }
    }

    @Test fun realLibadbEofRetriesWithoutTreatingArbitraryClosedFilesAsTransport() {
        assertEquals(FailureKind.TRANSIENT_TRANSPORT, ConnectionRecoveryPolicy.classify(IOException("Stream closed.")))
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(IOException("Stream closed")))
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(FileNotFoundException("Stream closed.")))
        assertEquals(FailureKind.AUTHENTICATION, ConnectionRecoveryPolicy.classify(
            IOException("Stream closed.", AdbAuthenticationFailedException())))
    }

    @Test fun aSettingTheDeviceRejectedIsNotRetriedAsIfTheNetworkHadDropped() {
        // The server validates options on the device and exits when one is wrong, which reaches
        // the client only as its sockets closing — the same thing a lost network looks like. Read
        // that way, the app told the user to check their Wi-Fi and then reconnected three times
        // with the very encoder the device had just refused, so an encoder picked by mistake left
        // the session unable to start with no hint as to why.
        val refused = ScrcpyServerRefusedException(
            "Stream closed.\n\nServer output:\nERROR: Encoder 'OMX.bogus' for h264 not found",
            IOException("Stream closed."),
        )
        assertEquals(FailureKind.INVALID_INPUT, ConnectionRecoveryPolicy.classify(refused))
        assertTrue(ConnectionRecoveryPolicy.onFailure(State(), refused, true) is Decision.Stop)

        // The same transport failure with nothing said about it still retries: a device that went
        // away really may come back, and refusing to retry those would be the opposite mistake.
        val dropped = IOException("Stream closed.")
        assertEquals(FailureKind.TRANSIENT_TRANSPORT, ConnectionRecoveryPolicy.classify(dropped))
        assertTrue(ConnectionRecoveryPolicy.onFailure(State(), dropped, true) is Decision.Retry)
    }

    @Test fun cyclicCausesDoNotHangClassification() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(first))
    }

    @Test fun excessivelyDeepCausesFailClosedRatherThanHidingPermanentErrors() {
        var error: Throwable = MediaProtocolException("invalid stream")
        repeat(40) { error = IOException("wrapper", error) }
        val outer = ConnectException("connection refused").apply { initCause(error) }
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(outer))
        assertTrue(ConnectionRecoveryPolicy.onFailure(State(), outer, true) is Decision.Stop)
    }
    @Test
    fun aStalledControlQueueIsRetriedAsAConnectionThatStoppedAnswering() {
        val stalled = ControlStalledException("Control backlog exceeded 1024 pending messages")
        assertEquals(ConnectionRecoveryPolicy.FailureKind.NOT_RESPONDING, ConnectionRecoveryPolicy.classify(stalled))
        assertEquals(ConnectionRecoveryPolicy.FailureKind.NOT_RESPONDING,
            ConnectionRecoveryPolicy.classify(java.io.IOException("Control failed", stalled)))
        assertTrue(ConnectionRecoveryPolicy.onFailure(ConnectionRecoveryPolicy.State(), stalled, enabled = true)
            is ConnectionRecoveryPolicy.Decision.Retry)
    }

    @Test fun audioSecurityExceptionIsNotAuthentication() {
        val wrapped = IOException("audio", SecurityException("RECORD_AUDIO"))
        assertEquals(FailureKind.OTHER, ConnectionRecoveryPolicy.classify(wrapped))
    }

    @Test fun budgetRefillsOnlyAfterStableConnection() {
        val used = State(retriesUsed = 5)
        assertEquals(used, ConnectionRecoveryPolicy.afterConnectedFor(used, 0))
        assertEquals(used, ConnectionRecoveryPolicy.afterConnectedFor(
            used, ConnectionRecoveryPolicy.STABLE_CONNECTION_MILLIS - 1))
        assertEquals(State(), ConnectionRecoveryPolicy.afterConnectedFor(
            used, ConnectionRecoveryPolicy.STABLE_CONNECTION_MILLIS))
    }
}
