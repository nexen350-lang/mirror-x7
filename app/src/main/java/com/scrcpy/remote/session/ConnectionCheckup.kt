package com.scrcpy.remote.session

import androidx.annotation.StringRes
import com.scrcpy.remote.R
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind

/**
 * The steps every connection goes through, in order.
 *
 * A step begins only once the attempt has been observed to finish the one before it, so the step
 * that was running when the attempt failed is where it failed.
 */
enum class CheckupStage(
    /**
     * False when this step's start cannot be observed apart from the step before it. That step
     * then passes untimed, and this one is timed from when that step began.
     */
    val startObserved: Boolean = true,
) {
    /** TCP: the device's port accepted the connection. */
    REACH,

    /**
     * The ADB handshake, including authorization and, for wireless debugging, TLS. It runs in
     * the same library call as the TCP connect, which reveals afterwards whether TCP connected
     * but not when, so the connect time is reported here rather than guessed.
     */
    AUTHORIZE(startObserved = false),

    /** Checking, and if necessary pushing, the scrcpy server. */
    DEPLOY,

    /** Starting the server and opening its video, audio and control streams. */
    START,

    /** Decoding the first video. */
    MEDIA,

    /** The session after video started. */
    SESSION,
}

enum class StageStatus { NOT_REACHED, RUNNING, PASSED, FAILED }

data class CheckupStep(
    val stage: CheckupStage,
    val status: StageStatus,
    /** How long the step ran, once it has passed or failed. */
    val elapsedMillis: Long? = null,
)

/**
 * What the last connection attempt got through.
 *
 * Built only from events that attempt reported. Nothing here connects, probes or retries: a
 * checkup that went looking would be a second retry loop, and could disagree with what the
 * attempt itself experienced.
 */
data class ConnectionCheckup(
    val endpoint: String,
    val steps: List<CheckupStep>,
    val failure: FailureKind? = null,
    /** The attempt carried choices made for this session only: link options, or ones made in its controls. */
    val sessionChoices: Boolean = false,
) {
    val failedStage: CheckupStage? get() = steps.firstOrNull { it.status == StageStatus.FAILED }?.stage

    /** The one thing to try next, or null when nothing failed or the attempt was cancelled. */
    @get:StringRes
    val nextAction: Int?
        get() {
            val stage = failedStage ?: return null
            return ConnectionCheckupGuidance.nextAction(stage, failure ?: return null, sessionChoices)
        }
}

/**
 * Records one attempt's progress.
 *
 * The startup worker reports the early steps and the main thread the later ones, so every change
 * is synchronized and [snapshot] hands out an immutable copy. A failure is recorded synchronously
 * by whoever observes it, which is what lets it be attributed to the step that was running rather
 * than to whichever update reached the screen first.
 */
class ConnectionCheckupTracker(
    private val endpoint: String,
    private val sessionChoices: Boolean = false,
    private val clock: () -> Long,
) {
    private val status = Array(CheckupStage.entries.size) { StageStatus.NOT_REACHED }
    private val started = LongArray(CheckupStage.entries.size)
    private val ended = LongArray(CheckupStage.entries.size)
    private var failure: FailureKind? = null

    init {
        advance(CheckupStage.REACH)
    }

    /** [stage] has begun, so every step before it has been observed to succeed. */
    @Synchronized
    fun advance(stage: CheckupStage) {
        if (failure != null) return
        val now = clock()
        var inheritedStart: Long? = null
        for (earlier in CheckupStage.entries.take(stage.ordinal)) {
            when (status[earlier.ordinal]) {
                StageStatus.RUNNING -> {
                    status[earlier.ordinal] = StageStatus.PASSED
                    if (!stage.startObserved && earlier.ordinal == stage.ordinal - 1) {
                        inheritedStart = started[earlier.ordinal]
                        ended[earlier.ordinal] = UNTIMED
                    } else {
                        ended[earlier.ordinal] = now
                    }
                }
                StageStatus.NOT_REACHED -> {
                    status[earlier.ordinal] = StageStatus.PASSED
                    started[earlier.ordinal] = now
                    ended[earlier.ordinal] = now
                }
                StageStatus.PASSED, StageStatus.FAILED -> Unit
            }
        }
        if (status[stage.ordinal] == StageStatus.NOT_REACHED) {
            status[stage.ordinal] = StageStatus.RUNNING
            started[stage.ordinal] = inheritedStart ?: now
        }
    }

    /** The attempt ended with [kind]; the step that was running is the one that failed. */
    @Synchronized
    fun fail(kind: FailureKind): ConnectionCheckup {
        if (failure == null) {
            failure = kind
            val running = CheckupStage.entries.lastOrNull { status[it.ordinal] == StageStatus.RUNNING }
            if (running != null) {
                status[running.ordinal] = StageStatus.FAILED
                ended[running.ordinal] = clock()
            }
        }
        return snapshot()
    }

    @Synchronized
    fun snapshot(): ConnectionCheckup = ConnectionCheckup(
        endpoint = endpoint,
        steps = CheckupStage.entries.map { stage ->
            val state = status[stage.ordinal]
            val elapsed = when (state) {
                StageStatus.PASSED, StageStatus.FAILED ->
                    ended[stage.ordinal].takeIf { it != UNTIMED }?.let { it - started[stage.ordinal] }
                StageStatus.RUNNING, StageStatus.NOT_REACHED -> null
            }
            CheckupStep(stage, state, elapsed)
        },
        failure = failure,
        sessionChoices = sessionChoices,
    )

    private companion object {
        /** Marks a step whose end was never observed on its own; see [CheckupStage.startObserved]. */
        const val UNTIMED = Long.MIN_VALUE
    }
}

/**
 * One next action for a failed step.
 *
 * Derived from the same classification the retry policy uses, refined by where the attempt
 * stopped: "connection refused" while reaching the port and a refused setting while starting the
 * server need different advice, even though both end the attempt.
 */
object ConnectionCheckupGuidance {
    /**
     * As below, for an attempt that carried choices made for this session only. A setting the
     * device refused is then one Reconnect would send again: connecting afresh drops it.
     */
    @StringRes
    fun nextAction(stage: CheckupStage, kind: FailureKind, sessionChoices: Boolean): Int? =
        if (stage == CheckupStage.START && kind == FailureKind.INVALID_INPUT && sessionChoices) {
            R.string.checkup_action_session_setting
        } else {
            nextAction(stage, kind)
        }

    @StringRes
    fun nextAction(stage: CheckupStage, kind: FailureKind): Int? {
        if (kind == FailureKind.CANCELLED) return null
        val transport = kind == FailureKind.TRANSIENT_TRANSPORT || kind == FailureKind.NOT_RESPONDING ||
            kind == FailureKind.CONNECTION_REFUSED
        return when (stage) {
            CheckupStage.REACH ->
                if (kind == FailureKind.CONNECTION_REFUSED) R.string.checkup_action_reach_refused
                else R.string.checkup_action_reach
            CheckupStage.AUTHORIZE -> when (kind) {
                FailureKind.PAIRING_REQUIRED -> R.string.checkup_action_pair
                // The port accepted TCP and then closed without speaking ADB.
                FailureKind.TRANSIENT_TRANSPORT, FailureKind.PROTOCOL -> R.string.checkup_action_adb_port
                else -> R.string.checkup_action_authorize
            }
            CheckupStage.DEPLOY ->
                if (transport) R.string.checkup_action_reconnect else R.string.checkup_action_deploy
            CheckupStage.START -> when {
                kind == FailureKind.INVALID_INPUT -> R.string.checkup_action_setting
                transport -> R.string.checkup_action_reconnect
                else -> R.string.checkup_action_start
            }
            CheckupStage.MEDIA ->
                if (transport) R.string.checkup_action_network else R.string.checkup_action_codec
            CheckupStage.SESSION -> when {
                kind == FailureKind.CODEC_OR_INPUT -> R.string.checkup_action_codec
                transport -> R.string.checkup_action_network
                else -> R.string.checkup_action_reconnect
            }
        }
    }
}
