package com.scrcpy.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * The precision pad's behaviour: a cursor moved by how far a finger slides, pressed only on a tap
 * or an explicit hold, and always pressed on the video pixel drawn under its tip.
 */
class PrecisionPadTest {
    private class Recorder : PrecisionPad.Sink {
        data class Touch(val action: Int, val x: Int, val y: Int, val viewX: Float, val viewY: Float)
        data class Scroll(val x: Int, val y: Int, val horizontal: Float, val vertical: Float)

        val touches = mutableListOf<Touch>()
        val scrolls = mutableListOf<Scroll>()

        override fun touch(action: Int, x: Int, y: Int, viewX: Float, viewY: Float) {
            touches += Touch(action, x, y, viewX, viewY)
        }

        override fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float) {
            scrolls += Scroll(x, y, horizontal, vertical)
        }
    }

    private fun pad(recorder: Recorder = Recorder()) = PrecisionPad(SLOP, STEP, recorder)

    /** Two view pixels per video pixel, filling the view exactly. */
    private val doubled = VideoLayout.fitCentered(1000, 2000, 500, 1000)

    @Test fun theCursorStartsInTheMiddleOfWhatIsVisible() {
        val pad = pad()
        assertFalse(pad.placed)
        assertNull(pad.cursorView(doubled))
        pad.place(doubled, 1000, 2000)
        assertEquals(250f, pad.cursorX, 0.01f)
        assertEquals(500f, pad.cursorY, 0.01f)

        // Magnified and panned, the middle of what is on screen, not of the whole picture.
        val zoomed = VideoLayout.fitCentered(1000, 2000, 500, 1000, zoom = 4f, panX = 1000f, panY = -2000f)
        val fresh = pad().also { it.place(zoomed, 1000, 2000) }
        val (x, y) = requireNotNull(fresh.cursorView(zoomed))
        assertEquals(500f, x, 1f)
        assertEquals(1000f, y, 1f)

        // A cursor that already has a place keeps it.
        pad.place(zoomed, 1000, 2000)
        assertEquals(250f, pad.cursorX, 0.01f)
    }

    @Test fun slidingMovesTheCursorAsFarOnScreenAsTheFingerTravelsTimesTheSpeed() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        val start = requireNotNull(pad.cursorView(doubled))
        pad.down(100f, 100f, 0)
        pad.move(160f, 140f, doubled)
        pad.move(200f, 100f, doubled)
        pad.up(1_000, doubled)
        val end = requireNotNull(pad.cursorView(doubled))
        assertEquals(start.first + 100f, end.first, 0.01f)
        assertEquals(start.second, end.second, 0.01f)
        assertTrue("Moving the cursor presses nothing", recorder.touches.isEmpty())

        pad.speed = 2f
        pad.down(0f, 0f, 2_000)
        pad.move(0f, 50f, doubled)
        pad.up(3_000, doubled)
        assertEquals(end.second + 100f, requireNotNull(pad.cursorView(doubled)).second, 0.01f)

        // Magnified, the same finger travel covers fewer video pixels: finer control.
        val zoomed = VideoLayout.fitCentered(1000, 2000, 500, 1000, zoom = 4f)
        val before = pad.cursorX
        pad.speed = 1f
        pad.down(0f, 0f, 4_000)
        pad.move(80f, 0f, zoomed)
        pad.up(5_000, zoomed)
        assertEquals(before + 80f / zoomed.scaleX, pad.cursorX, 0.01f)
        assertTrue(recorder.touches.isEmpty())
    }

    @Test fun aTapPressesAndReleasesTheVideoPixelUnderTheCursorTip() {
        val recorder = Recorder()
        // 3.75 view pixels per video pixel, as a 1080-wide phone shows a 288-wide capture.
        val layout = VideoLayout.fitCentered(1080, 2400, 288, 640)
        val pad = pad(recorder).also { it.place(layout, 1080, 2400) }
        pad.down(500f, 500f, 0)
        pad.move(537f, 517f, layout)
        pad.up(2_000, layout)
        assertTrue(recorder.touches.isEmpty())

        pad.down(10f, 10f, 3_000)
        pad.up(3_100, layout)
        val (viewX, viewY) = requireNotNull(pad.cursorView(layout))
        val press = Recorder.Touch(ACTION_DOWN, layout.toDeviceX(viewX), layout.toDeviceY(viewY), viewX, viewY)
        assertEquals(listOf(press, press.copy(action = ACTION_UP)), recorder.touches)
        assertEquals(floor(pad.cursorX).toInt(), press.x)
        assertEquals(floor(pad.cursorY).toInt(), press.y)
    }

    @Test fun aTapLeavesTheCursorWhereItWasAndAHeldOrWanderingTouchIsNotATap() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        val x = pad.cursorX
        val y = pad.cursorY
        // A finger always shifts a little while tapping; that is not aim.
        pad.down(100f, 100f, 0)
        pad.move(110f, 108f, doubled)
        pad.up(120, doubled)
        assertEquals(x, pad.cursorX, 0f)
        assertEquals(y, pad.cursorY, 0f)
        assertEquals(listOf(ACTION_DOWN, ACTION_UP), recorder.touches.map { it.action })
        assertEquals(doubled.toDeviceX(doubled.toViewX(x)), recorder.touches[0].x)
        recorder.touches.clear()

        pad.down(100f, 100f, 1_000)
        pad.up(1_000 + PrecisionPad.TAP_TIMEOUT_MS + 1, doubled)
        pad.down(100f, 100f, 2_000)
        pad.move(200f, 100f, doubled)
        pad.move(100f, 100f, doubled)
        pad.up(2_050, doubled)
        assertTrue("A long touch or one that wandered is not a click", recorder.touches.isEmpty())
    }

    @Test fun holdToDragSendsOneRealPressThatFollowsTheCursorUntilReleased() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        pad.toggleDrag(doubled)
        assertTrue(pad.dragging)
        pad.down(0f, 0f, 0)
        pad.move(1f, 0f, doubled) // Half a video pixel: the press is still on the same pixel.
        pad.move(41f, 0f, doubled)
        pad.move(41f, 1f, doubled)
        pad.up(1_000, doubled)
        assertTrue("Lifting the finger to reach further keeps the press", pad.dragging)
        pad.down(0f, 0f, 2_000)
        pad.move(0f, 20f, doubled)
        pad.up(3_000, doubled)
        pad.down(5f, 5f, 4_000)
        pad.up(4_050, doubled) // A tap drops what is being dragged.
        assertFalse(pad.dragging)
        assertEquals(
            listOf(ACTION_DOWN to (250 to 500), ACTION_MOVE to (270 to 500), ACTION_MOVE to (270 to 510),
                ACTION_UP to (270 to 510)),
            recorder.touches.map { it.action to (it.x to it.y) },
        )

        recorder.touches.clear()
        pad.toggleDrag(doubled)
        pad.toggleDrag(doubled)
        assertEquals(listOf(ACTION_DOWN, ACTION_UP), recorder.touches.map { it.action })
        assertFalse(pad.dragging)
    }

    @Test fun aTapThatDropsAHeldPressDropsItWhereTheCursorWasAimed() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        pad.speed = 2f
        pad.toggleDrag(doubled)
        pad.down(0f, 0f, 0)
        // A finger shifting within the slop while it taps: the press follows it as it would any
        // drag, but the tap is not aim, so the press goes back before it is let go.
        pad.move(12f, 0f, doubled)
        pad.up(100, doubled)
        assertFalse(pad.dragging)
        assertEquals(
            listOf(ACTION_DOWN to (250 to 500), ACTION_MOVE to (262 to 500), ACTION_MOVE to (250 to 500),
                ACTION_UP to (250 to 500)),
            recorder.touches.map { it.action to (it.x to it.y) },
        )
        assertEquals(250f, pad.cursorX, 0.01f)
    }

    @Test fun twoFingersScrollAtTheCursorAndTheContentFollowsTheFingers() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        val x = pad.cursorX
        val y = pad.cursorY
        pad.down(100f, 500f, 0)
        pad.scrollAnchor(150f, 500f)
        assertTrue(pad.scrolling)
        pad.move(150f, 300f, doubled) // Fingers up by two steps: show what is further down.
        pad.move(250f, 300f, doubled) // Right by one step: show what is further left.
        pad.scrollAnchor(250f, 320f) // One finger lifts; the centre jumps, nothing scrolls.
        pad.move(250f, 320f, doubled)
        pad.up(100, doubled)
        assertFalse(pad.scrolling)
        assertTrue("Scrolling presses nothing", recorder.touches.isEmpty())
        assertEquals(x, pad.cursorX, 0f)
        assertEquals(y, pad.cursorY, 0f)
        assertEquals(
            listOf(Recorder.Scroll(250, 500, 0f, -2f), Recorder.Scroll(250, 500, -1f, 0f)),
            recorder.scrolls,
        )

        // One message carries at most what the protocol can.
        recorder.scrolls.clear()
        pad.down(0f, 0f, 1_000)
        pad.scrollAnchor(0f, 0f)
        pad.move(0f, -100_000f, doubled)
        assertEquals(listOf(-16f), recorder.scrolls.map { it.vertical })
    }

    @Test fun theCursorStaysOnThePictureAndKeepsItsPlaceWhenTheCaptureChangesSize() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        pad.down(0f, 0f, 0)
        pad.move(-5_000f, 9_000f, doubled)
        pad.up(1_000, doubled)
        assertEquals(0f, pad.cursorX, 0f)
        assertTrue(pad.cursorY > 999f && pad.cursorY < 1000f)
        pad.down(0f, 0f, 2_000)
        pad.up(2_050, doubled)
        assertEquals(listOf(0 to 999, 0 to 999), recorder.touches.map { it.x to it.y })

        recorder.touches.clear()
        pad.toggleDrag(doubled)
        pad.resize(500, 1000, 1000, 500)
        assertFalse(pad.dragging)
        assertEquals("The session lifts the press itself on a capture change",
            listOf(ACTION_DOWN), recorder.touches.map { it.action })
        assertEquals(0f, pad.cursorX, 0f)
        assertTrue(pad.cursorY > 499f && pad.cursorY < 500f)
    }

    @Test fun cancellationOrLeavingLiftsAHeldPressWhereItLastWas() {
        val recorder = Recorder()
        val pad = pad(recorder).also { it.place(doubled, 1000, 2000) }
        pad.toggleDrag(doubled)
        pad.down(0f, 0f, 0)
        pad.move(40f, 0f, doubled)
        pad.cancel()
        assertFalse(pad.dragging)
        pad.up(10, doubled) // The cancelled gesture cannot become a tap afterwards.
        assertEquals(listOf(ACTION_DOWN to 250, ACTION_MOVE to 270, ACTION_UP to 270),
            recorder.touches.map { it.action to it.x })

        recorder.touches.clear()
        pad.toggleDrag(doubled)
        pad.release()
        pad.release()
        pad.toggleDrag(doubled)
        pad.forgetDrag()
        assertFalse(pad.dragging)
        assertEquals(listOf(ACTION_DOWN, ACTION_UP, ACTION_DOWN), recorder.touches.map { it.action })
    }

    @Test fun nothingIsPressedWithoutAPictureToPressOn() {
        val recorder = Recorder()
        val pad = pad(recorder)
        val empty = VideoLayout.fitCentered(1000, 2000, 0, 0)
        pad.place(empty, 1000, 2000)
        pad.toggleDrag(empty)
        pad.down(0f, 0f, 0)
        pad.up(10, empty)
        assertFalse(pad.placed)
        assertFalse(pad.dragging)
        assertTrue(recorder.touches.isEmpty())
    }

    @Test fun theDrawnTipAndThePressedPixelAgreeAcrossSizesZoomPanAndRotation() {
        val random = Random(270)
        repeat(2_000) { round ->
            val viewWidth = random.nextInt(200, 2_000)
            val viewHeight = random.nextInt(200, 2_400)
            var videoWidth = random.nextInt(100, 2_400)
            var videoHeight = random.nextInt(100, 2_400)
            if (random.nextBoolean()) videoWidth = videoHeight.also { videoHeight = videoWidth }
            val layout = VideoLayout.fitCentered(viewWidth, viewHeight, videoWidth, videoHeight,
                zoom = 1f + random.nextFloat() * 7f,
                panX = random.nextFloat() * 4_000f - 2_000f, panY = random.nextFloat() * 4_000f - 2_000f)
            val recorder = Recorder()
            val pad = pad(recorder).also { it.place(layout, viewWidth, viewHeight) }
            pad.speed = PrecisionPad.SPEEDS[random.nextInt(PrecisionPad.SPEEDS.size)]
            pad.down(0f, 0f, 0)
            pad.move(random.nextFloat() * 4_000f - 2_000f, random.nextFloat() * 4_000f - 2_000f, layout)
            pad.up(1_000, layout)
            pad.down(0f, 0f, 2_000)
            pad.up(2_010, layout)
            val press = recorder.touches.first()
            val (tipX, tipY) = requireNotNull(pad.cursorView(layout))
            // The very pixel a direct touch at the tip would select, through the same rectangle.
            assertEquals("round $round", layout.toDeviceX(tipX), press.x)
            assertEquals("round $round", layout.toDeviceY(tipY), press.y)
            assertEquals(tipX, press.viewX, 0f)
            assertEquals(tipY, press.viewY, 0f)
            assertTrue("round $round", press.x in 0 until videoWidth && press.y in 0 until videoHeight)
            assertTrue("round $round: ${press.x} vs ${pad.cursorX}", abs(press.x - floor(pad.cursorX)) <= 1f)
            assertTrue("round $round: ${press.y} vs ${pad.cursorY}", abs(press.y - floor(pad.cursorY)) <= 1f)
        }
    }

    @Test fun aMagnifiedPictureIsShiftedJustEnoughToKeepTheCursorInView() {
        val pad = pad()
        val fitted = VideoLayout.fitCentered(1000, 1000, 1000, 1000)
        pad.place(fitted, 1000, 1000)
        assertEquals(0f to 0f, pad.revealShift(fitted, 1000, 1000, 50f))
        val zoomed = VideoLayout.fitCentered(1000, 1000, 1000, 1000, zoom = 4f)
        assertEquals(0f to 0f, pad.revealShift(zoomed, 1000, 1000, 50f))
        pad.down(0f, 0f, 0)
        pad.move(2_000f, 0f, zoomed)
        val (shiftX, shiftY) = pad.revealShift(zoomed, 1000, 1000, 50f)
        assertEquals(950f - requireNotNull(pad.cursorView(zoomed)).first, shiftX, 0.01f)
        assertEquals(0f, shiftY, 0f)
        // The layout's own limit stops the picture at its edge, which still shows the cursor.
        val shifted = VideoLayout.fitCentered(1000, 1000, 1000, 1000, zoom = 4f, panX = shiftX)
        assertTrue(requireNotNull(pad.cursorView(shifted)).first in 0f..1000f)
    }

    @Test fun speedStepsCycleAndAnUnknownValueMovesToTheNextStep() {
        assertEquals(1f, PrecisionPad.DEFAULT_SPEED, 0f)
        assertEquals(1.5f, PrecisionPad.nextSpeed(1f), 0f)
        assertEquals(2f, PrecisionPad.nextSpeed(1.5f), 0f)
        assertEquals(0.5f, PrecisionPad.nextSpeed(2f), 0f)
        assertEquals(1f, PrecisionPad.nextSpeed(0.5f), 0f)
        assertEquals(1f, PrecisionPad.nextSpeed(0.7f), 0f)
    }

    @Test fun theBarGetsOutOfTheCursorsWay() {
        assertTrue(padBarCoversCursor(1_950f, 1_900f, 2_000f, 24f))
        assertTrue(padBarCoversCursor(1_880f, 1_900f, 2_000f, 24f))
        assertFalse(padBarCoversCursor(1_800f, 1_900f, 2_000f, 24f))
        assertFalse(padBarCoversCursor(Float.NaN, 1_900f, 2_000f, 24f))
        assertFalse("A bar not laid out yet covers nothing", padBarCoversCursor(10f, 0f, 0f, 24f))
        // At the bottom of a 2000-pixel screen it moves to the top, and back again from there.
        assertTrue(padBarShouldMove(1_950f, 1_900f, 2_000f, 2_000f, 24f))
        assertTrue(padBarShouldMove(40f, 0f, 100f, 2_000f, 24f))
        assertFalse(padBarShouldMove(1_000f, 1_900f, 2_000f, 2_000f, 24f))
        assertFalse("A bar covering most of the screen stays put",
            padBarShouldMove(1_000f, 400f, 2_000f, 2_000f, 24f))
        // Judged by where the bar was last laid out, whichever edge that was.
        assertTrue(padBarAtTop(1_950f, 1_900f, 2_000f, 2_000f, 24f, laidOutAtTop = false))
        assertFalse(padBarAtTop(40f, 0f, 100f, 2_000f, 24f, laidOutAtTop = true))
        assertFalse(padBarAtTop(1_000f, 1_900f, 2_000f, 2_000f, 24f, laidOutAtTop = false))
        assertTrue(padBarAtTop(1_000f, 0f, 100f, 2_000f, 24f, laidOutAtTop = true))
        assertTrue("Not laid out yet, it stays where it is", padBarAtTop(10f, 0f, 0f, 0f, 24f, laidOutAtTop = true))
        // A second check before the move is laid out sees the same old bounds and the same edge
        // they were measured at, so it agrees with the first rather than sending the bar back.
        val first = padBarAtTop(1_950f, 1_900f, 2_000f, 2_000f, 24f, laidOutAtTop = false)
        assertEquals(first, padBarAtTop(1_960f, 1_900f, 2_000f, 2_000f, 24f, laidOutAtTop = false))
    }

    private companion object {
        const val SLOP = 16f
        const val STEP = 100f
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
    }
}
