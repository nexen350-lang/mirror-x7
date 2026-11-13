package com.scrcpy.remote.session

import com.scrcpy.remote.scrcpy.ControlStalledException
import com.scrcpy.remote.scrcpy.MediaInputException
import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbTransportException
import java.io.EOFException
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.security.GeneralSecurityException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

/**
 * Pure, per-request retry accounting. A successful connection does not reset the allowance:
 * only a new explicit connection request starts with a fresh [State].
 */
object ConnectionRecoveryPolicy {
    /**
     * Enough to ride out a Wi-Fi roam or a router restart (about a minute in all) without leaving a
     * user staring at "Reconnecting" for a device that is really gone. A returning network skips the
     * wait for the attempt in progress; see MirrorViewModel's network callback.
     */
    const val MAX_RETRIES = 6
    /** Doubling, capped: 1, 2, 4, 8, 15 and 30 seconds, about a minute in all. */
    private val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)

    /**
     * How long a session must stay connected before its retry allowance is refilled. Short enough
     * that drops hours apart each get a full budget, long enough that connect-then-instantly-drop
     * loops keep backing off.
     */
    const val STABLE_CONNECTION_MILLIS = 20_000L

    /** The state to continue with after a session has been connected for [connectedMillis]. */
    fun afterConnectedFor(state: State, connectedMillis: Long): State =
        if (connectedMillis >= STABLE_CONNECTION_MILLIS) State() else state

    enum class FailureKind(val retryable: Boolean = false) {
        TRANSIENT_TRANSPORT(true), CONNECTION_REFUSED(true), NOT_RESPONDING(true),
        AUTHENTICATION, PAIRING_REQUIRED, PROTOCOL,
        CODEC_OR_INPUT, INVALID_INPUT, CANCELLED, OTHER,
    }

    data class State(val retriesUsed: Int = 0, val stopped: Boolean = false) {
        init { require(retriesUsed in 0..MAX_RETRIES) }
    }

    enum class StopReason { DISABLED, NON_RETRYABLE, EXHAUSTED, ALREADY_STOPPED }

    sealed interface Decision {
        val state: State
        val failure: FailureKind

        data class Retry(
            val attempt: Int,
            val delayMillis: Long,
            override val state: State,
            override val failure: FailureKind,
        ) : Decision

        data class Stop(
            val reason: StopReason,
            override val state: State,
            override val failure: FailureKind,
        ) : Decision
    }

    fun backoffMillis(attempt: Int): Long {
        require(attempt in 1..MAX_RETRIES)
        return BACKOFF_MILLIS[attempt - 1]
    }

    fun onFailure(state: State, error: Throwable, enabled: Boolean): Decision =
        onFailure(state, classify(error), enabled)

    /** The explicit kind overload also permits callers to classify a known startup deadline. */
    fun onFailure(state: State, failure: FailureKind, enabled: Boolean): Decision {
        val stop = when {
            state.stopped -> StopReason.ALREADY_STOPPED
            !enabled -> StopReason.DISABLED
            !failure.retryable -> StopReason.NON_RETRYABLE
            state.retriesUsed == MAX_RETRIES -> StopReason.EXHAUSTED
            else -> null
        }
        if (stop != null) return Decision.Stop(stop, state.copy(stopped = true), failure)
        val next = state.copy(retriesUsed = state.retriesUsed + 1)
        return Decision.Retry(next.retriesUsed, backoffMillis(next.retriesUsed), next, failure)
    }

    fun classify(error: Throwable): FailureKind {
        val causes = causeChain(error) ?: return FailureKind.OTHER
        // A retryable outer transport error must never hide a permanent inner cause.
        if (causes.any { it is CancellationException || it is InterruptedException ||
                it is ClosedByInterruptException ||
                (it is InterruptedIOException && it !is SocketTimeoutException) }) {
            return FailureKind.CANCELLED
        }
        if (causes.any { it is AdbPairingRequiredException }) return FailureKind.PAIRING_REQUIRED
        // Checked before the transport kinds: the device refusing an option closes the same
        // sockets a lost network does, and reading that as transient retried the rejected
        // setting until the attempts ran out.
        if (causes.any { it is ScrcpyServerRefusedException }) return FailureKind.INVALID_INPUT
        if (causes.any { it is AdbAuthenticationFailedException || it is SSLException ||
                it is GeneralSecurityException || (it is SecurityException && isAdbOrigin(it)) }) {
            return FailureKind.AUTHENTICATION
        }
        if (causes.any { it is MediaProtocolException || it is ProtocolException }) {
            return FailureKind.PROTOCOL
        }
        if (causes.any { it is MediaInputException || isCodecFailure(it) }) {
            return FailureKind.CODEC_OR_INPUT
        }
        if (causes.any { it is IllegalArgumentException }) return FailureKind.INVALID_INPUT
        if (causes.any { it is ControlStalledException || it is SocketTimeoutException ||
                (it is SocketException && TIMEOUT_ERRNO.containsMatchIn(it.message.orEmpty())) }) {
            return FailureKind.NOT_RESPONDING
        }
        if (causes.any { it is SocketException && REFUSED.containsMatchIn(it.message.orEmpty()) }) {
            return FailureKind.CONNECTION_REFUSED
        }
        if (causes.any { it is AdbTransportException || it is ConnectException ||
                it is NoRouteToHostException || it is EOFException ||
                isAdbStreamClosed(it) ||
                (it is SocketException && isTransientSocketFailure(it)) }) {
            return FailureKind.TRANSIENT_TRANSPORT
        }
        // Generic IOException includes deployment, permissions, malformed ADB replies and auth.
        return FailureKind.OTHER
    }

    /** Only a SecurityException raised inside the ADB layer is an authentication failure. */
    private fun isAdbOrigin(error: Throwable): Boolean =
        error.stackTrace.firstOrNull()?.className?.let {
            it.startsWith("io.github.muntashirakon.adb.") || it.startsWith("com.scrcpy.remote.adb.")
        } == true

    private fun causeChain(error: Throwable): List<Throwable>? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val result = mutableListOf<Throwable>()
        var next: Throwable? = error
        while (next != null && result.size < 32 && seen.add(next)) {
            result += next
            next = next.cause
        }
        return if (next == null) result else null
    }

    private fun isCodecFailure(error: Throwable): Boolean =
        generateSequence(error.javaClass as Class<*>?) { it.superclass }
            .any { it.name == "android.media.MediaCodec\$CodecException" ||
                it.name == "android.media.MediaCodec\$CryptoException" }

    private fun isTransientSocketFailure(error: SocketException): Boolean {
        val message = error.message?.trim()?.lowercase(Locale.ROOT) ?: return false
        return message in setOf(
            "connection reset", "connection reset by peer", "broken pipe",
            "connection aborted", "software caused connection abort",
            "socket closed", "socket is closed", "network is unreachable",
        ) || TRANSIENT_ERRNO.containsMatchIn(message)
    }

    // libadb 3.1.1 signals stream EOF with this exact IOException rather than EOFException.
    // Keep this narrow: other IO failures (files, deployment, permissions) remain non-retryable.
    private fun isAdbStreamClosed(error: Throwable): Boolean = com.scrcpy.remote.adb.isAdbStreamEof(error)

    private val TRANSIENT_ERRNO = Regex("\\b(econnreset|econnaborted|epipe|enetdown|enetunreach|etimedout)\\b")
    private val TIMEOUT_ERRNO = Regex("\\betimedout\\b|\\btimed? out\\b", RegexOption.IGNORE_CASE)
    private val REFUSED = Regex("\\beconnrefused\\b|\\bconnection refused\\b|^refused$", RegexOption.IGNORE_CASE)
}
