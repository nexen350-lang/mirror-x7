package com.scrcpy.remote.ui

import com.scrcpy.remote.session.MirrorViewModel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown

/**
 * Precision Pad: a local cursor over the mirrored screen, moved by sliding a finger anywhere on it.
 *
 * Direct touch presses where the finger is, which covers the very thing being aimed at and is only
 * as fine as a fingertip on a picture that may be shrunk several times. Here the finger moves a
 * cursor by how far it slides, the way a laptop touchpad does, and a tap presses at the cursor's
 * tip. Pressing and holding is explicit ([toggleDrag]) rather than guessed from timing, and a held
 * press survives lifting the finger to reach further.
 *
 * The cursor is kept in video pixels, so zooming or panning the picture carries it with the
 * content. It is drawn through [VideoLayout.toViewX] and pressed through [VideoLayout.toDeviceX] of
 * the one layout the view renders with: the press is on the pixel a direct touch at the drawn tip
 * would select, and there is no second transform that could drift away from the picture.
 *
 * Pure state, so it is testable without a device: the view feeds it plain coordinates and hands
 * what it asks for to the session.
 */
internal class PrecisionPad(
    /** How far, in view pixels, a finger may wander and still tap. */
    private val touchSlop: Float,
    /** Two-finger travel, in view pixels, that scrolls by one wheel step. */
    private val scrollStep: Float,
    private val sink: Sink,
) {
    interface Sink {
        /**
         * A press of the pad's own pointer on video pixel ([x], [y]); [viewX]/[viewY] is where the
         * cursor tip was drawn when it happened.
         */
        fun touch(action: Int, x: Int, y: Int, viewX: Float, viewY: Float)

        /** Wheel steps at video pixel ([x], [y]), with Android's signs: positive is up and right. */
        fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float)
    }

    /** The cursor tip in video pixels; NaN until [place] gives it a position. */
    var cursorX = Float.NaN
        private set
    var cursorY = Float.NaN
        private set

    /** Cursor travel on screen per unit of finger travel. */
    var speed: Float = DEFAULT_SPEED
        set(value) {
            require(value.isFinite() && value > 0f) { "Speed must be positive" }
            field = value
        }

    /** True while [toggleDrag] holds a press down on the device. */
    var dragging = false
        private set

    val scrolling: Boolean get() = gesture == Gesture.SCROLL
    val placed: Boolean get() = cursorX.isFinite() && cursorY.isFinite()

    private enum class Gesture { NONE, TRACK, SCROLL }

    private data class Target(val x: Int, val y: Int, val viewX: Float, val viewY: Float)

    private var gesture = Gesture.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var travelled = 0f
    private var downCursorX = Float.NaN
    private var downCursorY = Float.NaN
    private var pendingHorizontal = 0f
    private var pendingVertical = 0f

    /** Where the held press last was, which is where it has to be released. */
    private var held = Target(0, 0, 0f, 0f)

    /** Gives the cursor a position in the middle of what is on screen, if it has none yet. */
    fun place(layout: VideoLayout, viewWidth: Int, viewHeight: Int) {
        if (!layout.isValid) return
        if (placed) {
            clampTo(layout.videoWidth, layout.videoHeight)
            return
        }
        val left = max(0f, layout.contentLeft)
        val right = min(viewWidth.toFloat(), layout.contentLeft + layout.contentWidth)
        val top = max(0f, layout.contentTop)
        val bottom = min(viewHeight.toFloat(), layout.contentTop + layout.contentHeight)
        val middleX = if (right > left) (left + right) / 2f else layout.contentLeft + layout.contentWidth / 2f
        val middleY = if (bottom > top) (top + bottom) / 2f else layout.contentTop + layout.contentHeight / 2f
        cursorX = (middleX - layout.contentLeft) / layout.scaleX
        cursorY = (middleY - layout.contentTop) / layout.scaleY
        clampTo(layout.videoWidth, layout.videoHeight)
    }

    /** The first finger is down. Until it is lifted or joined, it moves the cursor. */
    fun down(x: Float, y: Float, time: Long) {
        gesture = Gesture.TRACK
        lastX = x
        lastY = y
        downX = x
        downY = y
        downTime = time
        travelled = 0f
        downCursorX = cursorX
        downCursorY = cursorY
    }

    /**
     * More than one finger is down, centred on ([x], [y]): the gesture scrolls until every finger
     * is up. Called again when a finger joins or lifts, so the centre jumping is not travel.
     */
    fun scrollAnchor(x: Float, y: Float) {
        if (gesture == Gesture.NONE) return
        gesture = Gesture.SCROLL
        lastX = x
        lastY = y
        pendingHorizontal = 0f
        pendingVertical = 0f
    }

    /** The finger, or while scrolling the fingers' centre, is now at ([x], [y]). */
    fun move(x: Float, y: Float, layout: VideoLayout) {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        when (gesture) {
            Gesture.NONE -> Unit
            Gesture.TRACK -> {
                travelled = max(travelled, hypot(x - downX, y - downY))
                if (!layout.isValid || !placed) return
                cursorX += dx * speed / layout.scaleX
                cursorY += dy * speed / layout.scaleY
                clampTo(layout.videoWidth, layout.videoHeight)
                if (dragging) follow(layout)
            }
            Gesture.SCROLL -> {
                // The content follows the fingers, as if they were dragging it: fingers moving up
                // show what is further down, which a wheel calls scrolling down.
                pendingHorizontal -= dx / scrollStep
                pendingVertical += dy / scrollStep
                if (abs(pendingHorizontal) < MIN_SCROLL && abs(pendingVertical) < MIN_SCROLL) return
                // The adds turn a negative zero into a plain one; the protocol has no use for the sign.
                val horizontal = pendingHorizontal.coerceIn(-MAX_SCROLL, MAX_SCROLL) + 0f
                val vertical = pendingVertical.coerceIn(-MAX_SCROLL, MAX_SCROLL) + 0f
                pendingHorizontal = 0f
                pendingVertical = 0f
                val target = target(layout) ?: return
                sink.scroll(target.x, target.y, horizontal, vertical)
            }
        }
    }

    /**
     * Every finger is up. A short touch that stayed put is a tap: it clicks at the cursor, or drops
     * what is being dragged. Anything longer only moved the cursor.
     */
    fun up(time: Long, layout: VideoLayout) {
        val tap = gesture == Gesture.TRACK && travelled <= touchSlop && time - downTime <= TAP_TIMEOUT_MS
        gesture = Gesture.NONE
        if (!tap) return
        // A finger always shifts a little while tapping. That is not aim, so the tap acts where
        // the cursor was when it began: a held press that followed the shift goes back first.
        val aimed = layout.isValid && downCursorX.isFinite() && downCursorY.isFinite()
        if (aimed) {
            cursorX = downCursorX
            cursorY = downCursorY
            clampTo(layout.videoWidth, layout.videoHeight)
        }
        if (dragging) {
            if (aimed) follow(layout)
            release()
            return
        }
        if (!aimed) return
        val target = target(layout) ?: return
        sink.touch(MirrorViewModel.ACTION_DOWN, target.x, target.y, target.viewX, target.viewY)
        sink.touch(MirrorViewModel.ACTION_UP, target.x, target.y, target.viewX, target.viewY)
    }

    /** The system took the gesture away. Nothing taps, and a held press is lifted. */
    fun cancel() {
        gesture = Gesture.NONE
        release()
    }

    /** Presses and holds at the cursor, or lifts a held press. */
    fun toggleDrag(layout: VideoLayout) {
        if (dragging) {
            release()
            return
        }
        val target = target(layout) ?: return
        dragging = true
        held = target
        sink.touch(MirrorViewModel.ACTION_DOWN, target.x, target.y, target.viewX, target.viewY)
    }

    /** Lifts a held press where it last was. Needs no layout, so it works on the way out too. */
    fun release() {
        if (!dragging) return
        dragging = false
        sink.touch(MirrorViewModel.ACTION_UP, held.x, held.y, held.viewX, held.viewY)
    }

    /**
     * The session has already lifted the press itself, as it does on a lock or a capture change:
     * only the record of it goes, and the gesture in progress with it.
     */
    fun forgetDrag() {
        dragging = false
        gesture = Gesture.NONE
    }

    /** The capture changed size, as on rotation: the cursor keeps its place on the picture. */
    fun resize(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int) {
        forgetDrag()
        if (newWidth <= 0 || newHeight <= 0) {
            cursorX = Float.NaN
            cursorY = Float.NaN
        } else if (placed && oldWidth > 0 && oldHeight > 0) {
            cursorX = cursorX / oldWidth * newWidth
            cursorY = cursorY / oldHeight * newHeight
            clampTo(newWidth, newHeight)
        }
    }

    /** Where the cursor tip is drawn, in view pixels. */
    fun cursorView(layout: VideoLayout): Pair<Float, Float>? =
        if (!layout.isValid || !placed) null else layout.toViewX(cursorX) to layout.toViewY(cursorY)

    /**
     * How far to move a magnified picture so the cursor stays at least [margin] inside the view.
     * Zero on an axis where the whole picture already fits; the view's own pan limit stops the
     * picture at its edge, which still shows the cursor.
     */
    fun revealShift(layout: VideoLayout, viewWidth: Int, viewHeight: Int, margin: Float): Pair<Float, Float> {
        val (x, y) = cursorView(layout) ?: return 0f to 0f
        return axisShift(x, viewWidth.toFloat(), layout.contentWidth, margin) to
            axisShift(y, viewHeight.toFloat(), layout.contentHeight, margin)
    }

    private fun axisShift(position: Float, view: Float, content: Float, margin: Float): Float {
        if (content <= view) return 0f
        val inset = margin.coerceIn(0f, view / 4f)
        return when {
            position < inset -> inset - position
            position > view - inset -> view - inset - position
            else -> 0f
        }
    }

    private fun follow(layout: VideoLayout) {
        val target = target(layout) ?: return
        if (target.x == held.x && target.y == held.y) return
        held = target
        sink.touch(MirrorViewModel.ACTION_MOVE, target.x, target.y, target.viewX, target.viewY)
    }

    private fun target(layout: VideoLayout): Target? {
        if (!layout.isValid || !placed) return null
        val viewX = layout.toViewX(cursorX)
        val viewY = layout.toViewY(cursorY)
        return Target(layout.toDeviceX(viewX), layout.toDeviceY(viewY), viewX, viewY)
    }

    /** Keeps the tip on the picture: any point short of the far edge is on its last pixel. */
    private fun clampTo(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !placed) return
        cursorX = cursorX.coerceIn(0f, width.toFloat().nextDown())
        cursorY = cursorY.coerceIn(0f, height.toFloat().nextDown())
    }

    companion object {
        const val DEFAULT_SPEED = 1f

        /** Few, fixed steps rather than an acceleration curve, until real use says otherwise. */
        val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f)

        /** A touch longer than this only moved the cursor, however still it stayed. */
        const val TAP_TIMEOUT_MS = 300L

        /**
         * The pad's pointer on the device: scrcpy's "generic finger", so it can never collide with
         * the ids of real fingers on the local screen.
         */
        const val POINTER_ID = -2L

        /** Travel too small to be worth a message; it is carried over until it adds up. */
        private const val MIN_SCROLL = 0.05f

        /** The most one scroll message can carry. */
        private const val MAX_SCROLL = 16f

        /** The step after [current], wrapping around; a value between steps moves to the next one. */
        fun nextSpeed(current: Float): Float = SPEEDS.firstOrNull { it > current + 1e-3f } ?: SPEEDS.first()
    }
}

/**
 * Whether the pad's bar, spanning [barTop]..[barBottom] in the same coordinates as the cursor,
 * would hide a cursor at [cursorY]. The bar moves to the other edge when it would.
 */
internal fun padBarCoversCursor(cursorY: Float, barTop: Float, barBottom: Float, margin: Float): Boolean =
    cursorY.isFinite() && barBottom > barTop && cursorY >= barTop - margin && cursorY <= barBottom + margin

/**
 * Whether the bar should move to the other edge: it hides the cursor where it is and would not
 * where it is going. [mirror] is the top plus the bottom of the area the bar moves in (its height
 * when it starts at zero), which maps a span at one edge onto the other. A bar too tall to avoid
 * the cursor anywhere stays put rather than flicker from edge to edge with every movement.
 */
internal fun padBarShouldMove(cursorY: Float, barTop: Float, barBottom: Float, mirror: Float, margin: Float): Boolean =
    padBarCoversCursor(cursorY, barTop, barBottom, margin) &&
        !padBarCoversCursor(cursorY, mirror - barBottom, mirror - barTop, margin)

/**
 * Whether the bar belongs at the top, judged only by where it was last laid out: at the top if
 * [laidOutAtTop], spanning [barTop]..[barBottom]. A move not laid out yet is never judged by the
 * bounds of the place it is leaving, so checking again before then gives the same answer instead
 * of sending it back.
 */
internal fun padBarAtTop(
    cursorY: Float,
    barTop: Float,
    barBottom: Float,
    mirror: Float,
    margin: Float,
    laidOutAtTop: Boolean,
): Boolean = if (padBarShouldMove(cursorY, barTop, barBottom, mirror, margin)) !laidOutAtTop else laidOutAtTop
