package com.scrcpy.remote

import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionCheckupGuidance
import com.scrcpy.remote.session.ConnectionCheckupTracker
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionCheckupTest {
    private var now = 1_000L
    private fun tracker() = ConnectionCheckupTracker("192.0.2.20:5555") { now }

    @Test fun anAttemptStartsByReachingTheAddressAndNothingElse() {
        val checkup = tracker().snapshot()
        assertEquals("192.0.2.20:5555", checkup.endpoint)
        assertEquals(listOf(StageStatus.RUNNING) + List(5) { StageStatus.NOT_REACHED }, checkup.steps.map { it.status })
        assertNull(checkup.failedStage)
        assertNull(checkup.nextAction)
    }

    @Test fun eachObservedStepCompletesTheOnesBeforeItWithTheirDurations() {
        val tracker = tracker()
        now += 120
        tracker.advance(CheckupStage.AUTHORIZE)
        now += 800
        tracker.advance(CheckupStage.DEPLOY)
        now += 50
        val checkup = tracker.snapshot()
        assertEquals(listOf(StageStatus.PASSED, StageStatus.PASSED, StageStatus.RUNNING,
            StageStatus.NOT_REACHED, StageStatus.NOT_REACHED, StageStatus.NOT_REACHED), checkup.steps.map { it.status })
        assertEquals(listOf(null, 920L, null, null, null, null), checkup.steps.map { it.elapsedMillis })
    }

    @Test fun connectingIsTimedOnAuthorizationBecauseTheirBoundaryIsNotObserved() {
        // TCP and the ADB handshake run inside one library call. Afterwards it is known whether
        // TCP connected, not when, so ten seconds spent waiting on the handshake must not be
        // reported as ten seconds spent reaching the device.
        val tracker = tracker()
        now += 10_000
        tracker.advance(CheckupStage.AUTHORIZE)
        val checkup = tracker.fail(FailureKind.NOT_RESPONDING)
        assertEquals(StageStatus.PASSED, checkup.steps[CheckupStage.REACH.ordinal].status)
        assertNull(checkup.steps[CheckupStage.REACH.ordinal].elapsedMillis)
        assertEquals(10_000L, checkup.steps[CheckupStage.AUTHORIZE.ordinal].elapsedMillis)
        // An address that never answered is still timed on the step that failed.
        val unanswered = tracker().also { now += 10_000 }.fail(FailureKind.NOT_RESPONDING)
        assertEquals(10_000L, unanswered.steps[CheckupStage.REACH.ordinal].elapsedMillis)
    }

    @Test fun aFailureIsAttributedToTheStepThatWasRunning() {
        val tracker = tracker()
        tracker.advance(CheckupStage.AUTHORIZE)
        now += 10_000
        val checkup = tracker.fail(FailureKind.NOT_RESPONDING)
        assertEquals(CheckupStage.AUTHORIZE, checkup.failedStage)
        assertEquals(10_000L, checkup.steps[CheckupStage.AUTHORIZE.ordinal].elapsedMillis)
        assertEquals(StageStatus.NOT_REACHED, checkup.steps[CheckupStage.DEPLOY.ordinal].status)
        assertEquals(R.string.checkup_action_authorize, checkup.nextAction)
    }

    @Test fun onlyTheFirstFailureCountsAndLaterEventsChangeNothing() {
        val tracker = tracker()
        val first = tracker.fail(FailureKind.CONNECTION_REFUSED)
        tracker.advance(CheckupStage.DEPLOY)
        val second = tracker.fail(FailureKind.PROTOCOL)
        assertEquals(first, second)
        assertEquals(CheckupStage.REACH, second.failedStage)
        assertEquals(R.string.checkup_action_reach_refused, second.nextAction)
    }

    @Test fun aSessionThatStartedAndThenDroppedReportsEveryStartupStepAsPassed() {
        val tracker = tracker()
        CheckupStage.entries.forEach(tracker::advance)
        val checkup = tracker.fail(FailureKind.TRANSIENT_TRANSPORT)
        assertEquals(List(5) { StageStatus.PASSED } + StageStatus.FAILED, checkup.steps.map { it.status })
        assertEquals(R.string.checkup_action_network, checkup.nextAction)
    }

    @Test fun everyFailedStepHasOneNextActionUnlessTheAttemptWasCancelled() {
        for (stage in CheckupStage.entries) {
            for (kind in FailureKind.entries) {
                val action = ConnectionCheckupGuidance.nextAction(stage, kind)
                if (kind == FailureKind.CANCELLED) assertNull("$stage/$kind", action)
                else assertNotNull("$stage/$kind", action)
            }
        }
    }

    @Test fun theActionFitsWhereTheAttemptStopped() {
        fun action(stage: CheckupStage, kind: FailureKind) = ConnectionCheckupGuidance.nextAction(stage, kind)
        assertEquals(R.string.checkup_action_reach, action(CheckupStage.REACH, FailureKind.NOT_RESPONDING))
        assertEquals(R.string.checkup_action_pair, action(CheckupStage.AUTHORIZE, FailureKind.PAIRING_REQUIRED))
        assertEquals("A port that closes without speaking ADB is probably not ADB",
            R.string.checkup_action_adb_port, action(CheckupStage.AUTHORIZE, FailureKind.TRANSIENT_TRANSPORT))
        assertEquals(R.string.checkup_action_authorize, action(CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION))
        assertEquals(R.string.checkup_action_deploy, action(CheckupStage.DEPLOY, FailureKind.OTHER))
        assertEquals(R.string.checkup_action_reconnect, action(CheckupStage.DEPLOY, FailureKind.TRANSIENT_TRANSPORT))
        assertEquals(R.string.checkup_action_setting, action(CheckupStage.START, FailureKind.INVALID_INPUT))
        assertEquals(R.string.checkup_action_codec, action(CheckupStage.MEDIA, FailureKind.CODEC_OR_INPUT))
        assertEquals(R.string.checkup_action_network, action(CheckupStage.MEDIA, FailureKind.NOT_RESPONDING))
    }

    @Test
    fun aRefusedSessionChoiceSaysToConnectAgainFromTheDeviceList() {
        val invalid = com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind.INVALID_INPUT
        val withChoices = com.scrcpy.remote.session.ConnectionCheckupTracker("host:5555", sessionChoices = true) { 0L }
        withChoices.advance(com.scrcpy.remote.session.CheckupStage.START)
        org.junit.Assert.assertEquals(com.scrcpy.remote.R.string.checkup_action_session_setting, withChoices.fail(invalid).nextAction)
        val saved = com.scrcpy.remote.session.ConnectionCheckupTracker("host:5555") { 0L }
        saved.advance(com.scrcpy.remote.session.CheckupStage.START)
        org.junit.Assert.assertEquals(com.scrcpy.remote.R.string.checkup_action_setting, saved.fail(invalid).nextAction)
    }
}
