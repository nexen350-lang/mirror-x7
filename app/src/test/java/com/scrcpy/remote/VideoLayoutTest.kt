package com.scrcpy.remote

import com.scrcpy.remote.ui.VideoLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The mapping from local view pixels to remote video pixels, including the letterbox bars.
 *
 * The property that decides perceived accuracy is the round trip: a touch must select the video
 * pixel that is drawn underneath it, because whatever the remote device renders at the injected
 * coordinate comes back to the user through the same downscale.
 */
class VideoLayoutTest {

    @Test
    fun exactAspectMatchUsesTheWholeArea() {
        val layout = VideoLayout.fitCentered(1080, 2400, 1080, 2400)

        assertTrue(layout.isValid)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(0f, layout.contentLeft, 0.0001f)
        assertEquals(0f, layout.contentTop, 0.0001f)
        assertEquals(0, layout.toDeviceX(0f))
        assertEquals(1079, layout.toDeviceX(1079.5f))
        assertEquals(2399, layout.toDeviceY(2399.5f))
    }

    @Test
    fun scalesDownWhenTheLocalScreenIsLarger() {
        // A 720x1600 video shown on a 1440x3200 screen: exactly 2 view pixels per video pixel.
        val layout = VideoLayout.fitCentered(1440, 3200, 720, 1600)

        assertEquals(2f, layout.scaleX, 0.0001f)
        assertEquals(0, layout.toDeviceX(0f))
        assertEquals(1, layout.toDeviceX(2f))
        assertEquals(360, layout.toDeviceX(720f))
        assertEquals(719, layout.toDeviceX(1439f))
    }

    @Test
    fun selectsTheVideoPixelDisplayedUnderTheTouch() {
        // 4 view pixels per video pixel, so the sub-pixel convention is easy to observe.
        val layout = VideoLayout.fitCentered(400, 400, 100, 100)

        assertEquals(4f, layout.scaleX, 0.0001f)

        // Video pixel 10 is drawn across view x in [40, 44). Every touch inside that band must
        // select pixel 10: whatever the device draws at the injected point comes back through the
        // same downscale, so anything else appears offset from the finger.
        assertEquals(10, layout.toDeviceX(40f))
        assertEquals(10, layout.toDeviceX(41f))
        assertEquals(10, layout.toDeviceX(42f))
        assertEquals(10, layout.toDeviceX(43.9f))
        assertEquals(11, layout.toDeviceX(44f))
        assertEquals(9, layout.toDeviceX(39.9f))
    }

    @Test
    fun everyViewPixelMapsBackIntoTheBandItWasDrawnFrom() {
        // The round trip that decides perceived accuracy: the pixel selected for a touch must be
        // the pixel whose drawn band contains that touch, with no drift along the axis.
        val layout = VideoLayout.fitCentered(1080, 2400, 720, 1600)

        var y = layout.contentTop
        while (y < layout.contentTop + layout.contentHeight) {
            val selected = layout.toDeviceY(y)
            val bandTop = layout.contentTop + selected * layout.scaleY
            val bandBottom = bandTop + layout.scaleY
            assertTrue(
                "view y=$y selected video row $selected drawn at [$bandTop, $bandBottom)",
                y >= bandTop - 0.001f && y < bandBottom + 0.001f,
            )
            y += 0.5f
        }
    }

    @Test
    fun letterboxesHorizontallyWhenTheLocalScreenIsWider() {
        // 1000x1000 view, 500x1000 video -> scale 1, 250px bars on the left and right.
        val layout = VideoLayout.fitCentered(1000, 1000, 500, 1000)

        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(250f, layout.contentLeft, 0.0001f)
        assertEquals(0f, layout.contentTop, 0.0001f)

        assertEquals(0, layout.toDeviceX(250f))
        assertEquals(100, layout.toDeviceX(350f))
        assertEquals(499, layout.toDeviceX(749.5f))
    }

    @Test
    fun touchesOnTheBarsClampToTheNearestEdge() {
        val layout = VideoLayout.fitCentered(1000, 1000, 500, 1000)

        assertEquals(0, layout.toDeviceX(0f))
        assertEquals(0, layout.toDeviceX(120f))
        assertEquals(0, layout.toDeviceX(249f))

        assertEquals(499, layout.toDeviceX(750f))
        assertEquals(499, layout.toDeviceX(999f))
    }

    @Test
    fun letterboxesVerticallyWhenTheLocalScreenIsTaller() {
        val layout = VideoLayout.fitCentered(1000, 1000, 1000, 500)

        assertEquals(250f, layout.contentTop, 0.0001f)
        assertEquals(0, layout.toDeviceY(0f))
        assertEquals(0, layout.toDeviceY(250f))
        assertEquals(100, layout.toDeviceY(350f))
        assertEquals(499, layout.toDeviceY(999f))
    }

    @Test
    fun theContentRectangleStaysInsideTheView() {
        // The rectangle is used verbatim as the surface's layout, so it must fit the parent and
        // keep the aspect ratio; otherwise the image and the touch mapping would disagree.
        val cases = listOf(
            listOf(1080, 2400, 720, 1600),
            listOf(1080, 2340, 720, 1600),
            listOf(2400, 1080, 1600, 720),
            listOf(1179, 2556, 1080, 2400),
            listOf(1000, 1000, 333, 777),
        )
        cases.forEach { (viewW, viewH, videoW, videoH) ->
            val layout = VideoLayout.fitCentered(viewW, viewH, videoW, videoH)
            assertTrue(layout.contentLeft >= 0f)
            assertTrue(layout.contentTop >= 0f)
            assertTrue(layout.contentLeft + layout.contentWidth <= viewW.toFloat())
            assertTrue(layout.contentTop + layout.contentHeight <= viewH.toFloat())

            val expected = videoW.toDouble() / videoH
            val actual = layout.contentWidth.toDouble() / layout.contentHeight
            assertTrue("aspect $actual vs $expected", abs(actual - expected) < 0.01)
        }
    }

    @Test
    fun mappingIsAlwaysInsideTheDeviceBounds() {
        val layout = VideoLayout.fitCentered(1080, 2400, 720, 1600)

        val probes = listOf(-5000f, -1f, 0f, 1f, 539f, 1079f, 1080f, 5000f, Float.MAX_VALUE)
        probes.forEach { x -> assertTrue("x=$x", layout.toDeviceX(x) in 0..719) }
        probes.forEach { y -> assertTrue("y=$y", layout.toDeviceY(y) in 0..1599) }
    }

    @Test
    fun everyDevicePixelIsReachable() {
        val layout = VideoLayout.fitCentered(1000, 2000, 250, 500)
        val reached = (0 until 1000).map { layout.toDeviceX(it.toFloat()) }.toSet()

        assertEquals(250, reached.size)
        assertEquals(0, reached.min())
        assertEquals(249, reached.max())
    }

    @Test
    fun aNegativeOffsetMovesTheInjectedPointUp() {
        // Backs the touch calibration setting: the offset is applied to the view coordinate
        // before mapping, so a negative value must lift the injected press.
        val layout = VideoLayout.fitCentered(1080, 2400, 720, 1600)
        val y = 1200f

        val uncorrected = layout.toDeviceY(y)
        val liftedBy10Px = layout.toDeviceY(y - 10f)
        val loweredBy10Px = layout.toDeviceY(y + 10f)

        assertTrue(liftedBy10Px < uncorrected)
        assertTrue(loweredBy10Px > uncorrected)
        // 10 view pixels is roughly 10 / scaleY video pixels, give or take the pixel boundary.
        val expected = (10f / layout.scaleY).toInt()
        assertTrue(abs((uncorrected - liftedBy10Px) - expected) <= 1)
    }

    @Test
    fun incompleteLayoutsAreRejected() {
        assertFalse(VideoLayout.fitCentered(0, 0, 720, 1600).isValid)
        assertFalse(VideoLayout.fitCentered(1080, 2400, 0, 0).isValid)
        assertEquals(0, VideoLayout.fitCentered(0, 0, 0, 0).toDeviceX(42f))
    }

    // --- Zoom and pan -------------------------------------------------------------------
    //
    // The whole design rests on one property: the rectangle used to lay the surface out is the
    // same rectangle used to map touches. Zoom therefore changes the rectangle rather than adding
    // a second transform, and these tests pin that down.

    @Test
    fun zoomOfOneAndNoPanIsBitIdenticalToTheUnzoomedLayout() {
        // The default path must not move by even a sub-pixel: every existing touch-accuracy
        // property depends on it, and "almost the same" would be a silent regression.
        for (view in listOf(1080 to 2400, 1440 to 3200, 2560 to 1600, 411 to 891)) {
            for (video in listOf(720 to 1600, 1080 to 2400, 1856 to 1392)) {
                val plain = VideoLayout.fitCentered(view.first, view.second, video.first, video.second)
                val zoomed = VideoLayout.fitCentered(
                    view.first, view.second, video.first, video.second, 1f, 0f, 0f,
                )
                assertEquals(plain, zoomed)
            }
        }
    }

    @Test
    fun fractionalZoomAndPanKeepTheRectangleOnWholeViewPixels() {
        for (zoom in listOf(1.25f, 1.5625f, 1.953125f)) {
            for ((panX, panY) in listOf(0f to 0f, 12.25f to -19.75f)) {
                val layout = VideoLayout.fitCentered(1080, 2400, 1080, 2400, zoom, panX, panY)
                for ((name, value) in listOf(
                    "left" to layout.contentLeft,
                    "top" to layout.contentTop,
                    "width" to layout.contentWidth,
                    "height" to layout.contentHeight,
                )) {
                    assertEquals(
                        "$name must describe the integer SurfaceView bounds at zoom=$zoom, pan=($panX,$panY)",
                        value.toInt().toFloat(),
                        value,
                        0f,
                    )
                }
            }
        }
    }

    @Test
    fun zoomingEnlargesTheContentAboutTheCentre() {
        val plain = VideoLayout.fitCentered(1000, 1000, 500, 1000)
        val zoomed = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 0f)

        assertEquals(plain.contentWidth * 2f, zoomed.contentWidth, 0.01f)
        assertEquals(plain.contentHeight * 2f, zoomed.contentHeight, 0.01f)
        // Centre of the content stays on the centre of the view.
        val plainCentreX = plain.contentLeft + plain.contentWidth / 2f
        val zoomedCentreX = zoomed.contentLeft + zoomed.contentWidth / 2f
        assertEquals(plainCentreX, zoomedCentreX, 0.01f)
    }

    @Test
    fun aZoomedTouchStillSelectsThePixelUnderTheFinger() {
        // 500x1000 video in a 1000x1000 view fits at 1:1 with bars either side; at 2x it is
        // 1000x2000 and fills the width.
        val zoomed = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 0f)
        assertEquals(0f, zoomed.contentLeft, 0.01f)
        assertEquals(1000f, zoomed.contentWidth, 0.01f)
        assertEquals(2f, zoomed.scaleX, 0.0001f)
        // Two view pixels per video pixel now.
        assertEquals(0, zoomed.toDeviceX(0f))
        assertEquals(1, zoomed.toDeviceX(2f))
        assertEquals(499, zoomed.toDeviceX(999f))
    }

    @Test
    fun panMovesTheContentAndTheMappingTogether() {
        val centred = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 0f)
        // Vertically the content is 2000 tall in a 1000 view, so there is 500 of slack each way.
        val panned = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 500f)
        assertEquals(centred.contentTop + 500f, panned.contentTop, 0.01f)
        // Panning down by the full slack brings the top edge of the video to the top of the view.
        assertEquals(0f, panned.contentTop, 0.01f)
        assertEquals(0, panned.toDeviceY(0f))
    }

    @Test
    fun panIsClampedSoTheContentCannotBeDraggedAwayFromTheViewport() {
        // Far more pan than there is slack, in both directions.
        val over = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 99999f)
        assertEquals(0f, over.contentTop, 0.01f)
        val under = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, -99999f)
        // Bottom edge of the content lands on the bottom of the view.
        assertEquals(1000f, under.contentTop + under.contentHeight, 0.01f)

        // Across an axis with no slack at all, pan must be ignored rather than drifting.
        assertEquals(
            VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 0f, 0f).contentLeft,
            VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, 9999f, 0f).contentLeft,
            0.01f,
        )
    }

    @Test
    fun aNonFiniteOrAbsurdZoomCannotProduceAnInvalidLayout() {
        // Guarding here rather than at every call site: a NaN that reached the surface layout
        // would size the view to nothing and lose the stream with no obvious cause.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -3f)) {
            val layout = VideoLayout.fitCentered(1000, 1000, 500, 1000, bad, 0f, 0f)
            assertTrue("zoom=$bad produced an invalid layout", layout.isValid)
        }
        val nanPan = VideoLayout.fitCentered(1000, 1000, 500, 1000, 2f, Float.NaN, Float.NaN)
        assertTrue(nanPan.isValid)
        assertTrue(nanPan.contentLeft.isFinite() && nanPan.contentTop.isFinite())
    }
}