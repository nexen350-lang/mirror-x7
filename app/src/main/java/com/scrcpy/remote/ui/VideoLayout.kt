package com.scrcpy.remote.ui

import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The rectangle the remote screen actually occupies on the local display, and the mapping from
 * local view pixels onto remote video pixels.
 *
 * This is deliberately expressed as a concrete rectangle rather than a formula: the view that
 * renders the stream lays its surface out into exactly this rectangle and maps touches with the
 * same instance, so what is drawn and what is touched cannot drift apart.
 *
 * Touches outside the rectangle (the letterbox bars) clamp onto the nearest edge pixel, which is
 * what makes edge gestures reachable from the physical edge of the local screen.
 */
data class VideoLayout(
    val contentLeft: Float,
    val contentTop: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val videoWidth: Int,
    val videoHeight: Int,
) {
    val isValid: Boolean =
        contentWidth > 0f && contentHeight > 0f && videoWidth > 0 && videoHeight > 0

    /** Local pixels per video pixel. */
    val scaleX: Float = if (videoWidth > 0) contentWidth / videoWidth else 0f
    val scaleY: Float = if (videoHeight > 0) contentHeight / videoHeight else 0f

    fun toDeviceX(viewX: Float): Int = mapAxis(viewX, contentLeft, scaleX, videoWidth)

    fun toDeviceY(viewY: Float): Int = mapAxis(viewY, contentTop, scaleY, videoHeight)

    /**
     * Where a continuous video position is drawn, in view pixels: the inverse of [toDeviceX] for
     * a point inside the picture. A cursor kept in video pixels is drawn through this and pressed
     * through [toDeviceX], so both go through this one rectangle.
     */
    fun toViewX(videoX: Float): Float = contentLeft + videoX * scaleX

    fun toViewY(videoY: Float): Float = contentTop + videoY * scaleY

    /**
     * Selects the video pixel that is actually **displayed** under the touch point.
     *
     * It is tempting to send the nearest integer to the true continuous position, which is
     * marginally closer in device space. But the user judges accuracy through the same downscale:
     * whatever the remote device draws at the injected coordinate comes back rendered in the
     * video, so the press has to land in the very pixel the finger is over, otherwise the
     * feedback appears offset by up to one video pixel — which at 3-4x upscaling is plainly
     * visible as the touch registering slightly low and to the right.
     *
     * Flooring also gives every video pixel an equally wide band of view pixels; rounding would
     * give the first and last pixel of each axis only half a band.
     */
    private fun mapAxis(coordinate: Float, origin: Float, scale: Float, extent: Int): Int {
        if (!isValid || scale <= 0f) return 0
        val projected = (coordinate - origin) / scale
        if (projected.isNaN()) return 0
        // Clamp before converting so a wild coordinate cannot overflow the Int conversion.
        if (projected <= 0f) return 0
        if (projected >= extent.toFloat()) return extent - 1
        return floor(projected).toInt().coerceIn(0, extent - 1)
    }

    companion object {
        /** The most the view may be magnified. Beyond this a video pixel is a large blank block. */
        const val MAX_ZOOM = 8f

        /**
         * Fits [videoWidth] x [videoHeight] inside the given view, preserving the aspect ratio and
         * centring it, snapped to whole pixels so the result can be used verbatim as a view
         * layout rectangle.
         *
         * [zoom] magnifies the content about the centre of the view and [panX]/[panY] shift it,
         * both in view pixels. They deliberately change *this rectangle* rather than being applied
         * as a separate transform later: the surface is laid out into whatever this returns and
         * touches are mapped through the same instance, so magnifying the rectangle keeps the two
         * in step by construction. A second transform would have to be inverted correctly in the
         * touch path, and any disagreement would show up as a touch offset that grows with zoom.
         *
         * A zoom of exactly 1 with no pan returns the unmagnified rectangle unchanged, so the
         * default path is not merely equivalent but identical.
         */
        fun fitCentered(
            viewWidth: Int,
            viewHeight: Int,
            videoWidth: Int,
            videoHeight: Int,
            zoom: Float = 1f,
            panX: Float = 0f,
            panY: Float = 0f,
        ): VideoLayout {
            if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
                return VideoLayout(0f, 0f, 0f, 0f, videoWidth, videoHeight)
            }

            val scale = min(
                viewWidth.toFloat() / videoWidth,
                viewHeight.toFloat() / videoHeight,
            )
            val width = (videoWidth * scale).roundToInt().coerceIn(1, viewWidth)
            val height = (videoHeight * scale).roundToInt().coerceIn(1, viewHeight)

            val magnification = sanitizeZoom(zoom)
            if (magnification == 1f) {
                // The historical path, kept exact rather than recomputed through the zoom
                // arithmetic: every touch-accuracy property of this class is stated against it.
                val left = (viewWidth - width) / 2
                val top = (viewHeight - height) / 2
                return VideoLayout(
                    contentLeft = left.toFloat(),
                    contentTop = top.toFloat(),
                    contentWidth = width.toFloat(),
                    contentHeight = height.toFloat(),
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                )
            }

            // Quantize the shared rectangle, not just the SurfaceView that renders it.
            val zoomedWidth = (width * magnification).toInt().toFloat()
            val zoomedHeight = (height * magnification).toInt().toFloat()
            return VideoLayout(
                contentLeft = offsetFor(viewWidth.toFloat(), zoomedWidth, panX).toInt().toFloat(),
                contentTop = offsetFor(viewHeight.toFloat(), zoomedHeight, panY).toInt().toFloat(),
                contentWidth = zoomedWidth,
                contentHeight = zoomedHeight,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            )
        }

        /**
         * Where one axis of the magnified content starts, with [pan] clamped to the slack.
         *
         * An axis with no slack ignores the pan rather than drifting off centre. The view uses
         * the same clamp for its accumulated pan so overscroll cannot delay the next reversal.
         */
        private fun offsetFor(view: Float, content: Float, pan: Float): Float =
            (view - content) / 2f + clampPan(view, content, pan)

        internal fun clampPan(view: Float, content: Float, pan: Float): Float {
            val slack = ((content - view) / 2f).coerceAtLeast(0f)
            return if (pan.isFinite()) pan.coerceIn(-slack, slack) else 0f
        }

        /**
         * Keeps zoom inside a range that can produce a usable layout.
         *
         * A NaN or zero reaching the surface layout would size it to nothing and lose the stream
         * with no visible cause, so it is corrected here — at the one place that turns a zoom into
         * a rectangle — rather than trusted to every caller.
         */
        private fun sanitizeZoom(zoom: Float): Float =
            if (!zoom.isFinite() || zoom < 1f) 1f else min(zoom, MAX_ZOOM)
    }
}
