package com.scrcpy.remote.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MirrorWindowLayoutTest {
    private val safePortrait = ControlBounds(24, 24, 1056, 2376)
    private val safeLandscape = ControlBounds(24, 24, 2376, 1056)
    private val topDragReserve = 78
    private val sideBarFootprint = 192
    private val minShortSide = 420

    @Test
    fun defaultWindowCentersAndPreservesPhoneAspectRatioWithRoomForSideBar() {
        val rect = computeMirrorWindowRect(
            safe = safePortrait,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            sideBarFootprintPx = sideBarFootprint,
            minShortSidePx = minShortSide,
        )
        val expectedAspect = 1080f / 2400f
        val actualAspect = rect.width.toFloat() / rect.height.toFloat()
        assertEquals(expectedAspect, actualAspect, 0.01f)
        assertTrue("Top drag bar must stay inside safe top", rect.top >= safePortrait.top + topDragReserve)
        assertTrue("Bottom must stay inside safe bottom", rect.bottom <= safePortrait.bottom)
        assertTrue("Room left for attached bar", rect.left >= safePortrait.left + sideBarFootprint)

        val centerDelta = abs((rect.left + rect.width / 2) - (safePortrait.left + safePortrait.width / 2))
        assertTrue("Default window should be centered horizontally, delta=$centerDelta", centerDelta <= 2)
    }

    @Test
    fun dragMovesWindowAndClampsInsideSafeBoundsIncludingTopDragBar() {
        val start = computeMirrorWindowRect(
            safe = safePortrait,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            sideBarFootprintPx = sideBarFootprint,
            minShortSidePx = minShortSide,
        )
        val moved = dragMirrorWindow(start, -80f, 120f, safePortrait, topDragReserve)
        assertEquals(start.left - 80, moved.left)
        assertEquals(start.top + 120, moved.top)
        assertEquals(start.width, moved.width)
        assertEquals(start.height, moved.height)

        val clampedTopLeft = dragMirrorWindow(start, -10_000f, -10_000f, safePortrait, topDragReserve)
        assertEquals(safePortrait.left, clampedTopLeft.left)
        assertEquals(safePortrait.top + topDragReserve, clampedTopLeft.top)

        val clampedBottomRight = dragMirrorWindow(start, 10_000f, 10_000f, safePortrait, topDragReserve)
        assertEquals(safePortrait.right - start.width, clampedBottomRight.left)
        assertEquals(safePortrait.bottom - start.height, clampedBottomRight.top)
    }

    @Test
    fun cornerResizePreservesAspectRatioKeepsTopLeftAnchoredAndEnforcesLimits() {
        val start = computeMirrorWindowRect(
            safe = safePortrait,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            sideBarFootprintPx = sideBarFootprint,
            minShortSidePx = minShortSide,
        )
        val expectedAspect = 1080f / 2400f

        for ((dx, dy) in listOf(120f to 240f, -160f to -300f, 200f to 10f, 10f to -280f)) {
            val resized = resizeMirrorWindowFromCorner(
                start = start,
                totalDeltaX = dx,
                totalDeltaY = dy,
                safe = safePortrait,
                videoWidth = 1080,
                videoHeight = 2400,
                topDragReservePx = topDragReserve,
                minShortSidePx = minShortSide,
            )
            assertEquals("Top-left X stays anchored", start.left, resized.left)
            assertEquals("Top-left Y stays anchored", start.top, resized.top)
            assertEquals(expectedAspect, resized.width.toFloat() / resized.height.toFloat(), 0.015f)
            assertTrue(resized.right <= safePortrait.right)
            assertTrue(resized.bottom <= safePortrait.bottom)
        }

        val shrunkToMin = resizeMirrorWindowFromCorner(
            start = start,
            totalDeltaX = -5000f,
            totalDeltaY = -5000f,
            safe = safePortrait,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            minShortSidePx = minShortSide,
        )
        assertTrue(shrunkToMin.width >= minShortSide - 1)
        assertEquals(expectedAspect, shrunkToMin.width.toFloat() / shrunkToMin.height.toFloat(), 0.015f)
    }

    @Test
    fun attachedLeftControlBarFollowsWindowAndClampsAtScreenEdge() {
        val window = MirrorWindowRect(left = 300, top = 400, width = 600, height = 1333)
        val placement = attachCompactNavigationToWindow(
            window = window.toControlBounds(),
            safe = safePortrait,
            barWidth = 168,
            barHeight = 744,
            gap = 24,
        )
        assertTrue(placement.vertical)
        assertEquals(window.left - 24, placement.bounds.right)
        assertEquals(168, placement.bounds.width)
        assertEquals(744, placement.bounds.height)

        val shifted = dragMirrorWindow(window, 150f, -200f, safePortrait, topDragReserve)
        val movedBar = attachCompactNavigationToWindow(
            window = shifted.toControlBounds(),
            safe = safePortrait,
            barWidth = 168,
            barHeight = 744,
            gap = 24,
        )
        assertEquals(shifted.left - 24, movedBar.bounds.right)
        assertEquals(shifted.top + (shifted.height - 744) / 2, movedBar.bounds.top)

        val atLeftEdge = dragMirrorWindow(window, -1000f, 0f, safePortrait, topDragReserve)
        val clampedBar = attachCompactNavigationToWindow(
            window = atLeftEdge.toControlBounds(),
            safe = safePortrait,
            barWidth = 168,
            barHeight = 744,
            gap = 24,
        )
        assertEquals(safePortrait.left, clampedBar.bounds.left)
    }

    @Test
    fun fractionsRoundTripRestoresWindowSizeAndPositionAccurately() {
        val initial = computeMirrorWindowRect(
            safe = safeLandscape,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            sideBarFootprintPx = sideBarFootprint,
            minShortSidePx = minShortSide,
        )
        val resized = resizeMirrorWindowFromCorner(
            start = initial,
            totalDeltaX = -90f,
            totalDeltaY = -180f,
            safe = safeLandscape,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            minShortSidePx = minShortSide,
        )
        val moved = dragMirrorWindow(resized, 350f, -80f, safeLandscape, topDragReserve)
        val saved = mirrorWindowToFractions(moved, safeLandscape, 1080, 2400, topDragReserve)
        val restored = computeMirrorWindowRect(
            safe = safeLandscape,
            videoWidth = 1080,
            videoHeight = 2400,
            topDragReservePx = topDragReserve,
            sideBarFootprintPx = sideBarFootprint,
            minShortSidePx = minShortSide,
            saved = saved,
        )
        assertTrue(abs(moved.left - restored.left) <= 2)
        assertTrue(abs(moved.top - restored.top) <= 2)
        assertTrue(abs(moved.width - restored.width) <= 2)
        assertTrue(abs(moved.height - restored.height) <= 2)
    }

    @Test
    fun touchMappingRemainsAccurateAcrossResizedWindowSizes() {
        val videoW = 1080
        val videoH = 2400
        for (windowWidth in listOf(450, 720, 900)) {
            val windowHeight = (windowWidth.toFloat() / (videoW.toFloat() / videoH.toFloat())).toInt()
            val layout = VideoLayout.fitCentered(windowWidth, windowHeight, videoW, videoH)
            assertTrue(layout.isValid)
            assertEquals(0, layout.toDeviceX(0f))
            assertEquals(0, layout.toDeviceY(0f))
            assertEquals(videoW / 2, layout.toDeviceX(windowWidth / 2f), 2)
            assertEquals(videoH / 2, layout.toDeviceY(windowHeight / 2f), 2)
            assertEquals(videoW - 1, layout.toDeviceX(windowWidth.toFloat()))
            assertEquals(videoH - 1, layout.toDeviceY(windowHeight.toFloat()))
        }
    }

    @Test
    fun perDeviceKeysAndOrientationSlotsRemainDistinct() {
        val k1 = mirrorWindowDeviceKey("dev-1", "192.168.1.10", 5555)
        val k2 = mirrorWindowDeviceKey("dev-2", "192.168.1.10", 5555)
        val unsaved1 = mirrorWindowDeviceKey(null, "::ffff:192.168.1.20", 5555)
        val unsaved2 = mirrorWindowDeviceKey(null, "192.168.1.20", 5555)
        assertNotEquals(k1, k2)
        assertEquals(unsaved1, unsaved2)
        assertNotEquals(
            mirrorWindowOrientationSlot(k1, localLandscape = false, remoteLandscape = false),
            mirrorWindowOrientationSlot(k1, localLandscape = false, remoteLandscape = true),
        )
        assertNotEquals(
            mirrorWindowOrientationSlot(k1, localLandscape = false, remoteLandscape = false),
            mirrorWindowOrientationSlot(k1, localLandscape = true, remoteLandscape = false),
        )
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("Expected $expected +/- $tolerance, got $actual", abs(expected - actual) <= tolerance)
    }
}
