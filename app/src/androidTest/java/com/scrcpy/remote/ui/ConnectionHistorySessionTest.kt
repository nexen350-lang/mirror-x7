package com.scrcpy.remote.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionHistoryStore
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real connection attempts on the mirror screen, to ports that refuse them or never answer: a saved
 * device's failures go into its history, and an address that is not saved leaves no trace.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionHistorySessionTest {
    @get:Rule(order = 0) val touchMode = TouchModeRule()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DeviceRepository.get(context)
    private val store = ConnectionHistoryStore.get(context)
    private val device = SavedDevice(id = "connection-history-session-${UUID.randomUUID()}", name = "Closed port",
        host = HOST, port = CLOSED_PORT)

    @Before fun saveTheDevice() {
        // A connection holds the process with the session service, whose notification Android 13+
        // asks about first; the question would pause the screen under test.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertTrue(repository.saveDevice(device))
        store.forget(listOf(device.id))
    }

    @After fun removeTheDeviceAndItsHistory() {
        repository.deleteDevice(device.id)
        store.forget(listOf(device.id))
    }

    @Test fun eachFailedAttemptOfASavedDeviceIsRecordedAndARepeatSaysSo() {
        val intent = MirrorActivity.newIntent(context, HOST, CLOSED_PORT, deviceId = device.id)
        ActivityScenario.launch<MirrorActivity>(intent).use { scenario ->
            val model = modelOf(scenario)
            await("The closed port fails") { model.state.value is MirrorUiState.Failed }
            await("The failure is recorded for the saved device") { store.history.value[device.id] != null }
            val first = store.history.value.getValue(device.id).problems.single()
            assertEquals(CheckupStage.REACH, first.stage)
            assertEquals("$HOST:$CLOSED_PORT", first.endpoint)
            assertEquals(1, first.count)
            // A first failure, with nothing before it, adds nothing to the card.
            compose.waitForIdle()
            compose.onNodeWithTag("connection-checkup").assertExists()
            compose.onNodeWithTag("failure-history").assertDoesNotExist()

            // Asked for again, as from the device list: the same failure, twice in a row.
            scenario.onActivity { it.startActivity(intent) }
            await("The second failure joins the first") {
                store.history.value[device.id]?.problems?.singleOrNull()?.count == 2
            }
            val run = store.history.value.getValue(device.id).problems.single()
            val repeated = context.resources.getQuantityString(R.plurals.connection_history_repeated, 2, 2,
                historyTime(context, run.firstAtMillis))
            compose.waitUntil(10_000) { compose.onAllNodesWithText(repeated).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(repeated).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun anAttemptWhoseDeviceWasRemovedMeanwhileAddsNothingToItsHistory() {
        // A port that takes the connection and then says nothing, so the attempt is still running
        // when its device is removed for good; a retry would not get this far, as it asks for the
        // removed device's settings first.
        val server = ServerSocket(0, 1, InetAddress.getByName(HOST))
        val accepted = LinkedBlockingQueue<Socket>()
        val acceptor = thread(name = "silent-port") { runCatching { while (true) accepted.put(server.accept()) } }
        val silent = SavedDevice(id = "connection-history-removed-${UUID.randomUUID()}", name = "Silent port",
            host = HOST, port = server.localPort)
        assertTrue(repository.saveDevice(silent))
        try {
            val intent = MirrorActivity.newIntent(context, HOST, server.localPort, deviceId = silent.id)
            ActivityScenario.launch<MirrorActivity>(intent).use { scenario ->
                val model = modelOf(scenario)
                val connection = accepted.poll(15, TimeUnit.SECONDS)
                assertNotNull("The attempt reached the port", connection)
                repository.deleteDevice(silent.id)
                store.forget(listOf(silent.id))
                // The device hangs up only once it has gone from the list and its Undo with it.
                connection!!.close()
                await("The attempt fails") { model.state.value is MirrorUiState.Failed }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertNotNull("A failure a saved device would have had recorded",
                    model.checkup.value?.failedStage)
                assertEquals("A device no longer saved gets no history back", null, store.history.value[silent.id])
            }
        } finally {
            server.close()
            acceptor.join(5_000)
            accepted.forEach { it.close() }
            repository.deleteDevice(silent.id)
            store.forget(listOf(silent.id))
        }
    }
    @Test fun anAddressThatIsNotSavedLeavesNoTrace() {
        val before = store.history.value
        ActivityScenario.launch<MirrorActivity>(MirrorActivity.newIntent(context, HOST, OTHER_CLOSED_PORT)).use { scenario ->
            val model = modelOf(scenario)
            await("The unsaved address fails") { model.state.value is MirrorUiState.Failed }
        }
        // Nor does a saved device's id given with an address that is not the device's.
        ActivityScenario.launch<MirrorActivity>(
            MirrorActivity.newIntent(context, HOST, OTHER_CLOSED_PORT, deviceId = device.id),
        ).use { scenario ->
            val model = modelOf(scenario)
            await("The mismatched address fails") { model.state.value is MirrorUiState.Failed }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(before, store.history.value)
    }

    private fun modelOf(scenario: ActivityScenario<MirrorActivity>): MirrorViewModel {
        lateinit var model: MirrorViewModel
        scenario.onActivity { model = ViewModelProvider(it)[MirrorViewModel::class.java] }
        return model
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(message, condition())
    }

    private companion object {
        const val HOST = "127.0.0.1"
        /** Ports nothing on the emulator listens on: the connection is refused at once. */
        const val CLOSED_PORT = 9
        const val OTHER_CLOSED_PORT = 1
    }
}
