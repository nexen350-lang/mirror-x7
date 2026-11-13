package com.scrcpy.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingHandlePositionTest {
    @Test
    fun clampsDraggingInsideSafeRange() {
        assertEquals(0f, normalizedHandlePosition(-100f, 24f, 600f), 0f)
        assertEquals(1f, normalizedHandlePosition(900f, 24f, 600f), 0f)
    }

    @Test
    fun relativePositionSurvivesWindowResize() {
        val fraction = normalizedHandlePosition(160f, 40f, 520f)
        assertEquals(0.25f, fraction, 0f)
        val resizedMinimum = 80f
        val resizedMaximum = 1040f
        assertEquals(320f, resizedMinimum + fraction * (resizedMaximum - resizedMinimum), 0f)
    }

    @Test
    fun collapsedSafeRangeDoesNotDivideByZero() {
        assertEquals(0.5f, normalizedHandlePosition(48f, 48f, 48f), 0f)
        assertEquals(0.5f, normalizedHandlePosition(48f, 60f, 48f), 0f)
    }
}
