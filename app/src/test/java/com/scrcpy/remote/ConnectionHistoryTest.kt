package com.scrcpy.remote

import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.ConnectionHistoryCodec
import com.scrcpy.remote.session.ConnectionHistoryStore
import com.scrcpy.remote.session.ConnectionProblem
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import com.scrcpy.remote.session.DeviceConnectionHistory
import com.scrcpy.remote.session.FailureHistory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection history kept for each saved device: the last few problems, a run of the same
 * problem counted once, and when the device last connected.
 */
class ConnectionHistoryTest {
    private val directory: File = Files.createTempDirectory("connection-history").toFile()
    private val file = File(directory, "connection-history.json")
    private var clock = 1_000L

    @After fun removeFiles() {
        directory.deleteRecursively()
    }

    private fun store() = ConnectionHistoryStore(file, now = { clock }, writer = { it.run() })

    @Test fun aRunOfTheSameProblemIsOneEntryUntilTheDeviceConnects() {
        var history = DeviceConnectionHistory()
            .withFailure(CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "10.0.0.2:5555", 1_000)
            .withFailure(CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "10.0.0.2:40001", 2_000)
        assertEquals(
            listOf(ConnectionProblem(CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "10.0.0.2:40001", 1_000, 2_000, 2)),
            history.problems,
        )
        // Another step, or the same step failing another way, is another problem.
        history = history.withFailure(CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION, "10.0.0.2:40001", 3_000)
            .withFailure(CheckupStage.AUTHORIZE, FailureKind.PAIRING_REQUIRED, "10.0.0.2:40001", 4_000)
        assertEquals(3, history.problems.size)
        // A connection in between ends the run, even for the same problem.
        history = history.withConnection(5_000)
            .withFailure(CheckupStage.AUTHORIZE, FailureKind.PAIRING_REQUIRED, "10.0.0.2:40001", 6_000)
        assertEquals(4, history.problems.size)
        assertEquals(5_000L, history.lastConnectedAtMillis)
        assertEquals(listOf(2, 1, 1, 1), history.problems.map { it.count })
        assertFalse(history.connectedSinceLastProblem)
    }

    @Test fun aCancelledAttemptIsNotAProblem() {
        val history = DeviceConnectionHistory()
            .withFailure(CheckupStage.REACH, FailureKind.CANCELLED, "10.0.0.2:5555", 1_000)
        assertTrue(history.isEmpty)
        // Unlike one that ended any other way.
        assertEquals(1, history.withFailure(CheckupStage.REACH, FailureKind.OTHER, "10.0.0.2:5555", 2_000).problems.size)
    }

    @Test fun onlyTheLastFiveProblemsAreKept() {
        var history = DeviceConnectionHistory()
        for (time in 1..7) {
            val stage = if (time % 2 == 0) CheckupStage.REACH else CheckupStage.AUTHORIZE
            history = history.withFailure(stage, FailureKind.OTHER, "host:5555", time * 1_000L)
        }
        assertEquals(DeviceConnectionHistory.MAX_PROBLEMS, history.problems.size)
        assertEquals((3..7).map { it * 1_000L }, history.problems.map { it.firstAtMillis })
    }

    @Test fun theFailureScreenSeesTheCurrentRunAndTheProblemsBeforeIt() {
        val first = DeviceConnectionHistory()
            .withFailure(CheckupStage.REACH, FailureKind.NOT_RESPONDING, "a:5555", 1_000)
        val single = checkNotNull(FailureHistory.of(first))
        assertEquals(first.problems.single(), single.current)
        assertTrue(single.earlier.isEmpty())
        // A first failure, with nothing before it, adds nothing to the screen.
        assertFalse(single.worthShowing)

        val history = first
            .withFailure(CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION, "a:5555", 2_000)
            .withConnection(3_000)
            .withFailure(CheckupStage.START, FailureKind.OTHER, "a:5555", 4_000)
            .withFailure(CheckupStage.START, FailureKind.OTHER, "a:5555", 5_000)
        val view = checkNotNull(FailureHistory.of(history))
        assertEquals(CheckupStage.START, view.current.stage)
        assertEquals(2, view.current.count)
        assertEquals(listOf(CheckupStage.AUTHORIZE, CheckupStage.REACH), view.earlier.map { it.stage })
        assertEquals(3_000L, view.lastConnectedAtMillis)
        assertTrue(view.worthShowing)
        // Each fact is worth saying on its own.
        assertTrue(checkNotNull(FailureHistory.of(DeviceConnectionHistory().withConnection(1_000)
            .withFailure(CheckupStage.REACH, FailureKind.OTHER, "a:5555", 2_000))).worthShowing)
        assertTrue(checkNotNull(FailureHistory.of(DeviceConnectionHistory()
            .withFailure(CheckupStage.REACH, FailureKind.OTHER, "a:5555", 1_000)
            .withFailure(CheckupStage.REACH, FailureKind.OTHER, "a:5555", 2_000))).worthShowing)
        assertNull(FailureHistory.of(DeviceConnectionHistory().withConnection(1_000)))
    }

    @Test fun historySurvivesANewStoreAndIsWrittenWhole() {
        val first = store()
        clock = 10_000
        first.recordFailure("phone", CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "10.0.0.2:5555")
        clock = 20_000
        first.recordFailure("phone", CheckupStage.REACH, FailureKind.CONNECTION_REFUSED, "10.0.0.2:5555")
        clock = 30_000
        first.recordConnected("phone")
        first.recordFailure("tv", CheckupStage.SESSION, FailureKind.TRANSIENT_TRANSPORT, "[fd00::5]:5555")
        val expected = first.history.value
        assertEquals(setOf("phone", "tv"), expected.keys)
        assertEquals(2, expected.getValue("phone").problems.single().count)
        assertEquals(30_000L, expected.getValue("phone").lastConnectedAtMillis)

        assertEquals(expected, store().history.value)
        // Written to a staging file and moved into place, so nothing half-written is left behind.
        assertEquals(listOf(file.name), directory.list()!!.toList())
        assertEquals(expected, ConnectionHistoryCodec.decode(file.readText()))
    }

    @Test fun anUnreadableHistoryStartsAfreshAndUnreadableEntriesAreLeftOut() {
        file.writeText("not json at all")
        val fresh = store()
        assertTrue(fresh.history.value.isEmpty())
        fresh.recordFailure("phone", CheckupStage.MEDIA, FailureKind.CODEC_OR_INPUT, "h:5555")
        assertEquals(1, store().history.value.getValue("phone").problems.size)

        file.writeText("""{"version":1,"devices":{
            "a":{"lastConnectedAt":5,"problems":[
                {"stage":"TELEPORT","kind":"OTHER","endpoint":"h:1","firstAt":1,"lastAt":1,"count":1},
                {"stage":"REACH","kind":"NEW_KIND","endpoint":"h:1","firstAt":1,"lastAt":1,"count":1},
                {"stage":"REACH","kind":"OTHER","endpoint":"h:1","firstAt":1,"lastAt":1,"count":0},
                {"stage":"REACH","kind":"OTHER","endpoint":"h:1","firstAt":2,"lastAt":1,"count":1},
                {"stage":"REACH","kind":"CANCELLED","endpoint":"h:1","firstAt":1,"lastAt":1,"count":1},
                {"stage":"REACH","kind":"OTHER","firstAt":1,"lastAt":1,"count":1},
                {"stage":"AUTHORIZE","kind":"AUTHENTICATION","endpoint":"h:2","firstAt":3,"lastAt":4,"count":2}]},
            "b":"not an object",
            "c":{"problems":"not a list"}}}""")
        val read = store().history.value
        // An entry with nothing readable in it is left out altogether.
        assertEquals(setOf("a"), read.keys)
        assertEquals(
            listOf(ConnectionProblem(CheckupStage.AUTHORIZE, FailureKind.AUTHENTICATION, "h:2", 3, 4, 2)),
            read.getValue("a").problems,
        )
        assertEquals(5L, read.getValue("a").lastConnectedAtMillis)
    }

    @Test fun aFailedWriteKeepsTheHistoryInMemoryAndSaysSo() {
        val errors = mutableListOf<String>()
        val blocked = File(directory, "blocked").apply { mkdirs() }
        // A directory with something in it where the file should be: every write fails.
        File(blocked, "inside").writeText("x")
        val store = ConnectionHistoryStore(blocked, now = { clock }, writer = { it.run() },
            onError = { message, _ -> errors += message })
        store.recordFailure("phone", CheckupStage.REACH, FailureKind.OTHER, "h:5555")
        assertEquals(1, store.history.value.getValue("phone").problems.size)
        assertTrue(errors.isNotEmpty())
    }

    @Test fun forgettingADeviceRemovesItsHistoryAndOnlySavedDevicesAreKept() {
        val store = store()
        for (id in listOf("a", "b", "c")) store.recordFailure(id, CheckupStage.REACH, FailureKind.OTHER, "$id:5555")
        store.forget(listOf("b"))
        assertEquals(setOf("a", "c"), store.history.value.keys)
        assertEquals(setOf("a", "c"), store().history.value.keys)
        store.retainOnly(setOf("c", "unknown"))
        assertEquals(setOf("c"), store.history.value.keys)
        assertEquals(setOf("c"), store().history.value.keys)
    }

    @Test fun onlyAFullyReadDeviceListRemovesTheHistoryOfDevicesNoLongerSaved() {
        val store = store()
        for (id in listOf("kept", "gone")) store.recordFailure(id, CheckupStage.REACH, FailureKind.OTHER, "$id:5555")
        val saved = """[{"id":"kept","name":"Kept","host":"10.0.0.2"}]"""

        // Storage that cannot be read, and a list with an entry this version cannot load, remove nothing.
        store.retainSavedDevices(DeviceRepository(Storage(failReads = true)))
        store.retainSavedDevices(DeviceRepository(Storage(mapOf("devices" to
            """[{"id":"kept","name":"Kept","host":"10.0.0.2"},{"id":7}]"""))))
        assertEquals(setOf("kept", "gone"), store.history.value.keys)

        store.retainSavedDevices(DeviceRepository(Storage(mapOf("devices" to saved))))
        assertEquals(setOf("kept"), store.history.value.keys)
    }

    private class Storage(
        initial: Map<String, Any?> = emptyMap(),
        private val failReads: Boolean = false,
    ) : PreferenceStorage {
        private val values = initial.toMutableMap().apply {
            putIfAbsent("settings", SettingsCodec.encodeSettings(MirrorSettings()))
        }
        override fun read(): Map<String, *> {
            if (failReads) throw IOException("unavailable")
            return values.toMap()
        }
        override fun write(values: Map<String, Any?>): Boolean = true
    }

}
