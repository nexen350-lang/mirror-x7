package com.scrcpy.remote.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.session.SessionInputState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The precision pad inside the real mirror view, driven by real motion events: the cursor moves by
 * finger travel, presses land only on a tap or an explicit hold and always at the cursor, and a
 * held press is let go whenever the user's attention or the picture changes underneath it.
 */
@RunWith(AndroidJUnit4::class)
class PrecisionPadViewTest {
    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /** A 500x1000 capture filling a 1000x2000 view: two view pixels per video pixel. */
    private fun padView(): ScrcpyMirrorView = ScrcpyMirrorView(InstrumentationRegistry.getInstrumentation().targetContext).also {
        it.setVideoSize(500, 1000, 1)
        measureAndLayout(it, 1000, 2000)
        it.precisionPad = true
    }

    @Test fun aSlideMovesTheCursorAndATapPressesTheGenericFingerAtIt() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        assertEquals(500f to 1000f, view.precisionPadCursor)
        slide(view, 100f, 100f, 300f, 50f, start = 0)
        assertEquals(700f to 950f, view.precisionPadCursor)
        assertTrue("Moving the cursor presses nothing", touches.isEmpty())
        tap(view, 900f, 1900f, at = 1_000)
        assertEquals(
            listOf(Touch(DOWN, PAD, 350, 475, 1f, 500, 1000), Touch(UP, PAD, 350, 475, 0f, 500, 1000)),
            touches,
        )
    }

    @Test fun holdToDragKeepsOneRemotePressAcrossLiftedFingersUntilTheModeEnds() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        view.togglePrecisionPadDrag()
        assertTrue(view.precisionPadDragging)
        slide(view, 100f, 100f, 140f, 100f, start = 0)
        slide(view, 100f, 100f, 100f, 140f, start = 1_000)
        assertTrue("Lifting the finger keeps the press", view.precisionPadDragging)
        view.precisionPad = false
        assertFalse(view.precisionPadDragging)
        assertEquals(
            listOf(Touch(DOWN, PAD, 250, 500, 1f, 500, 1000), Touch(MOVE, PAD, 270, 500, 1f, 500, 1000),
                Touch(MOVE, PAD, 270, 520, 1f, 500, 1000), Touch(UP, PAD, 270, 520, 0f, 500, 1000)),
            touches,
        )
        assertNull("Direct touch shows no cursor", view.precisionPadCursor)
    }

    @Test fun twoFingersScrollAtTheCursorWithTheContentFollowingTheFingers() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        val scrolls = collectScrolls(view)
        val step = 48f * density
        val first = Pointer(0, 100f, 1_000f)
        val second = Pointer(1, 300f, 1_000f)
        send(view, MotionEvent.ACTION_DOWN, 0, 0, first)
        send(view, pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 0, 10, first, second)
        send(view, MotionEvent.ACTION_MOVE, 0, 20, first.copy(y = 1_000f - 2 * step), second.copy(y = 1_000f - 2 * step))
        send(view, MotionEvent.ACTION_MOVE, 0, 30, first.copy(x = 100f + step, y = 1_000f - 2 * step),
            second.copy(x = 300f + step, y = 1_000f - 2 * step))
        send(view, pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 0, 40,
            first.copy(x = 100f + step, y = 1_000f - 2 * step), second.copy(x = 300f + step, y = 1_000f - 2 * step))
        send(view, MotionEvent.ACTION_UP, 0, 50, first.copy(x = 100f + step, y = 1_000f - 2 * step))
        assertTrue("Scrolling presses nothing", touches.isEmpty())
        assertEquals(2, scrolls.size)
        assertEquals(Scroll(250, 500, 0f, -2f, 500, 1000), scrolls[0].rounded())
        assertEquals(Scroll(250, 500, -1f, 0f, 500, 1000), scrolls[1].rounded())
        assertEquals("The cursor stays put while scrolling", 500f to 1000f, view.precisionPadCursor)
    }

    @Test fun aMouseKeepsPointingDirectlyInPadMode() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        sendMouse(view, MotionEvent.ACTION_DOWN, 100f, 200f, MotionEvent.BUTTON_PRIMARY)
        sendMouse(view, MotionEvent.ACTION_UP, 100f, 200f, 0)
        assertEquals(listOf(DOWN to (50 to 100), UP to (50 to 100)), touches.map { it.action to (it.x to it.y) })
        assertTrue("A mouse is not the pad", touches.none { it.pointerId == PAD })
        assertEquals(500f to 1000f, view.precisionPadCursor)
    }

    @Test fun lockingHidesTheCursorStopsThePadAndLetsGoOfAHeldPress() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        view.togglePrecisionPadDrag()
        view.setInputState(SessionInputState(locked = true, generation = 1))
        assertFalse(view.precisionPadDragging)
        assertNull("A locked screen shows no cursor that could act", view.precisionPadCursor)
        touches.clear()
        slide(view, 100f, 100f, 300f, 300f, start = 0)
        tap(view, 10f, 10f, at = 1_000)
        view.togglePrecisionPadDrag()
        assertTrue("Nothing reaches the device while locked", touches.isEmpty())
        view.setInputState(SessionInputState(locked = false, generation = 2))
        assertEquals("The cursor did not move while locked", 500f to 1000f, view.precisionPadCursor)
    }

    @Test fun focusLossAndCancellationLetGoOfAHeldPress() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        view.togglePrecisionPadDrag()
        view.dispatchWindowFocusChanged(false)
        assertFalse(view.precisionPadDragging)
        view.dispatchWindowFocusChanged(true)
        view.togglePrecisionPadDrag()
        send(view, MotionEvent.ACTION_DOWN, 0, 0, Pointer(0, 10f, 10f))
        send(view, MotionEvent.ACTION_CANCEL, 0, 10, Pointer(0, 10f, 10f))
        assertFalse(view.precisionPadDragging)
        assertEquals(listOf(DOWN, UP, DOWN, UP), touches.map { it.action })
        assertTrue(touches.all { it.pointerId == PAD })
    }

    @Test fun aCaptureChangeForgetsTheHeldPressAndKeepsTheCursorsPlace() = onMainThread {
        val view = padView()
        val touches = collectTouches(view)
        view.togglePrecisionPadDrag()
        view.setVideoSize(1000, 500, 2)
        measureAndLayout(view, 1000, 2000)
        assertFalse(view.precisionPadDragging)
        assertEquals("The session lifts the press itself on a capture change", listOf(DOWN), touches.map { it.action })
        // Half way across and half way down the picture, now 1000x500 at y 750.
        assertEquals(500f to 1000f, view.precisionPadCursor)
        touches.clear()
        tap(view, 10f, 10f, at = 1_000)
        assertEquals(listOf(Touch(DOWN, PAD, 500, 250, 1f, 1000, 500), Touch(UP, PAD, 500, 250, 0f, 1000, 500)), touches)
    }

    @Test fun aMagnifiedPictureFollowsTheCursorWithoutDroppingAHeldPress() = onMainThread {
        val view = padView()
        view.setZoom(4f)
        measureAndLayout(view, 1000, 2000)
        val touches = collectTouches(view)
        assertEquals("Zoomed about the centre, the cursor stays in view", 500f to 1000f, view.precisionPadCursor)
        view.togglePrecisionPadDrag()
        // 600 view pixels at 8 view pixels per video pixel: 75 video pixels to the right.
        send(view, MotionEvent.ACTION_DOWN, 0, 0, Pointer(0, 100f, 100f))
        send(view, MotionEvent.ACTION_MOVE, 0, 20, Pointer(0, 700f, 100f))
        measureAndLayout(view, 1000, 2000)
        val (x, _) = requireNotNull(view.precisionPadCursor)
        val inset = minOf(48f * density, 250f)
        assertEquals("The picture moved so the cursor stays inside the view", 1000f - inset, x, 2f)
        assertTrue("Moving the picture does not drop the held press", view.precisionPadDragging)
        send(view, MotionEvent.ACTION_UP, 0, 400, Pointer(0, 700f, 100f))
        view.togglePrecisionPadDrag()
        assertEquals(listOf(DOWN to 250, MOVE to 325, UP to 325), touches.map { it.action to it.x })
    }

    @Test fun aPadPressLandsWhereADirectTouchAtTheTipWould() = onMainThread {
        val view = padView()
        view.setZoom(3f)
        measureAndLayout(view, 1000, 2000)
        val touches = collectTouches(view)
        slide(view, 100f, 100f, 237f, 171f, start = 0)
        tap(view, 10f, 10f, at = 1_000)
        val (tipX, tipY) = requireNotNull(view.precisionPadCursor)
        val pad = touches.first()
        touches.clear()
        view.precisionPad = false
        send(view, MotionEvent.ACTION_DOWN, 2_000, 2_000, Pointer(5, tipX, tipY))
        send(view, MotionEvent.ACTION_UP, 2_000, 2_050, Pointer(5, tipX, tipY))
        assertEquals(pad.x to pad.y, touches.first().x to touches.first().y)
    }

    @Test fun switchingModesNeedsAFreshGesture() = onMainThread {
        val view = ScrcpyMirrorView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.setVideoSize(500, 1000, 1)
        measureAndLayout(view, 1000, 2000)
        val touches = collectTouches(view)
        send(view, MotionEvent.ACTION_DOWN, 0, 0, Pointer(3, 100f, 100f))
        view.precisionPad = true
        send(view, MotionEvent.ACTION_MOVE, 0, 20, Pointer(3, 400f, 100f))
        send(view, MotionEvent.ACTION_UP, 0, 40, Pointer(3, 400f, 100f))
        assertEquals("The direct press is let go and the rest of its gesture dropped",
            listOf(DOWN to 3L, UP to 3L), touches.map { it.action to it.pointerId })
        assertEquals(500f to 1000f, view.precisionPadCursor)
        touches.clear()
        send(view, MotionEvent.ACTION_DOWN, 1_000, 1_000, Pointer(4, 100f, 100f))
        view.precisionPad = false
        send(view, MotionEvent.ACTION_MOVE, 1_000, 1_020, Pointer(4, 400f, 100f))
        send(view, MotionEvent.ACTION_UP, 1_000, 1_040, Pointer(4, 400f, 100f))
        assertTrue("Half a pad gesture does not turn into a direct one", touches.isEmpty())
    }

    @Test fun theCursorIsDrawnWithItsTipOnThePressedPixel() = onMainThread {
        val view = padView()
        val bitmap = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val (tipX, tipY) = requireNotNull(view.precisionPadCursor)
            val inside = bitmap.getPixel((tipX + 2 * density).toInt(), (tipY + 9 * density).toInt())
            val outside = bitmap.getPixel((tipX - 6 * density).toInt(), (tipY + 9 * density).toInt())
            val below = bitmap.getPixel((tipX + 2 * density).toInt(), (tipY + 30 * density).toInt())
            assertEquals("The arrow is drawn just below and right of its tip", Color.WHITE, inside)
            assertTrue("Nothing is drawn beside it", outside != Color.WHITE)
            assertTrue("It is cursor sized, ending about 20 dp below its tip", below != Color.WHITE)
        } finally {
            bitmap.recycle()
        }
        view.precisionPad = false
        val plain = Bitmap.createBitmap(1000, 2000, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(plain))
            assertTrue("Direct touch draws no cursor", (0 until 2000 step 7).none { y ->
                (0 until 1000 step 7).any { x -> plain.getPixel(x, y) == Color.WHITE }
            })
        } finally {
            plain.recycle()
        }
    }

    @Test fun theBarIsToldWhereTheCursorIsAndWhetherAPressIsHeld() = onMainThread {
        val view = padView()
        val reports = mutableListOf<Triple<Boolean, Float, Float>>()
        view.onPrecisionPadChanged = { dragging, x, y -> reports += Triple(dragging, x, y) }
        slide(view, 100f, 100f, 100f, 300f, start = 0)
        view.togglePrecisionPadDrag()
        view.precisionPad = false
        assertEquals(Triple(false, 500f, 1200f), reports.first { it.third == 1200f })
        assertTrue(reports.any { it.first && it.third == 1200f })
        val last = reports.last()
        assertFalse(last.first)
        assertTrue("Without the pad there is no cursor to avoid", last.second.isNaN() && last.third.isNaN())
    }

    private fun slide(view: ScrcpyMirrorView, fromX: Float, fromY: Float, toX: Float, toY: Float, start: Long) {
        send(view, MotionEvent.ACTION_DOWN, start, start, Pointer(0, fromX, fromY))
        send(view, MotionEvent.ACTION_MOVE, start, start + 50, Pointer(0, toX, toY))
        send(view, MotionEvent.ACTION_UP, start, start + 400, Pointer(0, toX, toY))
    }

    private fun tap(view: ScrcpyMirrorView, x: Float, y: Float, at: Long) {
        send(view, MotionEvent.ACTION_DOWN, at, at, Pointer(0, x, y))
        send(view, MotionEvent.ACTION_UP, at, at + 50, Pointer(0, x, y))
    }

    private fun send(view: ScrcpyMirrorView, action: Int, downTime: Long, time: Long, vararg pointers: Pointer): Boolean {
        val event = MotionEvent.obtain(
            downTime, time, action, pointers.size,
            pointers.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                .toTypedArray(),
            pointers.map { MotionEvent.PointerCoords().apply { x = it.x; y = it.y; pressure = 1f; size = 1f } }
                .toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun sendMouse(view: ScrcpyMirrorView, action: Int, x: Float, y: Float, buttons: Int) {
        val event = MotionEvent.obtain(
            0, 0, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
            arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }),
            0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun collectTouches(view: ScrcpyMirrorView): MutableList<Touch> =
        mutableListOf<Touch>().also { touches ->
            view.onTouch = { action, id, x, y, pressure, width, height, _, _ ->
                touches += Touch(action, id, x, y, pressure, width, height)
            }
        }

    private fun collectScrolls(view: ScrcpyMirrorView): MutableList<Scroll> =
        mutableListOf<Scroll>().also { scrolls ->
            view.onScroll = { x, y, horizontal, vertical, width, height ->
                scrolls += Scroll(x, y, horizontal, vertical, width, height)
            }
        }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    private data class Touch(
        val action: Int, val pointerId: Long, val x: Int, val y: Int,
        val pressure: Float, val width: Int, val height: Int,
    )

    private data class Scroll(val x: Int, val y: Int, val horizontal: Float, val vertical: Float, val width: Int, val height: Int) {
        /** To two decimals: the step is computed from a density, so the exact float can differ. */
        fun rounded() = copy(horizontal = round(horizontal), vertical = round(vertical))

        private fun round(value: Float) = (Math.round(value * 100f) / 100f) + 0f
    }

    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val PAD = PrecisionPad.POINTER_ID
    }
}
