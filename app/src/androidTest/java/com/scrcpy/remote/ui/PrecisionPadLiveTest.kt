package com.scrcpy.remote.ui

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.session.MirrorSessionService
import com.scrcpy.remote.session.MirrorUiState
import com.scrcpy.remote.session.MirrorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The precision pad against a real scrcpy server, through the loopback adbd of a disposable
 * emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * The pad's own state machine drives a real session while a debug activity is in front: its tap
 * has to click one small button among touching neighbours, and two fingers moving up have to
 * scroll the content down, as dragging it would. The mirror screen itself is not shown, so the
 * device's injected input cannot loop back into the pad.
 */
@RunWith(AndroidJUnit4::class)
class PrecisionPadLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun aPadTapClicksOneSmallTargetAndTwoFingersScrollTheWayTheyMove() {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        val port = requireNotNull(text).toInt().also { require(it in 1..65535) }
        val target = instrumentation.startActivitySync(Intent(app, PadTargetActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PadTargetActivity
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        main {
            model = MirrorViewModel(app)
            store.put("pad-live-test", model)
            model.onVisibilityChanged(true)
            model.connect(HOST, port, MirrorSettings(audioEnabled = false, backgroundAudio = false, autoReconnect = false))
        }
        try {
            val connected = await("The live session reports its capture size", model) {
                model.state.value as? MirrorUiState.Connected
            }
            val videoWidth = connected.videoWidth
            val videoHeight = connected.videoHeight
            val display = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                app.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it)
            }
            // Any valid local layout will do: the pad's presses are in video pixels.
            val layout = VideoLayout.fitCentered(1080, 2400, videoWidth, videoHeight)
            val pad = PrecisionPad(touchSlop = 10f, scrollStep = 100f, sink = object : PrecisionPad.Sink {
                override fun touch(action: Int, x: Int, y: Int, viewX: Float, viewY: Float) = main {
                    model.sendTouch(action, PrecisionPad.POINTER_ID, x, y,
                        if (action == MirrorViewModel.ACTION_UP) 0f else 1f, videoWidth, videoHeight)
                }

                override fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float) = main {
                    model.sendScroll(x, y, horizontal, vertical, videoWidth, videoHeight)
                }
            })
            pad.place(layout, 1080, 2400)
            var time = SystemClock.uptimeMillis()

            /** Slides the cursor onto the middle of [screen] pixels on the device. */
            fun aimAt(screenX: Float, screenY: Float) {
                val (fromX, fromY) = requireNotNull(pad.cursorView(layout))
                val toX = layout.toViewX(screenX * videoWidth / display.widthPixels)
                val toY = layout.toViewY(screenY * videoHeight / display.heightPixels)
                pad.down(0f, 0f, time)
                pad.move((toX - fromX) / pad.speed, (toY - fromY) / pad.speed, layout)
                pad.up(time + 1_000, layout)
                time += 2_000
            }

            var centre = FloatArray(2)
            main {
                val cell = target.grid.getChildAt(PadTargetActivity.CENTRE)
                val origin = IntArray(2).also { cell.getLocationOnScreen(it) }
                centre = floatArrayOf(origin[0] + cell.width / 2f, origin[1] + cell.height / 2f)
            }
            aimAt(centre[0], centre[1])
            pad.down(5f, 5f, time)
            pad.up(time + 50, layout)
            time += 2_000
            await("The pad's tap clicks the target", model) { target.clicks[PadTargetActivity.CENTRE].takeIf { it > 0 } }
            SystemClock.sleep(300)
            assertEquals("Exactly one click, on the target and none of its neighbours",
                listOf(0, 0, 0, 0, 1, 0, 0, 0, 0), target.clicks.toList())

            var middle = FloatArray(2)
            main {
                val origin = IntArray(2).also { target.scroller.getLocationOnScreen(it) }
                middle = floatArrayOf(origin[0] + target.scroller.width / 2f, origin[1] + target.scroller.height / 2f)
            }
            aimAt(middle[0], middle[1])
            fun twoFingers(dy: Float) {
                pad.down(100f, 600f, time)
                pad.scrollAnchor(150f, 600f)
                pad.move(150f, 600f + dy, layout)
                pad.up(time + 500, layout)
                time += 2_000
            }
            twoFingers(-300f)
            val down = await("Fingers moving up scroll the content down", model) {
                var scrolled = 0
                main { scrolled = target.scroller.scrollY }
                scrolled.takeIf { it > 0 }
            }
            twoFingers(200f)
            await("Fingers moving down scroll it back up", model) {
                var scrolled = 0
                main { scrolled = target.scroller.scrollY }
                scrolled.takeIf { it < down }
            }
            assertEquals("Scrolling clicked nothing", 1, target.clicks.sum())
            assertTrue("The pad held nothing afterwards", !pad.dragging)
        } finally {
            main {
                model.disconnect()
                store.clear()
                target.finish()
            }
            MirrorSessionService.stop(app)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T : Any> await(description: String, model: MirrorViewModel, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("$description: timed out in ${model.state.value}")
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
