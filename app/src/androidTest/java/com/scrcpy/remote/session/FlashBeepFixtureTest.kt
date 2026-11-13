package com.scrcpy.remote.session

import android.media.AudioFormat
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** A repeatable flash/beep source for camera/loopback measurement, not a claim of physical lip-sync. */
@RunWith(AndroidJUnit4::class)
class FlashBeepFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun flashAndBeepUseTheMeasuredAudioPresentationClock() {
        val flash = mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.fillMaxSize().background(if (flash.value) Color.White else Color.Black))
            }
        }
        val rate = 48_000
        val clock = AudioPresentationClock(true)
        val error = AtomicReference<Exception>()
        val playback = PcmPlayback(rate, 4, 50,
            { AudioTrackOutput(rate, 2, AudioFormat.ENCODING_PCM_16BIT) }, { false }, {}, error::set,
            clock = clock)
        val start = System.nanoTime()
        val producer = Thread {
            repeat(250) { packet ->
                val target = start + packet * 20_000_000L
                val wait = (target - System.nanoTime()) / 1_000_000
                if (wait > 0) SystemClock.sleep(wait)
                val samples = ByteBuffer.allocate(960 * 4).order(ByteOrder.nativeOrder())
                repeat(960) { index ->
                    val frame = packet * 960 + index
                    val value = if (frame % 24_000 < 960) (sin(2 * Math.PI * 880 * frame / rate) * 4_000).toInt() else 0
                    samples.putShort(value.toShort()).putShort(value.toShort())
                }
                playback.offer(samples.array(), samples.capacity(), packet * 20_000L)
            }
        }
        var measuredFlashes = 0
        try {
            playback.start()
            producer.start()
            for (beep in 2..8) {
                val sourcePts = beep * 500_000L
                var scheduled: Long? = null
                val deadline = start + (beep * 500L + 400) * 1_000_000
                while (scheduled == null && System.nanoTime() < deadline && error.get() == null) {
                    val now = System.nanoTime()
                    if (clock.diagnostics().clockReady && now >= start + (beep * 500L - 100) * 1_000_000) {
                        when (val plan = clock.videoPresentation(sourcePts, now)) {
                            is VideoPresentation.At -> scheduled = plan.nanoTime
                            VideoPresentation.Immediate -> Unit
                        }
                    }
                    if (scheduled == null) SystemClock.sleep(2)
                }
                if (scheduled != null) {
                    val wait = (scheduled - System.nanoTime()) / 1_000_000
                    if (wait > 0) SystemClock.sleep(wait)
                    compose.runOnUiThread { flash.value = true }
                    measuredFlashes++
                    SystemClock.sleep(35)
                    compose.runOnUiThread { flash.value = false }
                }
            }
            producer.join(6_000)
            assertFalse(producer.isAlive)
            assertNull(error.get())
            assertTrue("Hardware presentation timestamps never became usable", measuredFlashes >= 3)
            assertTrue(playback.stats().writtenFrames > rate)
        } finally {
            playback.close()
            producer.interrupt()
            producer.join(2_000)
            assertTrue(playback.awaitStopped(3_000))
        }
    }
}
