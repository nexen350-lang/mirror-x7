package com.scrcpy.remote.session

import androidx.annotation.StringRes
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind

/**
 * Chooses the guidance shown when a session fails.
 *
 * The retry policy already classifies every failure, so the explanation is derived from that same
 * classification instead of a second, parallel set of `is` checks. Two consequences matter: what
 * the user is told can never disagree with whether the app is actually retrying, and the common
 * causes stop surfacing as raw Java exception text such as
 * `failed to connect to /192.168.1.44 (port 5555) after 10000ms`.
 *
 * Kept free of Android framework types so the mapping itself is unit-testable.
 */
object ConnectionFailureGuidance {

    /**
     * Guidance for [kind] where the attempt stopped at [stage], if the checkup knows.
     *
     * A transport failure once the device's port has answered is a connection that was lost, not
     * an address that could not be reached: that wording sent people checking an address that was
     * right. So is a running session the device stopped answering, such as one whose input it
     * stopped taking: no connection step ran out of time there. Every other kind names its own
     * cause wherever it happened.
     */
    @StringRes
    fun guidanceFor(kind: FailureKind, stage: CheckupStage?): Int? = when {
        kind == FailureKind.TRANSIENT_TRANSPORT && stage != null && stage > CheckupStage.REACH ->
            R.string.error_connection_lost
        kind == FailureKind.NOT_RESPONDING && stage == CheckupStage.SESSION -> R.string.error_connection_lost
        else -> guidanceFor(kind)
    }

    /**
     * As [guidanceFor], for an attempt that may have carried choices made for this session only:
     * options from the link that opened it, or what was chosen in its controls.
     */
    @StringRes
    fun guidanceFor(kind: FailureKind, stage: CheckupStage?, sessionChoices: Boolean): Int? =
        // Saying the saved settings were wrong sent people to review settings that were fine, and
        // Reconnect repeats the session's choices; only connecting afresh drops them.
        if (kind == FailureKind.INVALID_INPUT && sessionChoices) R.string.error_invalid_session_choice
        else guidanceFor(kind, stage)

    /**
     * Guidance for [kind], or null when the classification carries no more information than the
     * underlying error already does and the technical text is the most honest thing to show.
     */
    @StringRes
    fun guidanceFor(kind: FailureKind): Int? = when (kind) {
        FailureKind.PAIRING_REQUIRED -> R.string.error_pairing_required
        FailureKind.AUTHENTICATION -> R.string.error_auth_failed
        FailureKind.TRANSIENT_TRANSPORT -> R.string.error_unreachable
        FailureKind.CONNECTION_REFUSED -> R.string.error_connection_refused
        FailureKind.NOT_RESPONDING -> R.string.error_not_responding
        FailureKind.PROTOCOL -> R.string.error_protocol
        FailureKind.CODEC_OR_INPUT -> R.string.error_codec
        FailureKind.INVALID_INPUT -> R.string.error_invalid_settings
        FailureKind.CANCELLED, FailureKind.OTHER -> null
    }

    /** True when [guidanceFor] needs the attempted endpoint as a format argument. */
    fun needsEndpoint(kind: FailureKind): Boolean = kind.retryable

    /**
     * The technical line shown under the guidance: the first message anywhere in the cause chain.
     *
     * libadb wraps a failed TCP connect in an exception with no message of its own, which reduced
     * the most common failure of all to "Details: IOException". Bounded, so a cause cycle cannot
     * loop.
     */
    fun technicalDetail(error: Throwable): String =
        generateSequence(error) { it.cause }.take(MAX_CAUSES)
            .mapNotNull { cause -> cause.message?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull() ?: error.javaClass.simpleName

    data class FailureCopy(
        val message: String,
        val details: String?,
        val guided: Boolean,
    )

    /**
     * Separates friendly actionable guidance from raw technical exception details so the main
     * error screen stays simple while technical details remain one tap away under "Details".
     */
    fun buildFailureCopy(
        kind: FailureKind,
        stage: CheckupStage?,
        sessionChoices: Boolean,
        rawDetail: String,
        resolveGuided: (id: Int, withEndpoint: Boolean) -> String,
        fallbackMessage: String,
    ): FailureCopy {
        val guidanceId = guidanceFor(kind, stage, sessionChoices)
        val detail = rawDetail.trim().takeIf { it.isNotEmpty() }
        return if (guidanceId != null) {
            FailureCopy(
                message = resolveGuided(guidanceId, needsEndpoint(kind)),
                details = detail,
                guided = true,
            )
        } else {
            FailureCopy(
                message = fallbackMessage,
                details = detail,
                guided = false,
            )
        }
    }

    private const val MAX_CAUSES = 32
}
