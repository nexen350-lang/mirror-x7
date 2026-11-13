package com.scrcpy.remote.ui

import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionCheckupTracker
import com.scrcpy.remote.session.ConnectionHistoryStore
import com.scrcpy.remote.session.ConnectionProblem
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.FailureHistory
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A saved device's connection history: what the failure screen says about earlier attempts, the
 * history its menu shows, and when it goes with the device.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionHistoryUiTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val testIds = mutableListOf<String>()
    private val repository get() = DeviceRepository.get(compose.activity)
    private val store get() = ConnectionHistoryStore.get(compose.activity)

    @After fun removeOnlyTestDevicesAndTheirHistory() {
        compose.runOnIdle { testIds.forEach { repository.deleteDevice(it) } }
        store.forget(testIds)
    }

    @Test fun aFirstFailureAddsNothingButARepeatedOneSaysHowOftenAndWhenItLastWorked() {
        val now = System.currentTimeMillis()
        val endpoint = "10.0.0.2:41234"
        val checkup = ConnectionCheckupTracker(endpoint) { now }.fail(FailureKind.CONNECTION_REFUSED)
        val first = ConnectionProblem(CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, endpoint, now - HOUR, now, 1)
        val older = ConnectionProblem(CheckupStage.REACH, FailureKind.NOT_RESPONDING, "10.0.0.2:5555",
            now - 3 * DAY, now - 3 * DAY + MINUTE, 4)
        val newer = ConnectionProblem(CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION, endpoint,
            now - 2 * DAY, now - 2 * DAY, 1)
        val lastConnected = now - DAY
        var connecting by mutableStateOf(false)
        var history by mutableStateOf(FailureHistory(first, emptyList(), null))
        compose.runOnUiThread {
            compose.activity.setContent {
                ScrcpyRemoteTheme(darkTheme = false) {
                    ConnectionStatus("Could not connect", connecting = connecting, onDismiss = {}, checkup = checkup,
                        history = history)
                }
            }
        }
        compose.waitForIdle()
        // A first failure, with nothing before it: the card says no more than it did.
        compose.onNodeWithTag("connection-checkup").assertExists()
        compose.onNodeWithTag("failure-history").assertDoesNotExist()

        history = FailureHistory(first.copy(count = 3), listOf(newer, older), lastConnected)
        compose.waitForIdle()
        compose.onNodeWithTag("failure-history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(plural(R.plurals.connection_history_repeated, 3, 3, time(first.firstAtMillis, now)))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_history_last_connected, time(lastConnected, now)))
            .performScrollTo().assertIsDisplayed()
        val newerRow = compose.onNodeWithText(text(R.string.connection_history_problem, time(newer.lastAtMillis, now),
            text(R.string.checkup_stage_authorize), text(R.string.connection_history_outcome_not_allowed)))
            .performScrollTo().assertIsDisplayed()
        val olderRow = compose.onNodeWithText(plural(R.plurals.connection_history_problem_repeated, 4,
            time(older.lastAtMillis, now), text(R.string.checkup_stage_reach, "10.0.0.2:5555"),
            text(R.string.connection_history_outcome_no_answer), 4))
            .performScrollTo().assertIsDisplayed()
        assertTrue("Earlier problems are newest first",
            newerRow.fetchSemanticsNode().positionInWindow.y < olderRow.fetchSemanticsNode().positionInWindow.y)

        // While connecting there is no failure for it to describe.
        connecting = true
        compose.waitForIdle()
        compose.onNodeWithTag("failure-history").assertDoesNotExist()
    }

    @Test fun theDeviceMenuShowsItsConnectionHistoryAndClearingEmptiesIt() {
        val device = addTestDevice()
        store.recordFailure(device.id, CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "127.0.0.1:41234")
        store.recordFailure(device.id, CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "127.0.0.1:41234")
        store.recordConnected(device.id)
        store.recordFailure(device.id, CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION, "127.0.0.1:41234")
        val history = store.history.value.getValue(device.id)
        val now = System.currentTimeMillis()

        openHistory(device)
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.connection_history_last_connected,
            time(history.lastConnectedAtMillis!!, now))).assertIsDisplayed()
        val newest = compose.onNodeWithText(text(R.string.connection_history_problem, time(history.problems[1].lastAtMillis, now),
            text(R.string.checkup_stage_authorize), text(R.string.connection_history_outcome_not_allowed))).assertIsDisplayed()
        val oldest = compose.onNodeWithText(plural(R.plurals.connection_history_problem_repeated, 2,
            time(history.problems[0].lastAtMillis, now), text(R.string.checkup_stage_reach, "127.0.0.1:41234"),
            text(R.string.connection_history_outcome_refused), 2)).assertIsDisplayed()
        assertTrue("Newest first", newest.fetchSemanticsNode().positionInWindow.y < oldest.fetchSemanticsNode().positionInWindow.y)
        compose.onNodeWithText(text(R.string.connection_history_kept_here)).assertExists()

        compose.onNodeWithText(text(R.string.connection_history_clear)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) { device.id !in store.history.value }
        compose.onNodeWithText(text(R.string.connection_history_none)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.connection_history_clear)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.close)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun aRemovedDeviceKeepsItsHistoryUntilItsUndoHasGone() {
        val first = addTestDevice()
        val second = addTestDevice()
        for (device in listOf(first, second)) {
            store.recordFailure(device.id, CheckupStage.REACH, FailureKind.NOT_RESPONDING, "127.0.0.1:41234")
        }
        remove(first)
        // Undo can still bring it back, with its history.
        assertTrue(first.id in store.history.value)
        compose.onNodeWithText(text(R.string.undo)).performClick()
        compose.waitUntil(5_000) { repository.devices.value.any { it.id == first.id } }
        assertTrue(first.id in store.history.value)

        remove(first)
        // Removing another device takes the first one's Undo away, and with it the first one's history.
        remove(second)
        compose.waitUntil(5_000) { first.id !in store.history.value }
        assertTrue(second.id in store.history.value)
        // The second goes once its own Undo has had its time.
        compose.waitUntil(20_000) { second.id !in store.history.value }
    }

    private fun remove(device: SavedDevice) {
        openActions(device)
        compose.onNodeWithText(text(R.string.device_list_remove)).performClick()
        compose.waitUntil(5_000) { repository.devices.value.none { it.id == device.id } }
        compose.onNodeWithText(text(R.string.device_removed, device.name)).assertIsDisplayed()
    }

    private fun openHistory(device: SavedDevice) {
        openActions(device)
        compose.onNodeWithText(text(R.string.connection_history_title)).performClick()
        compose.waitForIdle()
    }

    private fun addTestDevice(): SavedDevice {
        val id = "connection-history-test-${UUID.randomUUID()}"
        val device = SavedDevice(id = id, name = id, host = "127.0.0.1", port = 41234)
        testIds += id
        compose.runOnIdle { assertTrue(repository.saveDevice(device)) }
        store.forget(listOf(id))
        return device
    }

    private fun openActions(device: SavedDevice) {
        compose.onNodeWithText(text(R.string.device_list_search)).performTextReplacement(device.name)
        compose.pressDeviceSearch()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            compose.waitUntil(timeoutMillis = 10_000) {
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
        }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription(text(R.string.device_list_hide_pairing_help))
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            compose.onNodeWithContentDescription(text(R.string.device_list_hide_pairing_help)).performClick()
        }
        compose.onNodeWithTag("saved-device-list").performScrollToNode(
            hasContentDescription(text(R.string.device_list_more_actions, device.name)),
        )
        compose.onNodeWithContentDescription(text(R.string.device_list_more_actions, device.name)).performClick()
        compose.waitForIdle()
    }

    private fun time(atMillis: Long, nowMillis: Long) = historyTime(compose.activity, atMillis, nowMillis)
    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)
    private fun plural(id: Int, count: Int, vararg args: Any) = compose.activity.resources.getQuantityString(id, count, *args)

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
