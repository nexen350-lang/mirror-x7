package com.scrcpy.remote

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.ui.panelTileWidth
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * The control panel sizes its tiles so whole columns fill its width, and FlowRow lays them out.
 * FlowRow ends a row using the gap rounded up to a whole pixel, and places tiles using the gap
 * rounded to the nearest. At densities where the two differ, such as 2.55 where 8 dp is 20.4 px,
 * tiles planned on the nearest pixel left the last tile of every full row on the next line, and an
 * empty column at the end of each row.
 */
class PanelTileWidthTest {
    @Test fun noRowEndsWithAnEmptyColumnAtAnyDensity() {
        // Whole-number densities, and the in-between ones Display size and Smallest width give.
        val dpis = listOf(120, 160, 213, 240, 280, 320, 340, 360, 382, 400, 408, 420, 440, 446, 450, 480, 560, 640, 666)
        for (dpi in dpis) {
            val density = Density(dpi / 160f)
            with(density) {
                val minTile = 72.dp.toPx()
                val wrapGap = ceil(8.dp.toPx()).toInt()
                val placeGap = 8.dp.roundToPx()
                // Up to the panel's own cap of 560 dp.
                for (width in ceil(minTile).toInt()..560.dp.roundToPx()) {
                    val tile = panelTileWidth(width.toDp(), density).roundToPx()
                    // How many tiles FlowRow puts on a row, and the space left after them.
                    val columns = (width + wrapGap) / (tile + wrapGap)
                    val leftover = width - columns * tile - (columns - 1) * placeGap
                    val where = "$dpi dpi, $width px: $columns tiles of $tile px leave $leftover px"
                    assertTrue("Tiles stay at least 72 dp wide: $where", tile >= minTile - 1f)
                    assertTrue("No empty column at the end of a row: $where", leftover < tile / 2)
                }
            }
        }
    }
}
