package com.scrcpy.remote.session

import android.app.Application
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Picture & sound against a real scrcpy server, through the loopback adbd of a disposable emulator.
 * Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * What a switch must change is what the device sends and what this phone decodes, not the label:
 * each check reads the codec the stream itself announced and the decoder's own frame count, with
 * a real surface to decode into.
 */
@RunWith(AndroidJUnit4::class)
class StreamChoiceLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun applyingAChoiceReconnectsWithItAndTheDeviceSendsIt() {
        val port = livePort()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            val choice = StreamChoice(VideoCodec.H265, AudioCodec.AAC, ScrcpyServerVersion.DEFAULT)
            main { model.applyStreamChoice(choice) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H265, ScrcpyCodecs.AUDIO_AAC)
            assertEquals(choice, StreamChoice.of(checkNotNull(model.activeSettings.value)))
            assertTrue(serverProcesses().toString(), serverProcesses().all { "scrcpy.Server 5.0.1 " in it })
            // A switch that worked leaves nothing to go back to.
            await("nothing to go back to once it works") { true.takeIf { model.revertibleChoice.value == null } }
            // Choosing what already runs restarts nothing.
            val generation = (model.state.value as MirrorUiState.Connected).sessionGeneration
            main { model.applyStreamChoice(choice) }
            SystemClock.sleep(1_500)
            assertEquals(generation, (model.state.value as? MirrorUiState.Connected)?.sessionGeneration)
        }
    }

    @Test fun aChoiceThatFailsOffersTheOneThatWorkedAndGoesBackToIt() {
        val port = livePort()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            main { model.loadEncoders() }
            val encoders = await("encoders listed") { model.encoders.value.takeIf { it.loaded && !it.loading } }
            // A codec this phone decodes and the device cannot encode: the switch the sheet would
            // mark as not on the device, applied anyway as a stale or unchecked list would allow.
            val missing = VideoCodec.supported().firstOrNull { codec ->
                encoders.encoders.none { it.codec.equals(codec.serverValue, ignoreCase = true) }
            }
            assumeTrue("The device has an encoder for every codec this phone decodes", missing != null)
            val codec = checkNotNull(missing)
            val working = StreamChoice.of(checkNotNull(model.activeSettings.value))
            main { model.applyStreamChoice(working.copy(videoCodec = codec)) }
            val failed = await("the switch to ${codec.label} failed") { model.state.value as? MirrorUiState.Failed }
            Log.i(TAG, "${codec.label}: ${failed.message}")
            assertEquals(working, model.revertibleChoice.value)
            main { model.revertStreamChoice() }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            assertEquals(working, StreamChoice.of(checkNotNull(model.activeSettings.value)))
            assertNull(model.revertibleChoice.value)
        }
    }

    @Test fun applyingDuringARecordingOffersTheRecordingFirst() {
        val port = livePort()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            assertTrue(main { model.startRecording() })
            await("the recording opens") { model.recordingStatus.value }
            SystemClock.sleep(2_000)
            main { model.applyStreamChoice(StreamChoice(VideoCodec.H265, AudioCodec.OPUS, ScrcpyServerVersion.V5_0)) }
            val offered = await("the recording offered") { model.finishedRecording.value }
            assertTrue("The recording kept what was captured", offered.length() > 0)
            awaitStream(model, ScrcpyCodecs.VIDEO_H265, ScrcpyCodecs.AUDIO_OPUS)
            main { model.consumeFinishedRecording(keep = false) }
        }
    }

    @Test fun recordingWithSoundAfterASoundSwitchReconnectsWithAac() {
        val port = livePort()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            // Picture & sound moves the session's sound to FLAC, which an MP4 cannot hold.
            main { model.applyStreamChoice(StreamChoice(VideoCodec.H264, AudioCodec.FLAC, ScrcpyServerVersion.V5_0)) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_FLAC)
            assertEquals(RecordingSound.NeedsAac(AudioCodec.FLAC), main { model.recordingSound() })
            main { model.recordWithSoundAfterReconnect() }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_AAC)
            await("the recording starts with sound") { model.recordingStatus.value?.takeIf { it.withSound } }
            SystemClock.sleep(2_500)
            main { model.stopRecording() }
            val offered = await("the recording offered") { model.finishedRecording.value }
            assertTrue("The recording holds the picture and its sound", offered.length() > 0)
            main { model.consumeFinishedRecording(keep = false) }
        }
    }

    @Test fun goingBackKeepsAnEncoderChosenForTheSession() {
        val port = livePort()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            main { model.loadEncoders() }
            val encoders = await("encoders listed") { model.encoders.value.takeIf { it.loaded && !it.loading } }
            val missing = VideoCodec.supported().firstOrNull { codec ->
                encoders.encoders.none { it.codec.equals(codec.serverValue, ignoreCase = true) }
            }
            assumeTrue("The device has an encoder for every codec this phone decodes", missing != null)
            // An encoder chosen for this session, as when the device's default one is broken.
            val chosen = encoders.encoders.first { it.codec.equals("h264", ignoreCase = true) }.name
            val generation = (model.state.value as MirrorUiState.Connected).sessionGeneration
            main { model.useVideoEncoderForSession(chosen) }
            await("reconnected with $chosen") {
                true.takeIf { (model.state.value as? MirrorUiState.Connected)?.sessionGeneration?.let { it != generation } == true }
            }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            assertTrue(serverProcesses().toString(), serverProcesses().single().contains("video_encoder=$chosen "))
            val working = StreamChoice.of(checkNotNull(model.activeSettings.value))
            main { model.applyStreamChoice(working.copy(videoCodec = checkNotNull(missing))) }
            await("the switch failed") { model.state.value as? MirrorUiState.Failed }
            main { model.revertStreamChoice() }
            awaitStream(model, ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_OPUS)
            // The way back is the session as it was, encoder included.
            assertEquals(chosen, model.activeSettings.value?.videoEncoder)
            assertTrue(serverProcesses().toString(), serverProcesses().single().contains("video_encoder=$chosen "))
        }
    }

    private fun settings() = MirrorSettings(maxSize = 640, autoReconnect = false, backgroundAudio = false)

    /** Connected, with the stream announcing [video] and [audio], and frames decoded since. */
    private fun awaitStream(model: MirrorViewModel, video: Int, audio: Int) {
        await("streams ${ScrcpyCodecs.codecName(video)} and ${ScrcpyCodecs.codecName(audio)}") {
            true.takeIf { model.state.value is MirrorUiState.Connected && main { model.streamCodecIds() } == (video to audio) }
        }
        val start = main { model.liveFrameStats() }?.decodedFrames ?: 0
        await("frames decoded as ${ScrcpyCodecs.codecName(video)}") {
            main { model.liveFrameStats() }?.decodedFrames?.takeIf { it > start + 2 }
        }
    }

    /** The running scrcpy servers' own command lines, without the shells that started them. */
    private fun serverProcesses(): List<String> =
        shell("ps -A -o ARGS").lines().map { it.trim() }
            .filter { it.startsWith("app_process") && "com.genymobile.scrcpy.Server " in it }
            .map { "$it " }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    /** A model that is on screen, decoding into a surface whose frames are taken and dropped. */
    private fun withVisibleModel(block: (MirrorViewModel) -> Unit) {
        val thread = HandlerThread("stream-choice-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 1280, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("stream-choice-live-test", model)
            model.onVisibilityChanged(true)
            model.setSurface(reader.surface)
        }
        try {
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            reader.close()
            thread.quitSafely()
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> await(description: String, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("$description: timed out")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val TAG = "StreamChoiceLive"
    }
}
