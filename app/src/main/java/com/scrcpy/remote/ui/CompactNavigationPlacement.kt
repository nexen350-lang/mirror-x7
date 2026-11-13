package com.scrcpy.remote.ui

internal data class ControlBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun overlaps(other: ControlBounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal data class CompactNavigationPlacement(val bounds: ControlBounds, val vertical: Boolean)

/** Use real letterbox space when it fits; never resize the video to make room for controls. */
internal fun dockCompactNavigation(
    content: VideoLayout?,
    viewWidth: Int,
    viewHeight: Int,
    safe: ControlBounds,
    longSide: Int,
    shortSide: Int,
    gap: Int,
    handle: ControlBounds,
): CompactNavigationPlacement? {
    require(longSide >= shortSide && shortSide > 0 && gap >= 0)
    if (content == null || !content.isValid ||
        content != VideoLayout.fitCentered(viewWidth, viewHeight, content.videoWidth, content.videoHeight)
    ) return null
    val left = ControlBounds(safe.left, safe.top, content.contentLeft.toInt() - gap, safe.bottom)
    val right = ControlBounds((content.contentLeft + content.contentWidth).toInt() + gap, safe.top, safe.right, safe.bottom)
    val top = ControlBounds(safe.left, safe.top, safe.right, content.contentTop.toInt() - gap)
    val bottom = ControlBounds(safe.left, (content.contentTop + content.contentHeight).toInt() + gap, safe.right, safe.bottom)
    val sides = if (handle.left + handle.width / 2 >= viewWidth / 2) listOf(left, right) else listOf(right, left)
    val regions = listOf(bottom to false) + sides.map { it to true } + (top to false)
    for ((region, vertical) in regions) {
        val width = if (vertical) shortSide else longSide
        val height = if (vertical) longSide else shortSide
        if (region.width < width || region.height < height) continue
        val x = region.left + (region.width - width) / 2
        val y = region.top + (region.height - height) / 2
        val bounds = ControlBounds(x, y, x + width, y + height)
        if (!bounds.overlaps(handle)) return CompactNavigationPlacement(bounds, vertical)
    }
    return null
}
