package com.scrcpy.remote.ui

import android.content.Context
import androidx.core.content.edit
import com.scrcpy.remote.data.canonicalHost
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Normalized position and size of a floating mirror window within the available viewport. */
internal data class MirrorWindowFractions(
    val xFraction: Float,
    val yFraction: Float,
    val sizeFraction: Float,
)

/** Pixel bounds of the video surface inside the mirror window container. */
internal data class MirrorWindowRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun toControlBounds(): ControlBounds = ControlBounds(left, top, right, bottom)
}

internal fun mirrorWindowDeviceKey(deviceId: String?, host: String, port: Int): String =
    if (!deviceId.isNullOrBlank()) "device:$deviceId"
    else "endpoint:${canonicalHost(host)}:$port"

internal fun mirrorWindowOrientationSlot(
    deviceKey: String,
    localLandscape: Boolean,
    remoteLandscape: Boolean,
): String {
    val localTag = if (localLandscape) "hl" else "hp"
    val remoteTag = if (remoteLandscape) "rl" else "rp"
    return "$deviceKey:$localTag:$remoteTag"
}

private fun safeAspect(videoWidth: Int, videoHeight: Int): Float =
    if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight.toFloat()
    else 9f / 16f

private fun maxWindowDimensions(
    safe: ControlBounds,
    aspect: Float,
    topDragReservePx: Int,
): Pair<Float, Float> {
    val availW = safe.width.coerceAtLeast(1).toFloat()
    val availH = (safe.height - topDragReservePx).coerceAtLeast(1).toFloat()
    return if (availW / availH > aspect) {
        val h = availH
        (h * aspect) to h
    } else {
        val w = availW
        w to (w / aspect)
    }
}

private fun minWindowWidth(
    maxW: Float,
    aspect: Float,
    minShortSidePx: Int,
): Float {
    val desiredMinW = if (aspect >= 1f) minShortSidePx * aspect else minShortSidePx.toFloat()
    return desiredMinW.coerceAtMost(maxW).coerceAtLeast(1f)
}

/**
 * Computes the mirror window's pixel rectangle inside [safe] while preserving the remote video's
 * aspect ratio. When [saved] is null, centers the window at a comfortable phone-mirroring size
 * that leaves room on the left for the attached vertical control bar.
 */
internal fun computeMirrorWindowRect(
    safe: ControlBounds,
    videoWidth: Int,
    videoHeight: Int,
    topDragReservePx: Int,
    sideBarFootprintPx: Int,
    minShortSidePx: Int,
    saved: MirrorWindowFractions? = null,
): MirrorWindowRect {
    val aspect = safeAspect(videoWidth, videoHeight)
    val (maxW, _) = maxWindowDimensions(safe, aspect, topDragReservePx)
    val minW = minWindowWidth(maxW, aspect, minShortSidePx)

    val widthPx: Int
    val heightPx: Int
    val leftPx: Int
    val topPx: Int

    val minTop = safe.top + topDragReservePx
    if (saved != null && saved.sizeFraction.isFinite() &&
        saved.xFraction.isFinite() && saved.yFraction.isFinite()
    ) {
        val targetW = (saved.sizeFraction * maxW).coerceIn(minW, maxW)
        widthPx = targetW.roundToInt().coerceIn(1, safe.width.coerceAtLeast(1))
        heightPx = (widthPx / aspect).roundToInt()
            .coerceIn(1, (safe.bottom - minTop).coerceAtLeast(1))

        val maxLeft = max(safe.left, safe.right - widthPx)
        val maxTop = max(minTop, safe.bottom - heightPx)
        val xFrac = saved.xFraction.coerceIn(0f, 1f)
        val yFrac = saved.yFraction.coerceIn(0f, 1f)
        leftPx = (safe.left + xFrac * (maxLeft - safe.left)).roundToInt()
        topPx = (minTop + yFrac * (maxTop - minTop)).roundToInt()
    } else {
        // Default: centered phone-sized frame leaving balanced space for the left control bar
        // and the right floating handle whenever the screen is wide enough.
        val balancedSideMargin = sideBarFootprintPx.toFloat()
        val availW = safe.width.coerceAtLeast(1).toFloat()
        val availH = (safe.height - topDragReservePx).coerceAtLeast(1).toFloat()
        val targetMaxW = if (availW > balancedSideMargin * 2f + minW) {
            min(availW * 0.78f, availW - balancedSideMargin * 2f)
        } else {
            (availW - balancedSideMargin).coerceAtLeast(availW * 0.72f)
        }
        val targetMaxH = availH * 0.78f
        val defaultW = if (targetMaxW / targetMaxH > aspect) {
            targetMaxH * aspect
        } else {
            targetMaxW
        }.coerceIn(minW, maxW)

        widthPx = defaultW.roundToInt().coerceIn(1, safe.width.coerceAtLeast(1))
        heightPx = (widthPx / aspect).roundToInt()
            .coerceIn(1, (safe.bottom - minTop).coerceAtLeast(1))

        val maxLeft = max(safe.left, safe.right - widthPx)
        val maxTop = max(minTop, safe.bottom - heightPx)
        val centeredLeft = safe.left + (safe.width - widthPx) / 2
        val minPreferredLeft = safe.left + sideBarFootprintPx
        leftPx = if (centeredLeft >= minPreferredLeft || maxLeft < minPreferredLeft) {
            centeredLeft.coerceIn(safe.left, maxLeft)
        } else {
            minPreferredLeft.coerceIn(safe.left, maxLeft)
        }
        topPx = (minTop + (maxTop - minTop) / 2).coerceIn(minTop, maxTop)
    }

    return MirrorWindowRect(leftPx, topPx, widthPx, heightPx)
}

/** Moves [current] by ([deltaX], [deltaY]) while keeping the window and top drag bar reachable. */
internal fun dragMirrorWindow(
    current: MirrorWindowRect,
    deltaX: Float,
    deltaY: Float,
    safe: ControlBounds,
    topDragReservePx: Int,
): MirrorWindowRect {
    val minTop = safe.top + topDragReservePx
    val maxLeft = max(safe.left, safe.right - current.width)
    val maxTop = max(minTop, safe.bottom - current.height)
    val newLeft = (current.left + deltaX).roundToInt().coerceIn(safe.left, maxLeft)
    val newTop = (current.top + deltaY).roundToInt().coerceIn(minTop, maxTop)
    return current.copy(left = newLeft, top = newTop)
}

/**
 * Resizes from the bottom-right corner relative to [start] while keeping the top-left corner fixed
 * and strictly preserving the remote video's aspect ratio.
 */
internal fun resizeMirrorWindowFromCorner(
    start: MirrorWindowRect,
    totalDeltaX: Float,
    totalDeltaY: Float,
    safe: ControlBounds,
    videoWidth: Int,
    videoHeight: Int,
    topDragReservePx: Int,
    minShortSidePx: Int,
): MirrorWindowRect {
    val aspect = safeAspect(videoWidth, videoHeight)
    val (globalMaxW, _) = maxWindowDimensions(safe, aspect, topDragReservePx)
    val minW = minWindowWidth(globalMaxW, aspect, minShortSidePx)

    val anchorMaxW = (safe.right - start.left).coerceAtLeast(1).toFloat()
    val anchorMaxH = (safe.bottom - start.top).coerceAtLeast(1).toFloat()
    val maxWFromAnchor = min(globalMaxW, min(anchorMaxW, anchorMaxH * aspect))
    val effectiveMinW = min(minW, maxWFromAnchor).coerceAtLeast(1f)

    val relX = abs(totalDeltaX) / start.width.coerceAtLeast(1)
    val relY = abs(totalDeltaY) / start.height.coerceAtLeast(1)
    val candidateW = if (relX >= relY) {
        start.width + totalDeltaX
    } else {
        (start.height + totalDeltaY) * aspect
    }
    val clampedW = candidateW.coerceIn(effectiveMinW, maxWFromAnchor)
    val widthPx = clampedW.roundToInt().coerceIn(1, (safe.right - start.left).coerceAtLeast(1))
    val heightPx = (widthPx / aspect).roundToInt()
        .coerceIn(1, (safe.bottom - start.top).coerceAtLeast(1))
    return MirrorWindowRect(start.left, start.top, widthPx, heightPx)
}

/** Converts pixel [rect] into normalized [MirrorWindowFractions] for per-device persistence. */
internal fun mirrorWindowToFractions(
    rect: MirrorWindowRect,
    safe: ControlBounds,
    videoWidth: Int,
    videoHeight: Int,
    topDragReservePx: Int,
): MirrorWindowFractions {
    val aspect = safeAspect(videoWidth, videoHeight)
    val (maxW, _) = maxWindowDimensions(safe, aspect, topDragReservePx)
    val minTop = safe.top + topDragReservePx
    val maxLeft = max(safe.left, safe.right - rect.width)
    val maxTop = max(minTop, safe.bottom - rect.height)
    val xFraction = if (maxLeft > safe.left) {
        ((rect.left - safe.left).toFloat() / (maxLeft - safe.left)).coerceIn(0f, 1f)
    } else 0.5f
    val yFraction = if (maxTop > minTop) {
        ((rect.top - minTop).toFloat() / (maxTop - minTop)).coerceIn(0f, 1f)
    } else 0.5f
    val sizeFraction = (rect.width / maxW).coerceIn(0.05f, 1f)
    return MirrorWindowFractions(xFraction, yFraction, sizeFraction)
}

/**
 * Places the compact navigation bar vertically along the left edge of [window] with [gap],
 * clamped within [safe] so it always remains reachable on screen while moving with [window].
 */
internal fun attachCompactNavigationToWindow(
    window: ControlBounds,
    safe: ControlBounds,
    barWidth: Int,
    barHeight: Int,
    gap: Int,
): CompactNavigationPlacement {
    val safeRightLeftLimit = (safe.right - barWidth).coerceAtLeast(safe.left)
    val safeBottomTopLimit = (safe.bottom - barHeight).coerceAtLeast(safe.top)
    val left = (window.left - gap - barWidth).coerceIn(safe.left, safeRightLeftLimit)
    val top = (window.top + (window.height - barHeight) / 2).coerceIn(safe.top, safeBottomTopLimit)
    return CompactNavigationPlacement(
        bounds = ControlBounds(left, top, left + barWidth, top + barHeight),
        vertical = true,
    )
}

/** Per-device mirror window position and size preferences. */
internal object MirrorWindowPreferences {
    private const val FILE = "mirror_window_layout"
    private const val KEY_X = ".x"
    private const val KEY_Y = ".y"
    private const val KEY_SIZE = ".size"

    fun load(
        context: Context,
        deviceKey: String,
        localLandscape: Boolean,
        remoteLandscape: Boolean,
    ): MirrorWindowFractions? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val slot = mirrorWindowOrientationSlot(deviceKey, localLandscape, remoteLandscape)
        if (!prefs.contains("$slot$KEY_SIZE")) return null
        val x = prefs.getFloat("$slot$KEY_X", 0.5f)
        val y = prefs.getFloat("$slot$KEY_Y", 0.5f)
        val size = prefs.getFloat("$slot$KEY_SIZE", 0.75f)
        if (!x.isFinite() || !y.isFinite() || !size.isFinite()) return null
        return MirrorWindowFractions(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), size.coerceIn(0.05f, 1f))
    }

    fun save(
        context: Context,
        deviceKey: String,
        localLandscape: Boolean,
        remoteLandscape: Boolean,
        fractions: MirrorWindowFractions,
    ) {
        val slot = mirrorWindowOrientationSlot(deviceKey, localLandscape, remoteLandscape)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putFloat("$slot$KEY_X", fractions.xFraction.coerceIn(0f, 1f))
            putFloat("$slot$KEY_Y", fractions.yFraction.coerceIn(0f, 1f))
            putFloat("$slot$KEY_SIZE", fractions.sizeFraction.coerceIn(0.05f, 1f))
        }
    }
}
