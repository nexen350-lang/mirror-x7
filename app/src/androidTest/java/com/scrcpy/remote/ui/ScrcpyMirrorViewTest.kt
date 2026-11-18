package com.scrcpy.remote.ui

import android.os.SystemClock
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
import kotlin.math.floor

@RunWith(AndroidJUnit4::class)
class ScrcpyMirrorViewTest {
    @Test fun captureRotationCannotStartANewPointerInsideAnOldPhysicalGesture() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360, 1)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        val first = Pointer(7, 100f, 100f)
        send(view, MotionEvent.ACTION_DOWN, first)
        view.setVideoSize(360, 640, 2)
        measureAndLayout(view, 640, 360)
        touches.clear()
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first, Pointer(19, 200f, 200f))
        send(view, MotionEvent.ACTION_MOVE, first, Pointer(19, 210f, 210f))
        send(view, MotionEvent.ACTION_UP, first)
        assertTrue("A newly added finger cannot revive a gesture invalidated by rotation", touches.isEmpty())
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_UP, first)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touches.map { it.action })
    }

    @Test fun lockingReleasesMultiTouchAndConsumesAllRemoteMouseAndTouchInput() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        val first = Pointer(7, 100f, 100f)
        val second = Pointer(19, 200f, 200f)
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
        touches.clear()
        view.setInputState(SessionInputState(true, 1))
        assertEquals(listOf(MotionEvent.ACTION_UP to 7L, MotionEvent.ACTION_UP to 19L),
            touches.map { it.action to it.pointerId })
        touches.clear()
        view.onMouseButton = { throw AssertionError("Locked mouse navigation reached the device") }
        view.onScroll = { _, _, _, _, _, _ -> throw AssertionError("Locked scrolling reached the device") }
        send(view, MotionEvent.ACTION_MOVE, first, second)
        send(view, MotionEvent.ACTION_UP, first)
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_UP, first)
        sendMouseButton(view, MotionEvent.BUTTON_SECONDARY)
        sendMouse(view, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY)
        sendMouse(view, MotionEvent.ACTION_UP, 0)
        sendScroll(view, 320f, 180f, -1f)
        assertTrue(touches.isEmpty())
    }

    @Test fun aCoalescedLockUnlockStillRequiresAnEntirelyNewPhysicalGesture() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        val first = Pointer(7, 100f, 100f)
        send(view, MotionEvent.ACTION_DOWN, first)
        view.setInputState(SessionInputState(false, 2))
        touches.clear()
        send(view, MotionEvent.ACTION_MOVE, first.copy(x = 120f))
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first, Pointer(19, 200f, 200f))
        send(view, MotionEvent.ACTION_UP, first)
        assertTrue(touches.isEmpty())
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_UP, first)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touches.map { it.action })
    }

    @Test fun localZoomAndTwoFingerPanRemainAvailableWhileInputIsLocked() = onMainThread {
        val view = newView()
        view.setVideoSize(500, 1000)
        view.setInputState(SessionInputState(true, 1))
        view.setZoom(2f)
        measureAndLayout(view, 1000, 1000)
        val touches = collectTouches(view)
        val before = requireNotNull(view.contentRect).contentTop
        val first = Pointer(7, 400f, 100f)
        val second = Pointer(19, 600f, 200f)
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
        send(view, MotionEvent.ACTION_MOVE, first.copy(y = 250f), second.copy(y = 350f))
        measureAndLayout(view, 1000, 1000)
        assertTrue(requireNotNull(view.contentRect).contentTop > before)
        assertTrue(touches.isEmpty())
        assertEquals(2f, view.zoom, 0f)
    }

    @Test
    fun surfaceBoundsMatchContentRectAfterRotationAndOddSizes() = onMainThread {
        val view = newView()
        val cases = listOf(
            intArrayOf(1080, 1920, 1080, 1920),
            intArrayOf(1920, 1080, 1080, 1920),
            intArrayOf(1080, 1920, 1920, 1080),
            intArrayOf(1001, 777, 641, 359),
            intArrayOf(777, 1001, 359, 641),
        )
        for ((parentWidth, parentHeight, videoWidth, videoHeight) in cases) {
            view.setVideoSize(videoWidth, videoHeight)
            measureAndLayout(view, parentWidth, parentHeight)
            assertGeometryMatchesSurface(view, parentWidth, parentHeight, videoWidth, videoHeight)
        }
    }

    @Test
    fun finalParentBoundsOverrideEarlierMeasurement() = onMainThread {
        val view = newView()
        view.setVideoSize(641, 359)
        view.measure(exact(1080), exact(1920))
        view.layout(0, 0, 1001, 777)
        assertGeometryMatchesSurface(view, 1001, 777, 641, 359)
    }

    @Test
    fun fractionalZoomTouchesUseTheActualSurfaceBounds() = onMainThread {
        val view = newView()
        view.setVideoSize(1080, 2400)
        view.setZoom(1.5625f)
        measureAndLayout(view, 1080, 2400)
        val surface = view.getChildAt(0)
        val callbacks = collectTouches(view)
        val pointer = Pointer(7, 0f, 1200f)

        assertTrue(send(view, MotionEvent.ACTION_DOWN, pointer))
        assertTrue(send(view, MotionEvent.ACTION_UP, pointer))

        val expectedX = floor((pointer.x - surface.left) / (surface.width.toFloat() / 1080))
            .toInt().coerceIn(0, 1079)
        val expectedY = floor((pointer.y - surface.top) / (surface.height.toFloat() / 2400))
            .toInt().coerceIn(0, 2399)
        assertEquals("Touch must select the pixel rendered by the actual SurfaceView", expectedX, callbacks.first().x)
        assertEquals(expectedY, callbacks.first().y)
        val geometry = requireNotNull(view.contentRect)
        assertEquals(surface.left.toFloat(), geometry.contentLeft, 0f)
        assertEquals(surface.top.toFloat(), geometry.contentTop, 0f)
        assertEquals(surface.width.toFloat(), geometry.contentWidth, 0f)
        assertEquals(surface.height.toFloat(), geometry.contentHeight, 0f)
    }

    @Test
    fun twoFingerPanReversesImmediatelyAfterReachingTheContentEdge() = onMainThread {
        val view = newView()
        view.setVideoSize(500, 1000)
        view.setZoom(2f)
        measureAndLayout(view, 1000, 1000)
        val callbacks = collectTouches(view)
        val first = Pointer(7, 400f, 100f)
        val second = Pointer(19, 600f, 200f)

        assertTrue(send(view, MotionEvent.ACTION_DOWN, first))
        assertTrue(send(
            view,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first,
            second,
        ))
        assertTrue(send(view, MotionEvent.ACTION_MOVE, first.copy(y = 800f), second.copy(y = 900f)))
        measureAndLayout(view, 1000, 1000)
        assertEquals(0f, requireNotNull(view.contentRect).contentTop, 0f)

        val reversedFirst = first.copy(y = 750f)
        val reversedSecond = second.copy(y = 850f)
        assertTrue(send(view, MotionEvent.ACTION_MOVE, reversedFirst, reversedSecond))
        measureAndLayout(view, 1000, 1000)
        assertEquals(
            "Reversing 50px must not first repay the 200px dragged beyond the edge",
            -50f,
            requireNotNull(view.contentRect).contentTop,
            0f,
        )
        assertTrue(send(
            view,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            reversedFirst,
            reversedSecond,
        ))
        assertTrue(send(view, MotionEvent.ACTION_UP, reversedFirst))
        assertEquals(
            "Entering pan releases the first remote pointer; panning never injects another",
            listOf(MotionEvent.ACTION_DOWN to 7L, MotionEvent.ACTION_UP to 7L),
            callbacks.map { it.action to it.pointerId },
        )

        callbacks.clear()
        val fresh = Pointer(23, 200f, 300f)
        assertTrue(send(view, MotionEvent.ACTION_DOWN, fresh))
        assertTrue(send(view, MotionEvent.ACTION_UP, fresh))
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN to 23L, MotionEvent.ACTION_UP to 23L),
            callbacks.map { it.action to it.pointerId },
        )
    }

    @Test
    fun zeroVideoSizeDoesNotForwardTouches() = onMainThread {
        val view = newView()
        val callbacks = collectTouches(view)
        view.setVideoSize(0, 0)
        measureAndLayout(view, 800, 480)
        assertNull(view.contentRect)
        assertFalse(send(view, MotionEvent.ACTION_DOWN, Pointer(3, 20f, 20f)))
        assertTrue(callbacks.isEmpty())
    }

    @Test
    fun letterboxTouchesClampToBothVideoEdges() = onMainThread {
        val view = newView()
        view.setVideoSize(641, 359)
        measureAndLayout(view, 777, 1001)
        val callbacks = collectTouches(view)

        assertTrue(send(view, MotionEvent.ACTION_DOWN, Pointer(7, -20f, -30f)))
        assertTrue(send(view, MotionEvent.ACTION_MOVE, Pointer(7, 900f, 1200f)))
        assertTrue(send(view, MotionEvent.ACTION_UP, Pointer(7, 900f, 1200f)))

        assertEquals(3, callbacks.size)
        assertEquals(0, callbacks.first().x)
        assertEquals(0, callbacks.first().y)
        assertEquals(640, callbacks[1].x)
        assertEquals(358, callbacks[1].y)
        assertEquals(0f, callbacks.last().pressure, 0f)
        callbacks.forEach {
            assertEquals(7L, it.pointerId)
            assertEquals(641, it.videoWidth)
            assertEquals(359, it.videoHeight)
        }
    }

    @Test
    fun touchOffsetsUseTheSameContentRectAsTheSurface() = onMainThread {
        val view = newView()
        view.setVideoSize(641, 359)
        measureAndLayout(view, 1001, 777)
        view.touchOffsetX = -13f
        view.touchOffsetY = 17f
        val geometry = requireNotNull(view.contentRect)
        val callbacks = collectTouches(view)
        val pointer = Pointer(5, 501.5f, 387.5f)

        send(view, MotionEvent.ACTION_DOWN, pointer)
        send(view, MotionEvent.ACTION_UP, pointer)

        assertEquals(geometry.toDeviceX(pointer.x - 13f), callbacks.first().x)
        assertEquals(geometry.toDeviceY(pointer.y + 17f), callbacks.first().y)
    }

    @Test
    fun multiTouchKeepsPointerIdsAndReleasesEveryPointerOnCancel() = onMainThread {
        val view = newView()
        view.setVideoSize(800, 600)
        measureAndLayout(view, 800, 600)
        val callbacks = collectTouches(view)
        val first = Pointer(5, 100f, 120f)
        val second = Pointer(19, 500f, 400f)

        send(view, MotionEvent.ACTION_DOWN, first)
        send(
            view,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first,
            second,
        )
        val movedFirst = first.copy(x = 110f, y = 130f)
        val movedSecond = second.copy(x = 520f, y = 420f)
        send(view, MotionEvent.ACTION_MOVE, movedFirst, movedSecond)
        send(view, MotionEvent.ACTION_CANCEL, movedFirst, movedSecond)

        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN to 5L,
                MotionEvent.ACTION_DOWN to 19L,
                MotionEvent.ACTION_MOVE to 5L,
                MotionEvent.ACTION_MOVE to 19L,
                MotionEvent.ACTION_UP to 5L,
                MotionEvent.ACTION_UP to 19L,
            ),
            callbacks.map { it.action to it.pointerId },
        )
        assertEquals(110, callbacks[2].x)
        assertEquals(520, callbacks[3].x)
        callbacks.takeLast(2).forEach { assertEquals(0f, it.pressure, 0f) }

        view.releaseAllPointers()
        assertEquals("Cancel must also clear the active-pointer state", 6, callbacks.size)
    }

    @Test
    fun aCancelCarryingOneFingerStillReleasesTheOthers() = onMainThread {
        // The platform is not obliged to describe every live pointer in a cancel; a synthetic one
        // can arrive naming only the first, at the origin. Trusting the event would leave the
        // other fingers pressed on the remote device for the rest of the session, poisoning every
        // gesture after it.
        val view = newView()
        view.setVideoSize(800, 600)
        measureAndLayout(view, 800, 600)
        val callbacks = collectTouches(view)
        val first = Pointer(5, 100f, 120f)
        val second = Pointer(19, 500f, 400f)

        send(view, MotionEvent.ACTION_DOWN, first)
        send(
            view,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first,
            second,
        )
        callbacks.clear()
        send(view, MotionEvent.ACTION_CANCEL, first.copy(x = 0f, y = 0f))

        assertEquals(
            "Both fingers must be released",
            listOf(MotionEvent.ACTION_UP to 5L, MotionEvent.ACTION_UP to 19L),
            callbacks.map { it.action to it.pointerId }.sortedBy { it.second },
        )
        // Released where each finger actually was, not where the cancel claimed pointer 0 was.
        val geometry = requireNotNull(view.contentRect)
        assertEquals(geometry.toDeviceX(500f), callbacks.first { it.pointerId == 19L }.x)
    }

    @Test
    fun aCancelReleasesEachFingerWhereItActuallyWas() = onMainThread {
        // Releases go out in the order the fingers went down, whatever ids the platform handed
        // out, so the same gesture always produces the same sequence on the wire.
        val view = newView()
        view.setVideoSize(800, 600)
        measureAndLayout(view, 800, 600)
        val callbacks = collectTouches(view)
        val first = Pointer(19, 100f, 120f)
        val second = Pointer(5, 500f, 400f)

        send(view, MotionEvent.ACTION_DOWN, first)
        send(
            view,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            first,
            second,
        )
        callbacks.clear()
        send(view, MotionEvent.ACTION_CANCEL, first.copy(x = 0f, y = 0f))

        assertEquals(
            listOf(MotionEvent.ACTION_UP to 19L, MotionEvent.ACTION_UP to 5L),
            callbacks.map { it.action to it.pointerId },
        )
    }

    @Test
    fun sameSizedCaptureRestartSuppressesOldGestureUntilFreshDown() = onMainThread {
        val view = newView()
        view.setVideoSize(800, 600, 1)
        measureAndLayout(view, 800, 600)
        val callbacks = collectTouches(view)
        val pointer = Pointer(19, 300f, 200f)
        send(view, MotionEvent.ACTION_DOWN, pointer)
        view.setVideoSize(800, 600, 2)
        measureAndLayout(view, 800, 600)
        send(view, MotionEvent.ACTION_MOVE, pointer.copy(x = 320f))
        send(view, MotionEvent.ACTION_UP, pointer)
        assertEquals(1, callbacks.size)
        send(view, MotionEvent.ACTION_DOWN, pointer)
        send(view, MotionEvent.ACTION_UP, pointer)
        assertEquals(listOf(0, 0, 1), callbacks.map { it.action })
        assertEquals(2L, view.captureGeneration)
    }

    @Test
    fun pointerUpOnlyReleasesItsActionIndex() = onMainThread {
        val view = newView()
        view.setVideoSize(800, 600)
        measureAndLayout(view, 800, 600)
        val callbacks = collectTouches(view)
        val first = Pointer(4, 100f, 100f)
        val second = Pointer(12, 300f, 300f)
        send(view, MotionEvent.ACTION_DOWN, first)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second)
        send(view, MotionEvent.ACTION_MOVE, first.copy(x = 200f))
        send(view, MotionEvent.ACTION_UP, first.copy(x = 200f))

        assertEquals(
            listOf(4L, 12L, 12L, 4L, 4L),
            callbacks.map { it.pointerId },
        )
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            callbacks.map { it.action },
        )
    }

    private fun assertGeometryMatchesSurface(
        view: ScrcpyMirrorView,
        parentWidth: Int,
        parentHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        val geometry = view.contentRect
        assertNotNull(geometry)
        requireNotNull(geometry)
        assertEquals(VideoLayout.fitCentered(parentWidth, parentHeight, videoWidth, videoHeight), geometry)
        val surface = view.getChildAt(0)
        assertEquals(geometry.contentLeft.toInt(), surface.left)
        assertEquals(geometry.contentTop.toInt(), surface.top)
        assertEquals(geometry.contentWidth.toInt(), surface.width)
        assertEquals(geometry.contentHeight.toInt(), surface.height)
        assertEquals(surface.width, surface.measuredWidth)
        assertEquals(surface.height, surface.measuredHeight)
        assertTrue(surface.left >= 0 && surface.top >= 0)
        assertTrue(surface.right <= parentWidth && surface.bottom <= parentHeight)
    }

    private fun collectTouches(view: ScrcpyMirrorView): MutableList<Touch> =
        mutableListOf<Touch>().also { touches ->
            view.onTouch = { action, id, x, y, pressure, width, height, _, _ ->
                touches += Touch(action, id, x, y, pressure, width, height)
            }
        }

    private fun newView() = ScrcpyMirrorView(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(exact(width), exact(height))
        view.layout(0, 0, width, height)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun onMainThread(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private fun send(view: ScrcpyMirrorView, action: Int, vararg pointers: Pointer): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now,
            now,
            action,
            pointers.size,
            pointers.map { pointer ->
                MotionEvent.PointerProperties().apply {
                    id = pointer.id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }.toTypedArray(),
            pointers.map { pointer ->
                MotionEvent.PointerCoords().apply {
                    x = pointer.x
                    y = pointer.y
                    pressure = 1f
                    size = 1f
                }
            }.toTypedArray(),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        return try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float)

    @Test
    fun aRightClickNavigatesBackInsteadOfTappingTheDevice() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        val buttons = mutableListOf<Int>()
        view.onMouseButton = { buttons += it }

        assertTrue(sendMouseButton(view, MotionEvent.BUTTON_SECONDARY))
        // Android reports the same click as a pointer gesture too; that must not also land as a
        // tap on the device.
        assertTrue(sendMouse(view, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY))
        assertTrue(sendMouse(view, MotionEvent.ACTION_UP, 0))

        assertEquals(listOf(android.view.KeyEvent.KEYCODE_BACK), buttons)
        assertTrue("a right click must not inject a touch", touches.isEmpty())
    }

    @Test
    fun anOrdinaryMouseClickStillTouchesTheDevice() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        view.onMouseButton = { throw AssertionError("primary button is a pointer press") }

        assertTrue(sendMouse(view, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY))
        assertTrue(sendMouse(view, MotionEvent.ACTION_UP, 0))

        assertEquals(2, touches.size)
        assertEquals(MirrorViewModelActions.DOWN, touches.first().action)
        assertEquals(MirrorViewModelActions.UP, touches.last().action)
    }

    @Test
    fun aSuppressedRightClickDoesNotSwallowTheNextRealTouch() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val touches = collectTouches(view)
        view.onMouseButton = {}

        assertTrue(sendMouse(view, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY))
        assertTrue(sendMouse(view, MotionEvent.ACTION_UP, 0))
        // The suppression must end with its own gesture, or every later touch would be dropped.
        assertTrue(send(view, MotionEvent.ACTION_DOWN, Pointer(1, 10f, 10f)))
        assertTrue(send(view, MotionEvent.ACTION_UP, Pointer(1, 10f, 10f)))

        assertEquals(2, touches.size)
    }

    @Test
    fun wheelScrollingReachesTheDeviceAtThePointerPosition() = onMainThread {
        val view = newView()
        view.setVideoSize(640, 360)
        measureAndLayout(view, 640, 360)
        val scrolls = mutableListOf<IntArray>()
        view.onScroll = { x, y, h, v, w, hh -> scrolls += intArrayOf(x, y, (h * 100).toInt(), (v * 100).toInt(), w, hh) }

        assertTrue(sendScroll(view, 320f, 180f, vertical = -1f))

        assertEquals(1, scrolls.size)
        assertEquals(320, scrolls[0][0])
        assertEquals(180, scrolls[0][1])
        assertEquals(-100, scrolls[0][3])
        assertEquals(640, scrolls[0][4])
    }

    private fun sendMouseButton(view: ScrcpyMirrorView, button: Int): Boolean {
        val event = mouseEvent(MotionEvent.ACTION_BUTTON_PRESS, 10f, 10f, button, button)
        return try { view.onGenericMotionEvent(event) } finally { event.recycle() }
    }

    private fun sendMouse(view: ScrcpyMirrorView, action: Int, buttonState: Int): Boolean {
        val event = mouseEvent(action, 10f, 10f, buttonState, 0)
        return try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun sendScroll(view: ScrcpyMirrorView, x: Float, y: Float, vertical: Float): Boolean {
        val event = mouseEvent(MotionEvent.ACTION_SCROLL, x, y, 0, 0, vertical)
        return try { view.onGenericMotionEvent(event) } finally { event.recycle() }
    }

    private fun mouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        actionButton: Int,
        vertical: Float = 0f,
    ): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            if (vertical != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        })
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords,
            0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        if (actionButton != 0) {
            // actionButton has no obtain() parameter; the framework exposes it only through the
            // hidden setter, so reflect when available and otherwise rely on buttonState.
            runCatching {
                MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
                    .invoke(event, actionButton)
            }
        }
        return event
    }

    private object MirrorViewModelActions {
        const val DOWN = 0
        const val UP = 1
    }

    private data class Touch(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val pressure: Float,
        val videoWidth: Int,
        val videoHeight: Int,
    )
}
