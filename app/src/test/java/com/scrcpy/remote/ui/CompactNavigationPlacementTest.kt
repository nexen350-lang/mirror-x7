package com.scrcpy.remote.ui

import org.junit.Assert.*
import org.junit.Test

class CompactNavigationPlacementTest {
    @Test fun portraitRemoteOnWidePhoneDocksBesideVideoAndAwayFromTheDots() {
        val content = VideoLayout.fitCentered(1920, 1080, 480, 800)
        val safe = ControlBounds(24, 24, 1896, 1056)
        val handle = ControlBounds(1832, 516, 1880, 564)
        val dock = checkNotNull(dockCompactNavigation(content, 1920, 1080, safe, 200, 56, 8, handle))
        assertTrue(dock.vertical)
        assertEquals(56, dock.bounds.width)
        assertEquals(200, dock.bounds.height)
        assertTrue(dock.bounds.right <= content.contentLeft - 8)
        assertFalse(dock.bounds.overlaps(handle))
        assertInside(dock.bounds, safe)
        val movedHandle = dock.bounds
        val otherSide = checkNotNull(dockCompactNavigation(content, 1920, 1080, safe, 200, 56, 8, movedHandle))
        assertTrue(otherSide.bounds.left >= content.contentLeft + content.contentWidth + 8)
    }

    @Test fun wideRemoteOnTallPhoneUsesTheBottomLetterboxWithoutRescalingVideo() {
        val content = VideoLayout.fitCentered(1080, 1920, 1920, 1080)
        val safe = ControlBounds(24, 50, 1056, 1840)
        val dock = checkNotNull(dockCompactNavigation(content, 1080, 1920, safe, 200, 56, 8,
            ControlBounds(980, 850, 1028, 898)))
        assertFalse(dock.vertical)
        assertEquals(200, dock.bounds.width)
        assertEquals(56, dock.bounds.height)
        assertTrue(dock.bounds.top >= content.contentTop + content.contentHeight + 8)
        assertInside(dock.bounds, safe)
        assertEquals(VideoLayout.fitCentered(1080, 1920, 1920, 1080), content)
    }

    @Test fun missingStaleOrFullScreenGeometryUsesTheCompactOverlayFallback() {
        val safe = ControlBounds(8, 8, 312, 632)
        val handle = ControlBounds(264, 296, 312, 344)
        for (content in listOf(null, VideoLayout.fitCentered(320, 640, 320, 640),
            VideoLayout.fitCentered(640, 320, 320, 640))) {
            assertNull(dockCompactNavigation(content, 320, 640, safe, 200, 56, 8, handle))
        }
    }

    @Test fun insetsAreRespectedAndLetterboxesTooSmallForTouchTargetsAreNotUsed() {
        val content = VideoLayout.fitCentered(320, 640, 300, 640)
        assertNull(dockCompactNavigation(content, 320, 640, ControlBounds(8, 24, 312, 600),
            200, 56, 8, ControlBounds(260, 300, 308, 348)))
        val wideContent = VideoLayout.fitCentered(900, 500, 300, 600)
        val safe = ControlBounds(220, 150, 680, 340)
        assertNull(dockCompactNavigation(wideContent, 900, 500, safe, 200, 56, 8,
            ControlBounds(228, 158, 276, 206)))
    }

    private fun assertInside(bounds: ControlBounds, safe: ControlBounds) {
        assertTrue(bounds.left >= safe.left && bounds.right <= safe.right)
        assertTrue(bounds.top >= safe.top && bounds.bottom <= safe.bottom)
    }
}
