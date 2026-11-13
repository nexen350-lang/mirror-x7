package com.scrcpy.remote.session

import android.app.Application
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.session.ConnectionRecoveryPolicy.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The connection checkup against a real scrcpy server, through the loopback adbd of a disposable
 * emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * A fixture can imitate the ADB steps, but only a real server shows where its own checks end an
 * attempt, and only real video proves the last startup step.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionCheckupLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun aWorkingConnectionPassesEveryStartupStepOnceVideoIsDecoded() {
        val port = livePort()
        // A real consumer, so the decoder has somewhere to put the frames that prove video started.
        val frames = HandlerThread("checkup-live-frames").apply { start() }
        val reader = ImageReader.newInstance(640, 480, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(frames.looper))
        val history = ConnectionHistoryStore.get(app)
        val deviceId = "checkup-live-${UUID.randomUUID()}"
        // Only a device that is still saved has a history.
        val repository = DeviceRepository.get(app)
        assertTrue(repository.saveDevice(SavedDevice(id = deviceId, name = "Checkup live", host = HOST, port = port)))
        try {
            withModel { model ->
                instrumentation.runOnMainSync {
                    model.onVisibilityChanged(true)
                    model.setSurface(reader.surface)
                    model.connect(HOST, port, MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false),
                        historyDeviceId = deviceId)
                }
                val checkup = await("The live session decodes video", model) {
                    model.checkup.value?.takeIf { it.steps.last().status == StageStatus.RUNNING }
                }
                assertTrue(model.state.value.toString(), model.state.value is MirrorUiState.Connected)
                assertEquals("$HOST:$port", checkup.endpoint)
                assertEquals(List(5) { StageStatus.PASSED } + StageStatus.RUNNING, checkup.steps.map { it.status })
                assertNull("Connecting is timed on authorization", checkup.steps[CheckupStage.REACH.ordinal].elapsedMillis)
                assertTrue(checkup.toString(), checkup.steps.subList(1, 5).all { (it.elapsedMillis ?: -1) >= 0 })
                assertNull(checkup.failure)
                assertNull(checkup.nextAction)
                // Video started: a saved device's history records that it connected.
                val recorded = await("The saved device's connection is recorded", model) { history.history.value[deviceId] }
                assertTrue(recorded.toString(), recorded.lastConnectedAtMillis != null && recorded.problems.isEmpty())
                instrumentation.runOnMainSync { model.releaseSurface() }
            }
        } finally {
            history.forget(listOf(deviceId))
            repository.deleteDevice(deviceId)
            reader.close()
            frames.quitSafely()
        }
    }

    @Test fun anEncoderAskedForByTheLinkIsNotBlamedOnTheSavedSettings() {
        // A link's options apply to its session alone, and Reconnect would ask for them again.
        val port = livePort()
        withModel { model ->
            instrumentation.runOnMainSync {
                model.connect(HOST, port, MirrorSettings(audioEnabled = false, autoReconnect = false,
                    videoEncoder = MISSING_ENCODER), linkOptions = true)
            }
            val failed = await("The rejected encoder ends the attempt", model) {
                model.state.value as? MirrorUiState.Failed
            }
            val checkup = model.checkup.value
            assertTrue(failed.message, failed.message.startsWith(app.getString(R.string.error_invalid_session_choice)))
            assertEquals(checkup.toString(), CheckupStage.START, checkup?.failedStage)
            assertEquals(R.string.checkup_action_session_setting, checkup?.nextAction)
        }
    }

    @Test fun anEncoderTheServerRejectsIsReportedAsThatSettingWhileTheServerStarts() {
        val port = livePort()
        withModel { model ->
            instrumentation.runOnMainSync {
                model.connect(HOST, port, MirrorSettings(audioEnabled = false, autoReconnect = false,
                    videoEncoder = MISSING_ENCODER))
            }
            val failed = await("The rejected encoder ends the attempt", model) {
                model.state.value as? MirrorUiState.Failed
            }
            val checkup = model.checkup.value
            assertTrue(failed.message, failed.message.startsWith(app.getString(R.string.error_invalid_settings)))
            val rawDetails = failed.details ?: failed.message
            assertTrue("The server's own explanation must be kept: $rawDetails",
                rawDetails.contains(MISSING_ENCODER))
            assertEquals(checkup.toString(), FailureKind.INVALID_INPUT, checkup?.failure)
            // The server refuses before its streams are ready, so the attempt never waits for video.
            assertEquals(checkup.toString(), CheckupStage.START, checkup?.failedStage)
            assertEquals(R.string.checkup_action_setting, checkup?.nextAction)
        }
    }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    private fun withModel(block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("checkup-live-test", model)
        }
        try {
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun <T : Any> await(description: String, model: MirrorViewModel, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("$description: timed out in ${model.state.value} with ${model.checkup.value}")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val MISSING_ENCODER = "qa.scrcpy.remote.missing.encoder"
    }
}
