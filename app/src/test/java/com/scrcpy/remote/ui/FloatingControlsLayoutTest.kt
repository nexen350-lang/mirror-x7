package com.scrcpy.remote.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingControlsLayoutTest {
    @Test
    fun tileMinWidthIsFixedUpToLargeFontScaleThenGrowsCapped() {
        assertEquals(72.dp, panelTileMinWidth(1f))
        assertEquals(72.dp, panelTileMinWidth(1.3f))
        assertTrue(panelTileMinWidth(2f) > 100.dp)
        assertEquals(120.dp, panelTileMinWidth(4f))
    }

    @Test
    fun tilesGetWiderAtLargeFontScale() {
        val normal = panelTileWidth(360.dp, Density(2f, 1f))
        val large = panelTileWidth(360.dp, Density(2f, 2f))
        assertTrue(large > normal)
    }

    @Test
    fun kbpsUsesInjectedUnits() {
        assertEquals("850 kbit", formatKbps(850, "%1\$.1f Mbit", "%1\$d kbit"))
        assertEquals("8.2 Mbit", formatKbps(8_200, "%1\$.1f Mbit", "%1\$d kbit"))
        assertEquals("850 kb/s", formatKbps(850))
    }
}
