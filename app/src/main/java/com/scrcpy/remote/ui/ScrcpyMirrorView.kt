package com.scrcpy.remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.graphics.withTranslation
import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.session.MirrorViewModel
import com.scrcpy.remote.session.MouseButtonPolicy
import com.scrcpy.remote.session.SessionInputState

/**
 * Renders the mirrored screen and forwards touches to it.
 *
 * Rendering and input deliberately live in one view: the surface is laid out into exactly the
 * rectangle returned by [VideoLayout.fitCentered], and touches are mapped with that same
 * rectangle. A separate touch overlay would have to recompute the geometry and could disagree
 * with where the surface actually ended up, which shows up as a constant offset between where the
 * user touches and where the remote device registers the press.
 *
 * The view itself always fills its parent so that touches on the letterbox bars are still
 * received and clamped onto the nearest edge of the remote screen.
 */
class ScrcpyMirrorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val surfaceView = SurfaceView(context)

    /** Invoked with (action, pointerId, deviceX, deviceY, pressure, videoWidth, videoHeight, rawX, rawY). */
    var onTouch: ((Int, Long, Int, Int, Float, Int, Int, Float, Float) -> Unit)? = null

    /** Invoked with (deviceX, deviceY, horizontal, vertical, videoWidth, videoHeight). */
    var onScroll: ((Int, Int, Float, Float, Int, Int) -> Unit)? = null

    /** Invoked with the Android keycode a non-primary mouse button maps to. */
    var onMouseButton: ((Int) -> Unit)? = null

    private var suppressedMouseGesture = false
    private var gestureStarted = false
    var inputState = SessionInputState()
        private set

    fun setInputState(value: SessionInputState) {
        if (value == inputState) return
        releaseAllPointers()
        inputState = value
        gestureStarted = false
        panning = false
        suppressedMouseGesture = false
        updateGestureExclusion()
        padChanged()
    }

    private val density = resources.displayMetrics.density

    /**
     * The precision pad's cursor, presses and scrolls. Everything it sends goes out through
     * [onTouch] and [onScroll] like any other input, so the session's gate, lock and release
     * bookkeeping apply to it unchanged.
     */
    private val pad = PrecisionPad(
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        scrollStep = PAD_SCROLL_STEP_DP * density,
        sink = object : PrecisionPad.Sink {
            override fun touch(action: Int, x: Int, y: Int, viewX: Float, viewY: Float) {
                onTouch?.invoke(action, PrecisionPad.POINTER_ID, x, y,
                    if (action == MirrorViewModel.ACTION_UP) 0f else 1f, videoWidth, videoHeight, viewX, viewY)
            }

            override fun scroll(x: Int, y: Int, horizontal: Float, vertical: Float) {
                onScroll?.invoke(x, y, horizontal, vertical, videoWidth, videoHeight)
            }
        },
    )

    /**
     * Precision pad mode: a finger moves a cursor, and only a tap or an explicit hold reaches the
     * device, at the cursor. A physical mouse keeps pointing directly; it is precise already.
     */
    var precisionPad: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // A gesture already under way belongs to the mode it began in. The rest of it is
            // dropped rather than reinterpreted, and whatever either mode was holding is let go.
            releaseAllPointers()
            gestureStarted = false
            panning = false
            suppressedMouseGesture = false
            padChanged()
        }

    /** Cursor travel on screen per unit of finger travel; one of [PrecisionPad.SPEEDS]. */
    var precisionPadSpeed: Float
        get() = pad.speed
        set(value) {
            pad.speed = value
        }

    /** Told whether a press is held and where the cursor tip is drawn (NaN when it is not). */
    var onPrecisionPadChanged: ((dragging: Boolean, cursorX: Float, cursorY: Float) -> Unit)? = null

    /** Where the cursor tip is drawn, in view pixels, while the pad is showing one. */
    internal val precisionPadCursor: Pair<Float, Float>?
        get() = if (!precisionPad || inputState.locked) null else currentLayout()?.let { pad.cursorView(it) }

    internal val precisionPadDragging: Boolean get() = pad.dragging

    /** "Hold to drag": presses and holds at the cursor, or lifts the held press. */
    fun togglePrecisionPadDrag() {
        if (!precisionPad || inputState.locked) return
        val geometry = currentLayout() ?: return
        pad.place(geometry, width, height)
        pad.toggleDrag(geometry)
        padChanged()
    }

    /** Lifts a held press, for the moments the user's attention has gone elsewhere. */
    fun releasePrecisionPadDrag() {
        if (!pad.dragging) return
        pad.release()
        padChanged()
    }

    /** Exposes the rectangle the surface occupies so a diagnostic overlay can invert the mapping. */
    val contentRect: VideoLayout? get() = layout?.takeIf { it.isValid }

    val surfaceHolder: SurfaceHolder get() = surfaceView.holder
    internal val screenshotSurface: SurfaceView get() = surfaceView

    /** Adjustment in pixels applied to a touch before mapping; negative moves it up and left. */
    var touchOffsetX: Float = 0f
    var touchOffsetY: Float = 0f

    fun applyTouchCalibration(x: Float, y: Float) {
        require(x.isFinite() && y.isFinite()) { "Calibration must be finite" }
        if (x == touchOffsetX && y == touchOffsetY) return
        // The pad presses exactly at its drawn tip, so a calibration change does not move its press.
        releaseDirectPointers()
        touchOffsetX = x
        touchOffsetY = y
    }

    /**
     * Whether to claim the screen edges for the remote device.
     *
     * Claiming them lets edge swipes reach the remote instead of triggering the local device's
     * own back gesture — but the local gesture is then unavailable, which is why only a band is
     * claimed and why this can be turned off entirely.
     */
    var captureEdgeGestures: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateGestureExclusion()
        }

    private var videoWidth = 0
    private var videoHeight = 0
    var captureGeneration = 0L
        private set
    var onGeometryChanged: ((VideoLayout) -> Unit)? = null

    /**
     * Magnification of the mirrored picture, and how far it has been dragged from centre.
     *
     * These feed [VideoLayout.fitCentered], which means they change the rectangle the surface is
     * laid out into rather than being applied as a separate transform afterwards. Touch mapping
     * reads the same rectangle, so a magnified touch cannot land anywhere other than the pixel
     * drawn beneath it.
     */
    var zoom: Float = 1f
        private set
    private var panX: Float = 0f
    private var panY: Float = 0f

    /** Notified whenever the magnification changes, so the UI can show and offer to reset it. */
    var onZoomChanged: ((Float) -> Unit)? = null

    /** True while a multi-finger gesture is moving the picture instead of reaching the device. */
    private var panning = false
    private var panAnchorX = 0f
    private var panAnchorY = 0f

    /** The rectangle the surface occupies; also the authority for touch mapping. */
    private var layout: VideoLayout? = null

    /**
     * Last sample sent per pointer id: the coordinate, packed as x in the high word and y in the
     * low word, together with the pressure as it goes on the wire.
     *
     * Pressure belongs in the same entry rather than a map beside it, because this one also acts
     * as the register of which pointers are down — two maps could fall out of step and leave a
     * finger held on the device.
     *
     * Insertion-ordered so that releasing several pointers at once goes out in the order they
     * went down. A hash order would vary with the ids the platform happened to hand out, making
     * the same gesture produce a different sequence on the wire from one run to the next.
     */
    private val lastSent = LinkedHashMap<Int, Sample>()

    /** A coordinate and pressure pair, compared to decide whether a MOVE is worth sending. */
    private data class Sample(val packed: Long, val pressure: Int)

    init {
        addView(surfaceView)
        setWillNotDraw(true)
    }

    fun setVideoSize(width: Int, height: Int, generation: Long = 0) {
        // (0, 0) is the not-yet-ready state; partially specified or negative sizes are invalid.
        require((width == 0 && height == 0) || (width > 0 && height > 0)) { "Invalid video size" }
        if (width == videoWidth && height == videoHeight && generation == captureGeneration) return
        // The session coordinator releases remote pointers on every capture generation.
        // Keep remaining physical fingers suppressed until a fresh DOWN, including 180-degree turns.
        lastSent.clear()
        // The pad's held press is among those the session releases; its cursor keeps its place.
        pad.resize(videoWidth, videoHeight, width, height)
        gestureStarted = false
        panning = false
        suppressedMouseGesture = false
        captureGeneration = generation
        videoWidth = width
        videoHeight = height
        layout = null
        requestLayout()
        padChanged()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        measureSurface(width, height)

        // Always occupy the whole area so the letterbox bars remain touchable.
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Recomputed from the final bounds rather than reusing the measured ones: `layout` is the
        // single source of truth for touch mapping, so it must describe where the surface really
        // ends up even if the parent hands us bounds that differ from what we measured.
        val geometry = measureSurface(r - l, b - t)
        if (!geometry.isValid) {
            surfaceView.layout(0, 0, 0, 0)
            return
        }
        val left = geometry.contentLeft.toInt()
        val top = geometry.contentTop.toInt()
        surfaceView.layout(
            left,
            top,
            left + geometry.contentWidth.toInt(),
            top + geometry.contentHeight.toInt(),
        )
        onGeometryChanged?.invoke(geometry)
        // Posted like the geometry above: a cursor that moved with the picture is reported once
        // layout has finished, not from inside it.
        if (precisionPad) post { padChanged() }
    }

    /** Computes the content rectangle and measures the surface to match it exactly. */
    private fun measureSurface(width: Int, height: Int): VideoLayout {
        val geometry = VideoLayout.fitCentered(width, height, videoWidth, videoHeight, zoom, panX, panY)
        layout = geometry
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(geometry.contentWidth.toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(geometry.contentHeight.toInt(), MeasureSpec.EXACTLY),
        )
        return geometry
    }

    /**
     * Magnifies the picture, keeping the pan within whatever slack the new size leaves.
     *
     * Zooming out to 1 also recentres: pan means nothing once the whole picture fits, and keeping
     * a stale offset would make the next zoom-in jump somewhere the user did not leave it.
     */
    fun setZoom(value: Float) {
        val next = if (value.isFinite()) value.coerceIn(1f, VideoLayout.MAX_ZOOM) else 1f
        if (next == 1f) applyTransform(1f, 0f, 0f) else applyTransform(next, panX, panY)
        // Zooming in about the centre can leave the cursor off screen; the picture follows it.
        if (precisionPad) revealCursor()
    }

    /** Multiplies the current magnification, for a step control. */
    fun zoomBy(factor: Float) = setZoom(zoom * factor)

    /** Returns to the fitted, centred picture. */
    fun resetZoom() = applyTransform(1f, 0f, 0f)

    private fun applyTransform(newZoom: Float, newPanX: Float, newPanY: Float) {
        val z = if (newZoom.isFinite()) newZoom.coerceIn(1f, VideoLayout.MAX_ZOOM) else 1f
        val fitted = VideoLayout.fitCentered(width, height, videoWidth, videoHeight, z)
        val px = VideoLayout.clampPan(width.toFloat(), fitted.contentWidth, newPanX)
        val py = VideoLayout.clampPan(height.toFloat(), fitted.contentHeight, newPanY)
        if (z == zoom && px == panX && py == panY) return
        // A finger that is down while the picture moves underneath it would spend the rest of its
        // gesture on whatever slid under it, so the device is told those pointers lifted — the
        // same reason a capture size change releases them. The pad's press is not one of them:
        // its cursor stays on the same video pixel however the picture moves.
        releaseDirectPointers()
        val zoomChanged = z != zoom
        zoom = z
        panX = px
        panY = py
        layout = null
        requestLayout()
        if (zoomChanged) onZoomChanged?.invoke(z)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    /**
     * Reserves a band of each vertical edge for the remote device.
     *
     * Android honours at most 200dp of exclusion per edge anyway, so claiming the entire side —
     * as this used to — simply took the local back gesture away everywhere. Claiming a centred
     * band instead leaves the corners free, so an edge swipe near the top or bottom still drives
     * the local device while a swipe from the middle of the edge reaches the remote one.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!captureEdgeGestures || inputState.locked || width <= 0 || height <= 0) {
            systemGestureExclusionRects = emptyList()
            return
        }

        val density = resources.displayMetrics.density
        val bandHeight = minOf(height, (EDGE_BAND_DP * density).toInt())
        val edgeWidth = minOf(width / 2, (EDGE_WIDTH_DP * density).toInt()).coerceAtLeast(1)
        val top = (height - bandHeight) / 2
        val bottom = top + bandHeight

        systemGestureExclusionRects = listOf(
            Rect(0, top, edgeWidth, bottom),
            Rect(width - edgeWidth, top, width, bottom),
        )
    }

    // The mirrored screen has no click semantics of its own; every gesture is forwarded verbatim.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Handled before anything else, including the geometry check. A cancel has to release
        // every pointer the device still believes is down, and neither the event nor the layout
        // can be trusted to make that happen: a synthetic cancel may carry only one pointer, at
        // (0,0), and a cancel arriving once the layout is invalid would otherwise return early
        // and release nothing at all. `releaseAllPointers` uses each pointer's own last position,
        // so none of them is released somewhere it never was.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestureStarted = false
            suppressedMouseGesture = false
            panning = false
            releaseAllPointers()
            padChanged()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureStarted = true
        else if (!gestureStarted) return true
        if (event.actionMasked == MotionEvent.ACTION_UP) gestureStarted = false

        // The pad takes every finger, magnified or not: two fingers scroll there, and the picture
        // follows the cursor instead of being dragged. A mouse still points directly, and while
        // locked the pad is out of the way so the ordinary locked handling applies.
        if (precisionPad && !inputState.locked && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            handlePadGesture(event)
            return true
        }

        // While the picture is magnified, a second finger takes over the gesture to move it. One
        // finger still reaches the device, which is the point: zooming in to tap something small
        // would be pointless if the zoomed view could not be touched.
        //
        // At zoom 1 this is skipped entirely, so the ordinary multi-touch path — pinching and
        // rotating on the remote device — is exactly what it was.
        if (zoom > 1f || panning) {
            if (handlePanGesture(event)) return true
        }
        if (inputState.locked) return true

        val geometry = currentLayout() ?: return false

        // A secondary or tertiary mouse click is already delivered as a button press and acted on
        // there. Android still reports it as a pointer gesture, so without this the same click
        // would also land on the device as a tap.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            suppressedMouseGesture = event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.buttonState != 0 &&
                event.buttonState and MotionEvent.BUTTON_PRIMARY == 0
            // Asks for motion to arrive as it is sampled rather than once per displayed frame.
            // Batched delivery holds every sample newer than 5 ms before the frame boundary, so
            // a drag reaches the device around half a frame plus 5 ms after the finger was
            // actually there — time that no amount of care further down can win back. Lasts for
            // this gesture only, which is why it is asked for again on each press.
            requestUnbufferedDispatch(event)
        }
        if (suppressedMouseGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                suppressedMouseGesture = false
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                dispatch(event, event.actionIndex, MirrorViewModel.ACTION_DOWN, geometry)

            MotionEvent.ACTION_MOVE -> {
                // Android batches motion samples; replaying the history keeps fast drags and
                // handwriting accurate instead of collapsing them into straight jumps.
                for (position in 0 until event.historySize) {
                    for (index in 0 until event.pointerCount) {
                        dispatch(event, index, MirrorViewModel.ACTION_MOVE, geometry, position)
                    }
                }
                for (index in 0 until event.pointerCount) {
                    dispatch(event, index, MirrorViewModel.ACTION_MOVE, geometry)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                dispatch(event, event.actionIndex, MirrorViewModel.ACTION_UP, geometry)

            else -> return false
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        gestureStarted = false
        releaseAllPointers()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // A held press has no finger on the screen for the system to cancel when something takes
        // the window away — a dialog, the notification shade, leaving the app — so it goes here.
        if (!hasWindowFocus) releasePrecisionPadDrag()
    }

    /** Draws the pad's cursor over the video, through the same layout its presses map through. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val (x, y) = precisionPadCursor ?: return
        if (pad.dragging) canvas.drawCircle(x, y, PRESSED_RADIUS_DP * density, pressedPaint)
        // Named apart from the view's density: inside the block, `density` would be the Canvas's.
        val dpScale = density
        canvas.withTranslation(x, y) {
            scale(dpScale, dpScale)
            drawPath(CURSOR_SHAPE, cursorFill)
            drawPath(CURSOR_SHAPE, cursorOutline)
        }
    }

    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cursorOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        // In dp: the canvas is scaled to density while the shape is drawn.
        strokeWidth = 1.5f
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PRESSED_COLOR
        style = Paint.Style.FILL
    }

    /** The layout touches and the cursor map through: the laid out one, or the one about to be. */
    private fun currentLayout(): VideoLayout? =
        resolveTouchLayout(layout, width, height, videoWidth, videoHeight, zoom, panX, panY)

    /**
     * Pad input. Fingers move the cursor or scroll; only a tap or the explicit hold reaches the
     * device, and always at the cursor rather than under the finger.
     */
    private fun handlePadGesture(event: MotionEvent) {
        val geometry = currentLayout() ?: return
        pad.place(geometry, width, height)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                pad.down(event.getX(0), event.getY(0), event.eventTime)
            }

            MotionEvent.ACTION_POINTER_DOWN -> pad.scrollAnchor(centroidX(event, -1), centroidY(event, -1))

            MotionEvent.ACTION_MOVE -> if (pad.scrolling) {
                pad.move(centroidX(event, -1), centroidY(event, -1), geometry)
            } else {
                // Replayed like direct touch, so a quick slide moves the cursor by all of it.
                for (position in 0 until event.historySize) {
                    pad.move(event.getHistoricalX(0, position), event.getHistoricalY(0, position), geometry)
                }
                pad.move(event.getX(0), event.getY(0), geometry)
                revealCursor()
            }

            // The lifting finger is still in the event; leaving it out keeps the centre from jumping.
            MotionEvent.ACTION_POINTER_UP ->
                pad.scrollAnchor(centroidX(event, event.actionIndex), centroidY(event, event.actionIndex))

            MotionEvent.ACTION_UP -> pad.up(event.eventTime, geometry)
        }
        padChanged()
    }

    /** Moves a magnified picture just enough to keep the cursor in view. */
    private fun revealCursor() {
        if (zoom <= 1f) return
        val geometry = currentLayout() ?: return
        val (shiftX, shiftY) = pad.revealShift(geometry, width, height, PAD_REVEAL_MARGIN_DP * density)
        if (shiftX != 0f || shiftY != 0f) applyTransform(zoom, panX + shiftX, panY + shiftY)
    }

    /** Places the cursor if it has no position yet, redraws it and reports it. */
    private fun padChanged() {
        if (precisionPad) currentLayout()?.let { pad.place(it, width, height) }
        invalidate()
        val tip = precisionPadCursor
        onPrecisionPadChanged?.invoke(pad.dragging, tip?.first ?: Float.NaN, tip?.second ?: Float.NaN)
    }

    /**
     * Forwards wheel and trackpad scrolling from a physical mouse or a connected desktop setup.
     *
     * Touch scrolling already works as a drag, so only true pointer sources are handled here;
     * treating anything else as a scroll would double up with [onTouchEvent].
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            return super.onGenericMotionEvent(event)
        }
        if (inputState.locked) return true
        // Button presses arrive here rather than through onTouchEvent, because a button state
        // change is not one of Android's touch actions.
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            val keycode = MouseButtonPolicy.keycodeFor(event.actionButton)
                ?: return super.onGenericMotionEvent(event)
            onMouseButton?.invoke(keycode)
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
        val geometry = currentLayout() ?: return false

        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (horizontal == 0f && vertical == 0f) return false

        onScroll?.invoke(
            geometry.toDeviceX(event.x + touchOffsetX),
            geometry.toDeviceY(event.y + touchOffsetY),
            horizontal,
            vertical,
            videoWidth,
            videoHeight,
        )
        return true
    }

    /** Lifts any pointer still held down, so nothing stays pressed on the remote device. */
    fun releaseAllPointers() {
        releaseDirectPointers()
        pad.cancel()
    }

    /** Lifts the fingers forwarded by direct touch, leaving the pad's own press alone. */
    private fun releaseDirectPointers() {
        releasePointers(videoWidth, videoHeight)
    }

    /**
     * Moves the magnified picture with two or more fingers.
     *
     * Once a gesture becomes a pan it stays one until every finger is up. Handing control back
     * the moment a second finger lifts would drive the remote device with whatever finger
     * happened to remain, in the middle of a movement the user meant for the picture.
     *
     * @return true when the event belongs to the pan and must not be forwarded.
     */
    private fun handlePanGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panning = false
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!panning) {
                    panning = true
                    // The first finger was already forwarded as a press; it has to be lifted on
                    // the device or it stays down there for the whole pan.
                    releaseDirectPointers()
                }
                anchorPan(event, ignoring = -1)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!panning) return false
                val x = centroidX(event, ignoring = -1)
                val y = centroidY(event, ignoring = -1)
                applyTransform(zoom, panX + (x - panAnchorX), panY + (y - panAnchorY))
                panAnchorX = x
                panAnchorY = y
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (!panning) return false
                // The lifting finger is still in the event; excluding it stops the picture
                // jumping as the centroid moves to the remaining fingers.
                anchorPan(event, ignoring = event.actionIndex)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!panning) return false
                panning = false
                return true
            }
        }
        return panning
    }

    private fun anchorPan(event: MotionEvent, ignoring: Int) {
        panAnchorX = centroidX(event, ignoring)
        panAnchorY = centroidY(event, ignoring)
    }

    private fun centroidX(event: MotionEvent, ignoring: Int): Float {
        var total = 0f
        var counted = 0
        for (index in 0 until event.pointerCount) {
            if (index == ignoring) continue
            total += event.getX(index)
            counted++
        }
        return if (counted == 0) panAnchorX else total / counted
    }

    private fun centroidY(event: MotionEvent, ignoring: Int): Float {
        var total = 0f
        var counted = 0
        for (index in 0 until event.pointerCount) {
            if (index == ignoring) continue
            total += event.getY(index)
            counted++
        }
        return if (counted == 0) panAnchorY else total / counted
    }

    private fun releasePointers(width: Int, height: Int) {
        if (lastSent.isEmpty() || width <= 0 || height <= 0) {
            lastSent.clear()
            return
        }
        val pending = lastSent.toMap()
        lastSent.clear()
        pending.forEach { (pointerId, sample) ->
            val x = (sample.packed shr 32).toInt().coerceIn(0, width - 1)
            val y = sample.packed.toInt().coerceIn(0, height - 1)
            onTouch?.invoke(
                MirrorViewModel.ACTION_UP,
                pointerId.toLong(),
                x,
                y,
                0f,
                width,
                height,
                0f,
                0f,
            )
        }
    }

    /**
     * @param historyPosition index into the batched samples, or -1 for the current position.
     */
    private fun dispatch(
        event: MotionEvent,
        index: Int,
        action: Int,
        geometry: VideoLayout,
        historyPosition: Int = -1,
    ) {
        val pointerId = event.getPointerId(index)

        // Android pointer ids are handles, not slot indices, so the id's magnitude says nothing.
        // What the server limits is how many pointers are live at once; `lastSent` doubles as the
        // set of pointers it already knows about.
        val known = lastSent.containsKey(pointerId)
        when (action) {
            MirrorViewModel.ACTION_DOWN ->
                if (!known && lastSent.size >= MAX_POINTERS) return

            // A pointer the server never saw go down must not emit moves or releases: a stray
            // MOVE would make the server materialise a pointer that never had a DOWN.
            else -> if (!known) return
        }

        val rawX: Float
        val rawY: Float
        val rawPressure: Float
        if (historyPosition >= 0) {
            rawX = event.getHistoricalX(index, historyPosition)
            rawY = event.getHistoricalY(index, historyPosition)
            rawPressure = event.getHistoricalPressure(index, historyPosition)
        } else {
            rawX = event.getX(index)
            rawY = event.getY(index)
            rawPressure = event.getPressure(index)
        }

        val deviceX = geometry.toDeviceX(rawX + touchOffsetX)
        val deviceY = geometry.toDeviceY(rawY + touchOffsetY)
        val packed = (deviceX.toLong() shl 32) or (deviceY.toLong() and 0xFFFFFFFFL)
        val pressure = if (action == MirrorViewModel.ACTION_UP) {
            0f
        } else {
            rawPressure.takeIf { it > 0f }?.coerceAtMost(1f) ?: 1f
        }
        // Compared at the resolution the wire actually carries. Comparing the raw float would let
        // sensor noise defeat the skip below, and comparing nothing at all — as this did — meant
        // a press that grew harder without moving off its video pixel never reached the device.
        val sample = Sample(packed, ControlMessage.floatToU16Fixed(pressure))

        when (action) {
            MirrorViewModel.ACTION_MOVE -> {
                // A pinch reports every pointer on each batch even when only one moved; skipping
                // the unchanged ones keeps the control queue short and the moving finger smooth.
                if (lastSent[pointerId] == sample) return
                lastSent[pointerId] = sample
            }

            MirrorViewModel.ACTION_DOWN -> lastSent[pointerId] = sample

            MirrorViewModel.ACTION_UP -> lastSent.remove(pointerId)
        }

        onTouch?.invoke(
            action,
            pointerId.toLong(),
            deviceX,
            deviceY,
            pressure,
            geometry.videoWidth,
            geometry.videoHeight,
            rawX,
            rawY,
        )
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    private companion object {
        /** Maximum simultaneous pointers, mirroring `PointersState.MAX_POINTERS` in the server. */
        const val MAX_POINTERS = 10

        /** Height of the claimed edge band; Android caps exclusions at 200dp per edge. */
        const val EDGE_BAND_DP = 200f

        /** Width of the claimed strip along each vertical edge. */
        const val EDGE_WIDTH_DP = 32f

        /** Two-finger travel that scrolls by one wheel step on the pad. */
        const val PAD_SCROLL_STEP_DP = 48f

        /** How close to the edge of the view a magnified picture lets the cursor come. */
        const val PAD_REVEAL_MARGIN_DP = 48f

        /** The halo that shows a press is being held. */
        const val PRESSED_RADIUS_DP = 12f
        const val PRESSED_COLOR = 0x8000B0FF.toInt()

        /** An arrow pointer in dp, tip at the origin: white with a dark edge, legible on any video. */
        val CURSOR_SHAPE = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 17f)
            lineTo(4.2f, 13.2f)
            lineTo(7f, 19.4f)
            lineTo(9.6f, 18.2f)
            lineTo(6.9f, 12.2f)
            lineTo(12.2f, 12.2f)
            close()
        }
    }
}

/**
 * The layout a touch maps through: the laid-out one, or — when a size or zoom change has cleared
 * it and the next layout pass has not run — the one about to be, so a first DOWN is not dropped.
 */
internal fun resolveTouchLayout(
    laidOut: VideoLayout?, width: Int, height: Int, videoWidth: Int, videoHeight: Int,
    zoom: Float, panX: Float, panY: Float,
): VideoLayout? = laidOut?.takeIf { it.isValid }
    ?: VideoLayout.fitCentered(width, height, videoWidth, videoHeight, zoom, panX, panY).takeIf { it.isValid }
