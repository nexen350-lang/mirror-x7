package com.scrcpy.remote.session

import android.app.Application
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Release endurance checks for Picture & sound and the session lifecycle, against real scrcpy
 * servers through the loopback adbd of a disposable emulator. On top of `-e qaLoopbackAdbPort`,
 * each part is opted into separately because it takes minutes: `-e qaStreamChoiceCycles <n>`
 * for repeated switches and `-e qaSoakMinutes <n>` for observed streaming, which captures the
 * camera named by `-e qaLoopbackCameraId` so that frames keep coming.
 *
 * What is checked is what the device sends and this phone decodes (the codec each stream
 * announced and the decoder's own frame count), that one server runs per session, and that file
 * descriptors and threads do not pile up. Results go to `files/qa-endurance/` as JSON for the
 * QA host to collect and delete.
 */
@RunWith(AndroidJUnit4::class)
class StreamChoiceEnduranceLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun everySwitchReconnectsWithTheChosenCodecsAndLeavesOneServer() {
        val cycles = intArgument("qaStreamChoiceCycles")
        val port = livePort()
        val report = JSONObject()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, VideoCodec.H264, AudioCodec.OPUS)
            val (videos, audios) = codecsOnBothEnds(model)
            assumeTrue("Needs two video codecs both ends support: $videos", videos.size >= 2)
            assumeTrue("Needs two sound codecs both ends support: $audios", audios.size >= 2)
            settle()
            val before = resources()
            val runs = JSONArray()
            val timings = mutableListOf<Long>()
            for (index in 0 until cycles) {
                // Every step changes the video codec, so each one is a real restart; with five video
                // and four sound codecs, twenty steps meet every pair once.
                val choice = StreamChoice(
                    videos[(index + 1) % videos.size],
                    audios[(index + 1) % audios.size],
                    ScrcpyServerVersion.DEFAULT,
                )
                val elapsed = switchTo(model, choice)
                timings += elapsed
                val servers = serverProcesses()
                assertEquals("One server per session: $servers", 1, servers.size)
                assertTrue(servers.toString(), "scrcpy.Server ${choice.server.version} " in servers.single())
                assertEquals(choice, StreamChoice.of(checkNotNull(model.activeSettings.value)))
                assertNull("A switch that works leaves nothing to go back to", model.revertibleChoice.value)
                runs.put(JSONObject().put("cycle", index + 1).put("video", choice.videoCodec.serverValue)
                    .put("audio", choice.audioCodec.serverValue).put("server", choice.server.version)
                    .put("reconnectToFramesMs", elapsed))
                Log.i(TAG, "cycle ${index + 1}/$cycles ${describe(choice)}: frames after $elapsed ms")
            }
            settle()
            val after = resources()
            report.put("videoCodecs", JSONArray(videos.map { it.serverValue }))
            report.put("audioCodecs", JSONArray(audios.map { it.serverValue }))
            report.put("cycles", runs)
            report.put("reconnectToFramesMs", summary(timings))
            report.put("resourcesBefore", before.json()).put("resourcesAfter", after.json())
            // A leak of one descriptor or thread per session would add up to the number of cycles.
            assertTrue("File descriptors grew: $before -> $after", after.fds - before.fds < cycles / 2)
            assertTrue("Threads grew: $before -> $after", after.threads - before.threads < cycles / 2)
        }
        val left = awaitNoServer()
        report.put("serversAfterClose", left)
        write("stream-choice-cycles", report)
    }

    @Test fun aSecondChoiceWhileTheFirstStillConnectsIsTheOneThatRuns() {
        intArgument("qaStreamChoiceCycles")
        val port = livePort()
        val report = JSONObject()
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, VideoCodec.H264, AudioCodec.OPUS)
            val (videos, audios) = codecsOnBothEnds(model)
            assumeTrue("Needs two video codecs both ends support: $videos", videos.size >= 2)
            val races = JSONArray()
            // Both in the same main-thread turn, and the second while the first deploys its server.
            for ((name, gapMs) in listOf("same turn" to 0L, "during deployment" to 400L)) {
                val current = StreamChoice.of(checkNotNull(model.activeSettings.value))
                val first = StreamChoice(videos.first { it != current.videoCodec },
                    audios.firstOrNull { it != current.audioCodec } ?: current.audioCodec, ScrcpyServerVersion.DEFAULT)
                // The second differs from the first in its video codec, so the stream shows which won.
                val second = StreamChoice(
                    videos.lastOrNull { it != current.videoCodec && it != first.videoCodec }
                        ?: videos.first { it != first.videoCodec },
                    AudioCodec.RAW, ScrcpyServerVersion.V5_0)
                val generation = connectedGeneration(model)
                if (gapMs == 0L) {
                    main { model.applyStreamChoice(first); model.applyStreamChoice(second) }
                } else {
                    main { model.applyStreamChoice(first) }
                    SystemClock.sleep(gapMs)
                    main { model.applyStreamChoice(second) }
                }
                await("$name: reconnected") { connectedGeneration(model)?.takeIf { it != generation } }
                awaitStream(model, second.videoCodec, second.audioCodec)
                // Long enough for an orphaned first server to have shown itself.
                SystemClock.sleep(3_000)
                val servers = serverProcesses()
                assertEquals("$name: one server: $servers", 1, servers.size)
                assertTrue("$name: $servers", "scrcpy.Server 5.0.1 " in servers.single())
                assertEquals(name, second, StreamChoice.of(checkNotNull(model.activeSettings.value)))
                assertEquals(name, second.videoCodec to second.audioCodec, codecsAnnounced(model))
                assertNull(name, model.revertibleChoice.value)
                races.put(JSONObject().put("race", name).put("first", describe(first)).put("second", describe(second))
                    .put("servers", JSONArray(servers)))
            }
            report.put("races", races)
        }
        report.put("serversAfterClose", awaitNoServer())
        write("stream-choice-races", report)
    }

    @Test fun observedStreamingThroughReconnectsAndVisibilityChanges() {
        val minutes = intArgument("qaSoakMinutes")
        val port = livePort()
        val cameraArgument = InstrumentationRegistry.getArguments().getString("qaLoopbackCameraId")
        assumeTrue("Opt in with qaLoopbackCameraId: a camera keeps frames coming", cameraArgument != null)
        val cameraId = requireNotNull(cameraArgument)
        val report = JSONObject().put("minutesRequested", minutes)
        withVisibleModel { model ->
            main { model.connect(HOST, port, settings()) }
            awaitStream(model, VideoCodec.H264, AudioCodec.OPUS)
            val screen = connectedGeneration(model)
            main { model.useCameraForSession(CameraCapture(id = cameraId, fps = 30)) }
            await("the camera session") { connectedGeneration(model)?.takeIf { it != screen } }
            awaitStream(model, VideoCodec.H264, AudioCodec.OPUS)
            main { model.diagnosticsEnabled = true }
            val target = minutes * 60_000L
            val samples = JSONArray()
            val failures = JSONArray()
            val events = JSONArray()
            val reconnects = mutableListOf<Long>()
            var observed = 0L
            var excluded = 0L
            var nextEvent = EVENT_EVERY_MS
            var eventCount = 0
            var nextSample = 0L
            val cpuStart = Process.getElapsedCpuTime()
            val wallStart = SystemClock.elapsedRealtime()
            var last = SystemClock.elapsedRealtime()
            var lastFrames = checkNotNull(main { model.liveFrameStats() }).decodedFrames
            while (observed < target) {
                // A session that stopped working would never add observed time: give up rather than
                // run forever, and let the failures say why.
                if (failures.length() >= MAX_FAILURES || SystemClock.elapsedRealtime() - wallStart > target * 2 + 600_000) break
                SystemClock.sleep(OBSERVE_EVERY_MS)
                val now = SystemClock.elapsedRealtime()
                val interval = now - last
                last = now
                val frames = main { model.liveFrameStats() }
                val state = model.state.value
                val servers = serverProcesses()
                val problem = when {
                    // The emulator was paused or the host slept: not an observation at all.
                    interval > OBSERVE_EVERY_MS * 3 -> "gap"
                    state !is MirrorUiState.Connected -> "state $state"
                    frames == null || frames.decodedFrames <= lastFrames -> "no new frames (${frames?.decodedFrames} after $lastFrames)"
                    servers.size != 1 -> "servers $servers"
                    else -> null
                }
                frames?.let { lastFrames = it.decodedFrames }
                if (problem == "gap") {
                    excluded += interval
                } else if (problem != null) {
                    failures.put(JSONObject().put("atObservedMs", observed).put("problem", problem))
                    Log.w(TAG, "observation failed at ${observed / 1000} s: $problem")
                } else {
                    observed += interval
                }
                if (observed >= nextSample) {
                    samples.put(sample(model, observed, cpuStart, wallStart))
                    nextSample += SAMPLE_EVERY_MS
                }
                if (observed >= nextEvent && observed < target) {
                    eventCount++
                    val started = SystemClock.elapsedRealtime()
                    val what = if (eventCount % 2 == 1) {
                        // Leaving the screen and coming back, as switching apps does.
                        main { model.onVisibilityChanged(false) }
                        SystemClock.sleep(3_000)
                        main { model.onVisibilityChanged(true) }
                        val shown = checkNotNull(main { model.liveFrameStats() }).decodedFrames
                        await("frames after returning") { main { model.liveFrameStats() }?.decodedFrames?.takeIf { it > shown + 2 } }
                        "hidden for 3 s and shown again"
                    } else {
                        val current = StreamChoice.of(checkNotNull(model.activeSettings.value))
                        val next = current.copy(videoCodec = if (current.videoCodec == VideoCodec.H264) VideoCodec.H265 else VideoCodec.H264)
                        val elapsed = switchTo(model, next)
                        reconnects += elapsed
                        "switched to ${describe(next)}, frames after $elapsed ms"
                    }
                    events.put(JSONObject().put("atObservedMs", observed).put("event", what)
                        .put("ms", SystemClock.elapsedRealtime() - started))
                    Log.i(TAG, "event $eventCount at ${observed / 1000} s: $what")
                    nextEvent += EVENT_EVERY_MS
                    last = SystemClock.elapsedRealtime()
                    lastFrames = checkNotNull(main { model.liveFrameStats() }).decodedFrames
                }
            }
            samples.put(sample(model, observed, cpuStart, wallStart))
            report.put("observedMs", observed).put("excludedGapMs", excluded)
                .put("wallMs", SystemClock.elapsedRealtime() - wallStart)
                .put("failures", failures).put("events", events).put("samples", samples)
                .put("reconnectToFramesMs", summary(reconnects))
            write("soak", report)
            assertEquals("Failed observations: $failures", 0, failures.length())
            assertTrue("Observed only $observed of $target ms", observed >= target)
        }
        report.put("serversAfterClose", awaitNoServer())
        write("soak", report)
    }

    private fun settings() = MirrorSettings(maxSize = 640, autoReconnect = false, backgroundAudio = false)

    private fun describe(choice: StreamChoice) =
        "${choice.videoCodec.serverValue}/${choice.audioCodec.serverValue} on ${choice.server.version}"

    /** Codecs this phone decodes and the device can send, classified by the sheet's own rules. */
    private fun codecsOnBothEnds(model: MirrorViewModel): Pair<List<VideoCodec>, List<AudioCodec>> {
        main { model.loadEncoders(); model.loadAudioEncoders() }
        val video = await("video encoders listed") { model.encoders.value.takeIf { it.loaded && !it.loading } }
        val audio = await("audio encoders listed") { model.audioEncoders.value.takeIf { it.loaded && !it.loading } }
        return VideoCodec.supported().filter { StreamChoices.videoSupport(it, video) == CodecSupport.ON_DEVICE } to
            AudioCodec.supported().filter { StreamChoices.audioSupport(it, audio) == CodecSupport.ON_DEVICE }
    }

    /** Applies [choice] and returns how long it took until the new stream's frames were decoded. */
    private fun switchTo(model: MirrorViewModel, choice: StreamChoice): Long {
        val generation = connectedGeneration(model)
        val started = SystemClock.elapsedRealtime()
        main { model.applyStreamChoice(choice) }
        await("reconnected for ${describe(choice)}") { connectedGeneration(model)?.takeIf { it != generation } }
        awaitStream(model, choice.videoCodec, choice.audioCodec)
        return SystemClock.elapsedRealtime() - started
    }

    private fun connectedGeneration(model: MirrorViewModel): Long? =
        (model.state.value as? MirrorUiState.Connected)?.sessionGeneration

    private fun codecsAnnounced(model: MirrorViewModel): Pair<VideoCodec?, AudioCodec?>? =
        main { model.streamCodecIds() }?.let { (video, audio) ->
            VideoCodec.entries.firstOrNull { codecId(it) == video } to AudioCodec.entries.firstOrNull { codecId(it) == audio }
        }

    /** Connected, with the streams announcing [video] and [audio], and frames decoded since. */
    private fun awaitStream(model: MirrorViewModel, video: VideoCodec, audio: AudioCodec) {
        await("streams ${video.serverValue} and ${audio.serverValue}") {
            true.takeIf {
                model.state.value is MirrorUiState.Connected &&
                    main { model.streamCodecIds() } == (codecId(video) to codecId(audio))
            }
        }
        val start = main { model.liveFrameStats() }?.decodedFrames ?: 0
        await("frames decoded as ${video.serverValue}") {
            main { model.liveFrameStats() }?.decodedFrames?.takeIf { it > start + 2 }
        }
    }

    private fun codecId(codec: VideoCodec) = when (codec) {
        VideoCodec.H264 -> ScrcpyCodecs.VIDEO_H264
        VideoCodec.H265 -> ScrcpyCodecs.VIDEO_H265
        VideoCodec.AV1 -> ScrcpyCodecs.VIDEO_AV1
        VideoCodec.VP8 -> ScrcpyCodecs.VIDEO_VP8
        VideoCodec.VP9 -> ScrcpyCodecs.VIDEO_VP9
    }

    private fun codecId(codec: AudioCodec) = when (codec) {
        AudioCodec.OPUS -> ScrcpyCodecs.AUDIO_OPUS
        AudioCodec.AAC -> ScrcpyCodecs.AUDIO_AAC
        AudioCodec.FLAC -> ScrcpyCodecs.AUDIO_FLAC
        AudioCodec.RAW -> ScrcpyCodecs.AUDIO_RAW
    }

    private data class Resources(val fds: Int, val threads: Int, val pssKb: Long) {
        fun json(): JSONObject = JSONObject().put("fds", fds).put("threads", threads).put("pssKb", pssKb)
    }

    /** This process's open descriptors, threads and memory; the app and its tests share it. */
    private fun resources() = Resources(
        File("/proc/self/fd").list()?.size ?: -1,
        File("/proc/self/task").list()?.size ?: -1,
        Debug.getPss(),
    )

    /** Lets descriptors closed by finalizers go before counting. */
    private fun settle() {
        repeat(2) { Runtime.getRuntime().gc(); System.runFinalization() }
        SystemClock.sleep(2_000)
    }

    private fun sample(model: MirrorViewModel, observedMs: Long, cpuStart: Long, wallStart: Long): JSONObject {
        val frames = main { model.liveFrameStats() }
        val latency = model.videoLatency.value
        val timing = model.mediaTiming.value
        val wall = SystemClock.elapsedRealtime() - wallStart
        return resources().json().put("observedMs", observedMs)
            .put("cpuPercentOfOneCore", if (wall > 0) (Process.getElapsedCpuTime() - cpuStart) * 100.0 / wall else 0.0)
            .put("decodedFrames", frames?.decodedFrames).put("skippedFrames", frames?.skippedFrames)
            .put("decodedFps", frames?.decodedFps)
            .put("latencyMedianMs", latency.medianMillis).put("latencyP95Ms", latency.p95Millis)
            .put("audioUnderruns", timing.underruns).put("audioQueuedMs", timing.outputQueuedMs)
    }

    private fun summary(values: List<Long>): JSONObject {
        if (values.isEmpty()) return JSONObject().put("count", 0)
        val sorted = values.sorted()
        fun percentile(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return JSONObject().put("count", sorted.size).put("p50", percentile(0.5)).put("p95", percentile(0.95))
            .put("min", sorted.first()).put("max", sorted.last())
    }

    private fun write(name: String, report: JSONObject) {
        val dir = File(app.filesDir, "qa-endurance").apply { mkdirs() }
        File(dir, "$name.json").writeText(report.toString(2))
        Log.i(TAG, "$name: $report")
    }

    /** Waits for every server to be gone once the session's model is cleared, and lists any left. */
    private fun awaitNoServer(): JSONArray {
        val until = SystemClock.elapsedRealtime() + 15_000
        var left = serverProcesses()
        while (left.isNotEmpty() && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(500)
            left = serverProcesses()
        }
        assertEquals("Servers left after the session closed: $left", emptyList<String>(), left)
        return JSONArray(left)
    }

    /** The running scrcpy servers' own command lines, without the shells that started them. */
    private fun serverProcesses(): List<String> =
        shell("ps -A -o ARGS").lines().map { it.trim() }
            .filter { it.startsWith("app_process") && "com.genymobile.scrcpy.Server " in it }
            .map { "$it " }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun intArgument(name: String): Int {
        val text = InstrumentationRegistry.getArguments().getString(name)
        assumeTrue("Opt in with $name: this check takes minutes", text != null)
        return requireNotNull(text).toInt().also { require(it > 0) }
    }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    /** A model that is on screen, decoding into a surface whose frames are taken and dropped. */
    private fun withVisibleModel(block: (MirrorViewModel) -> Unit) {
        val thread = HandlerThread("stream-endurance-frames").apply { start() }
        val reader = ImageReader.newInstance(1280, 1280, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("stream-endurance-live-test", model)
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
        val until = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("$description: timed out")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val TAG = "StreamEndurance"
        const val OBSERVE_EVERY_MS = 10_000L
        const val SAMPLE_EVERY_MS = 60_000L
        const val EVENT_EVERY_MS = 5 * 60_000L
        const val MAX_FAILURES = 20
    }
}
