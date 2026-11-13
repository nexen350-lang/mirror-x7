package com.scrcpy.remote.session

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionDiagnosticsResetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun disconnectDoesNotLeaveFramesFromTheRetiredSession() = withModel { model ->
        flow<VideoFrameStats>(model, "_videoFrames").value =
            VideoFrameStats(surfaceFps = 30, surfaceFrames = 500, skippedFrames = 100)
        model.disconnect()
        assertEquals(VideoFrameStats(), model.videoFrames.value)
    }

    @Test fun disconnectDoesNotLeaveLatencyFromTheRetiredSession() = withModel { model ->
        flow<LatencyStats>(model, "_videoLatency").value =
            LatencyStats(samples = 20, medianMillis = 14, p95Millis = 25, worstMillis = 30)
        model.disconnect()
        assertEquals(LatencyStats(), model.videoLatency.value)
    }

    @Test fun disconnectDoesNotLeaveAudioDiagnosticsFromTheRetiredSession() = withModel { model ->
        flow<MediaTimingDiagnostics>(model, "_mediaTiming").value =
            MediaTimingDiagnostics(audioQueuedMs = 80, outputQueuedMs = 40, underruns = 6, droppedFrames = 400)
        model.disconnect()
        assertEquals(MediaTimingDiagnostics(), model.mediaTiming.value)
    }

    @Test fun detailsWatchPausesWhenControllerIsNotVisible() = withModel { model ->
        model.onVisibilityChanged(true)
        model.setStatsWatched(true)
        assertTrue(flow<Boolean>(model, "_statsWatched").value)
        model.onVisibilityChanged(false)
        assertFalse("A retained dialog is not being watched while the Activity is stopped",
            flow<Boolean>(model, "_statsWatched").value)
        model.onVisibilityChanged(true)
        assertTrue("Returning to the still-open dialog resumes its watch",
            flow<Boolean>(model, "_statsWatched").value)
        model.setStatsWatched(false)
        model.onVisibilityChanged(false)
        model.onVisibilityChanged(true)
        assertFalse("A closed dialog must not resume on return",
            flow<Boolean>(model, "_statsWatched").value)
    }

    private fun withModel(assertions: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var scopeJob: Job
        try {
            instrumentation.runOnMainSync {
                val model = MirrorViewModel(app)
                scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])
                store.put("connection-diagnostics-test", model)
                assertions(model)
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            runBlocking { withTimeout(5_000) { scopeJob.join() } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(model: MirrorViewModel, name: String): MutableStateFlow<T> =
        MirrorViewModel::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(model) as MutableStateFlow<T>
        }
}
