package com.scrcpy.remote.session

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionQualitySamplingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun equalLiveFlowValuesStillProduceTicksAndClosingStopsTheSampler() = withFixture { fixture ->
        onMain {
            fixture.model.onVisibilityChanged(true)
            fixture.model.setStatsWatched(true)
            invokeSampler("startDiagnosticSampling", fixture)
        }
        waitUntil { fixture.model.qualityHistory.value.samples.size >= 2 }
        lateinit var closed: ConnectionQualitySnapshot
        onMain {
            assertEquals(VideoFrameStats(), fixture.model.videoFrames.value)
            val samples = fixture.model.qualityHistory.value.samples
            assertNotEquals(samples.first().elapsedMillis, samples.last().elapsedMillis)
            assertTrue(samples.all { it.renderedFps == null })
            fixture.model.setStatsWatched(false)
            closed = fixture.model.qualityHistory.value
            fixture.meter.rendered(System.nanoTime())
        }
        SystemClock.sleep(750)
        onMain {
            assertEquals("A closed panel adds no history ticks", closed, fixture.model.qualityHistory.value)
            assertEquals("Neither watcher is enabled, so live stats must not be sampled", 0L,
                fixture.model.videoFrames.value.surfaceFrames)
            fixture.model.diagnosticsEnabled = true
        }
        waitUntil { fixture.model.videoFrames.value.surfaceFrames == 1L }
        onMain {
            assertEquals("The overlay refreshes live stats, not the panel history", closed,
                fixture.model.qualityHistory.value)
        }
    }

    @Test fun hiddenPanelsPauseAndRebaseWhileOverlayOnlySamplingKeepsItsExistingBehavior() =
        withFixture { fixture ->
            lateinit var hidden: ConnectionQualitySnapshot
            onMain {
                fixture.model.onVisibilityChanged(true)
                fixture.model.setStatsWatched(true)
                fixture.meter.rendered(System.nanoTime())
                invokeSampler("sampleDiagnostics", fixture)
                fixture.model.onVisibilityChanged(false)
                hidden = fixture.model.qualityHistory.value
                assertFalse(hidden.collecting)
                repeat(20) { fixture.meter.skipped() }
                invokeSampler("sampleDiagnostics", fixture)
                assertEquals(hidden, fixture.model.qualityHistory.value)
                assertEquals(0L, fixture.model.videoFrames.value.skippedFrames)
                fixture.model.diagnosticsEnabled = true
                invokeSampler("sampleDiagnostics", fixture)
                assertEquals(20L, fixture.model.videoFrames.value.skippedFrames)
                assertEquals(hidden, fixture.model.qualityHistory.value)
                fixture.model.onVisibilityChanged(true)
            }
            nextSample(fixture)
            onMain {
                val resumed = fixture.model.qualityHistory.value
                assertTrue(resumed.collecting)
                assertEquals(2, resumed.samples.size)
                assertNull(resumed.samples.last().skippedDelta)
                assertNull(resumed.samples.last().intervalMillis)
                repeat(3) { fixture.meter.skipped() }
            }
            nextSample(fixture)
            onMain { assertEquals(3L, fixture.model.qualityHistory.value.samples.last().skippedDelta) }
        }

    @Test fun reopeningReadsTheDecoderAndClockInsteadOfReusingStalePublishedFlows() =
        withFixture { fixture ->
            onMain {
                fixture.model.onVisibilityChanged(true)
                fixture.model.setStatsWatched(true)
                fixture.meter.rendered(System.nanoTime())
                invokeSampler("sampleDiagnostics", fixture)
                fixture.model.setStatsWatched(false)
                flow<VideoFrameStats>(fixture.model, "_videoFrames").value =
                    VideoFrameStats(surfaceFps = 999, surfaceFrames = 999_999, skippedFrames = 999_999)
                flow<LatencyStats>(fixture.model, "_videoLatency").value =
                    LatencyStats(samples = 999, p95Millis = 999)
                flow<MediaTimingDiagnostics>(fixture.model, "_mediaTiming").value =
                    MediaTimingDiagnostics(audioQueuedMs = 999, outputQueuedMs = 999, underruns = 999)
                repeat(20) { fixture.meter.skipped() }
                fixture.model.setStatsWatched(true)
            }
            nextSample(fixture)
            onMain {
                val samples = fixture.model.qualityHistory.value.samples
                assertEquals(2, samples.size)
                val fps = samples.last().renderedFps
                assertTrue(fps != null && fps in 0..1)
                assertNull(samples.last().clientP95Millis)
                assertNull(samples.last().audioQueuedMillis)
                assertNull(samples.last().skippedDelta)
                assertEquals(20L, fixture.model.videoFrames.value.skippedFrames)
                assertEquals(LatencyStats(), fixture.model.videoLatency.value)
                assertEquals(fixture.clock.diagnostics(), fixture.model.mediaTiming.value)
            }
        }

    @Test fun aRetiredGenerationOrClockCannotRepopulateClearedHistory() = withFixture { fixture ->
        onMain {
            fixture.model.onVisibilityChanged(true)
            fixture.model.setStatsWatched(true)
            fixture.meter.rendered(System.nanoTime())
            repeat(10) { fixture.meter.skipped() }
            invokeSampler("sampleDiagnostics", fixture)
            fixture.model.disconnect()
            val cleared = fixture.model.qualityHistory.value
            assertTrue(cleared.samples.isEmpty())
            invokeSampler("sampleDiagnostics", fixture)
            assertEquals(cleared, fixture.model.qualityHistory.value)
            assertEquals(VideoFrameStats(), fixture.model.videoFrames.value)

            val newClock = AudioPresentationClock()
            field(fixture.model, "mediaClock").set(fixture.model, newClock)
            val newToken = generations(fixture.model).token()
            invokeSampler("sampleDiagnostics", fixture, token = newToken)
            invokeSampler("sampleDiagnostics", fixture, clock = newClock)
            assertEquals(cleared, fixture.model.qualityHistory.value)
            invokeSampler("sampleDiagnostics", fixture, clock = newClock, token = newToken)
            val first = fixture.model.qualityHistory.value.samples.single()
            assertNull(first.skippedDelta)
            assertNull(first.underrunDelta)
            assertNull(first.renderedFps)
        }
    }

    @Test fun requestedAudioIsNotEvidenceOfPlayingOrAvailableUnderrunMeasurements() = withFixture { fixture ->
        onMain {
            fixture.model.onVisibilityChanged(true)
            fixture.model.setStatsWatched(true)
            observeAudio(fixture.clock, underruns = 10)
            invokeSampler("sampleDiagnostics", fixture)
            assertNull(fixture.model.qualityHistory.value.samples.last().audioQueuedMillis)
            flow<AudioState>(fixture.model, "_audioState").value = AudioState(playing = true)
        }
        nextSample(fixture)
        onMain {
            val playing = fixture.model.qualityHistory.value.samples.last()
            assertEquals(10, playing.audioQueuedMillis)
            assertNull(playing.underrunDelta)
            observeAudio(fixture.clock, underruns = 12)
        }
        nextSample(fixture)
        onMain {
            assertEquals(2, fixture.model.qualityHistory.value.samples.last().underrunDelta)
            flow<AudioState>(fixture.model, "_audioState").value = AudioState(error = "Playback failed")
        }
        nextSample(fixture)
        onMain {
            assertNull(fixture.model.qualityHistory.value.samples.last().audioQueuedMillis)
            assertNull(fixture.model.qualityHistory.value.samples.last().underrunDelta)
            flow<AudioState>(fixture.model, "_audioState").value = AudioState(playing = true)
            flow<MirrorSettings?>(fixture.model, "_activeSettings").value = MirrorSettings(audioEnabled = false)
        }
        nextSample(fixture)
        onMain { assertNull(fixture.model.qualityHistory.value.samples.last().audioQueuedMillis) }
    }

    private fun observeAudio(clock: AudioPresentationClock, underruns: Int) {
        clock.observe(
            PcmPresentation(0, System.nanoTime(), timestampAvailable = false, underruns = underruns),
            PcmPlaybackStats(queuedFrames = 480, writtenFrames = 0, droppedFrames = 0),
            System.nanoTime(),
        )
    }

    private fun nextSample(fixture: Fixture) {
        SystemClock.sleep(2)
        onMain { invokeSampler("sampleDiagnostics", fixture) }
    }

    private fun invokeSampler(
        name: String,
        fixture: Fixture,
        clock: AudioPresentationClock = fixture.clock,
        token: Long = fixture.token,
    ) {
        MirrorViewModel::class.java.getDeclaredMethod(
            name, AudioPresentationClock::class.java, java.lang.Long.TYPE,
        ).apply { isAccessible = true }.invoke(fixture.model, clock, token)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Diagnostic sampler did not reach the expected state")
    }

    private data class Fixture(
        val model: MirrorViewModel,
        val clock: AudioPresentationClock,
        val meter: VideoFrameMeter,
        val token: Long,
    )

    private fun withFixture(assertions: (Fixture) -> Unit) {
        val store = ViewModelStore()
        lateinit var scopeJob: Job
        lateinit var fixture: Fixture
        try {
            onMain {
                val model = MirrorViewModel(app)
                scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
                store.put("connection-quality-test", model)
                val clock = AudioPresentationClock()
                val decoder = VideoDecoder(
                    object : PacketSource {
                        override val codecId = ScrcpyCodecs.VIDEO_H264
                        override fun readPacket(): StreamPacket = error("No media worker is started by this fixture")
                    },
                    onVideoSizeChanged = { _, _, _ -> },
                    onError = { throw it },
                    onDecodedFormat = {},
                )
                field(model, "mediaClock").set(model, clock)
                field(model, "video").set(model, decoder)
                flow<MirrorSettings?>(model, "_activeSettings").value = MirrorSettings(audioEnabled = true)
                val meter = VideoDecoder::class.java.getDeclaredField("meter").run {
                    isAccessible = true
                    get(decoder) as VideoFrameMeter
                }
                fixture = Fixture(model, clock, meter, generations(model).token())
            }
            assertions(fixture)
        } finally {
            onMain { store.clear() }
            runBlocking { withTimeout(5_000) { scopeJob.join() } }
        }
    }

    private fun generations(model: MirrorViewModel) = field(model, "generations").get(model) as SessionGeneration

    private fun field(model: MirrorViewModel, name: String) =
        model.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(model: MirrorViewModel, name: String) =
        field(model, name).get(model) as MutableStateFlow<T>

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
}
