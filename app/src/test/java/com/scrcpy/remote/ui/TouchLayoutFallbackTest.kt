package com.scrcpy.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TouchLayoutFallbackTest {
    @Test fun clearedLayoutFallsBackToTheFittedOne() {
        val layout = resolveTouchLayout(null, 1000, 500, 1000, 500, 1f, 0f, 0f)
        assertNotNull("A first touch right after a size/zoom change must still map", layout)
        assertEquals(1000f, layout!!.contentWidth, 0.5f)
    }

    @Test fun laidOutLayoutWinsWhenValid() {
        val laid = VideoLayout.fitCentered(1000, 500, 1000, 500, 1f, 0f, 0f)
        assertEquals(laid, resolveTouchLayout(laid, 1000, 500, 1000, 500, 1f, 0f, 0f))
    }

    @Test fun noVideoMeansNoLayout() {
        assertNull(resolveTouchLayout(null, 1000, 500, 0, 0, 1f, 0f, 0f))
    }
}
