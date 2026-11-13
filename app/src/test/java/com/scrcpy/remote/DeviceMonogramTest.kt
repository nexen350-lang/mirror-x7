package com.scrcpy.remote

import androidx.compose.ui.graphics.Color
import com.scrcpy.remote.ui.design.AvatarPalette
import com.scrcpy.remote.ui.design.avatarPaletteIndex
import com.scrcpy.remote.ui.design.avatarPaletteIndices
import com.scrcpy.remote.ui.design.deviceMonogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

/** What a saved device's avatar shows, and which colour it gets. */
class DeviceMonogramTest {
    @Test fun aNameGivesTheFirstLettersOfItsFirstTwoWords() {
        assertEquals("LR", deviceMonogram("Living room tablet"))
        assertEquals("OP", deviceMonogram("Office phone with a long descriptive name"))
        assertEquals("P9", deviceMonogram("Pixel 9"))
        assertEquals("T", deviceMonogram("tablet"))
        assertEquals("MP", deviceMonogram("my-phone"))
        assertEquals("GT", deviceMonogram("  galaxy   tab  "))
        assertEquals("ÉS", deviceMonogram("écran salon"))
    }

    @Test fun anAddressOrSymbolGetsTheDeviceIconRatherThanDigits() {
        // A device saved without a name is named after its address.
        assertNull(deviceMonogram("192.168.1.20"))
        assertNull(deviceMonogram("2001:db8::1"))
        assertNull(deviceMonogram("[2001:db8::1]"))
        assertNull(deviceMonogram("📱 Phone"))
        assertNull(deviceMonogram(""))
        assertNull(deviceMonogram("   "))
    }

    @Test fun anIpv6AddressThatStartsWithALetterGetsTheIconToo() {
        // Without a name these showed "F", the first letter of an address.
        assertNull(deviceMonogram("fd00::5"))
        assertNull(deviceMonogram("fe80::1%wlan0"))
        assertNull(deviceMonogram("FE80::A00:27FF:FE4E:66A1"))
        assertNull(deviceMonogram("abcd:ef01:2345:6789:abcd:ef01:2345:6789"))
        // A name that only has a colon in it is still a name.
        assertEquals("B", deviceMonogram("Bed:5"))
        assertEquals("KT", deviceMonogram("Kitchen: tablet"))
        assertEquals("C", deviceMonogram("cafe"))
    }

    @Test fun scriptsWithoutCaseShowOneCharacter() {
        // Two Hangul or Han characters side by side read as a word, not as initials.
        assertEquals("거", deviceMonogram("거실 태블릿"))
        assertEquals("客", deviceMonogram("客厅 平板"))
    }

    @Test fun aSurrogatePairIsNeverSplit() {
        // U+1D400, a letter outside the basic plane, takes two UTF-16 units.
        assertEquals("\uD835\uDC00", deviceMonogram("\uD835\uDC00bc"))
    }

    @Test fun eachDeviceKeepsItsColourAndTheColoursAreUsed() {
        val ids = (0 until 400).map { "device-$it-${it * 7919}" }
        for (id in ids) {
            val index = avatarPaletteIndex(id)
            assertTrue(index in 0 until AvatarPalette.SIZE)
            assertEquals("Stable for $id", index, avatarPaletteIndex(id))
        }
        // Spread across the palette rather than clustered on a few colours.
        val counts = ids.groupingBy(::avatarPaletteIndex).eachCount()
        assertEquals(AvatarPalette.SIZE, counts.size)
        assertTrue("Uneven: $counts", counts.values.all { it >= 400 / AvatarPalette.SIZE / 3 })
        // Negative hash codes still land in range.
        assertTrue(avatarPaletteIndex("polygenelubricants") in 0 until AvatarPalette.SIZE)
        // Short ids that differ only in their last characters do not all share a colour, as they
        // would with the hash code's low bits alone.
        assertTrue(listOf("a1", "b2", "c3").map(::avatarPaletteIndex).toSet().size > 1)
    }

    @Test fun upToEightDevicesInAListAllHaveTheirOwnColour() {
        // Chosen from the id alone, three devices shared a colour one time in three.
        val random = Random(7)
        repeat(500) {
            val ids = List(1 + random.nextInt(AvatarPalette.SIZE)) { UUID(random.nextLong(), random.nextLong()).toString() }
            val colours = avatarPaletteIndices(ids)
            assertEquals("One colour each for $ids", ids.size, ids.map(colours::getValue).toSet().size)
            // The first device has the colour its id picks.
            assertEquals(avatarPaletteIndex(ids.first()), colours.getValue(ids.first()))
        }
        // Past eight, every colour is still one of the palette's.
        val many = List(30) { "device-$it" }
        assertTrue(avatarPaletteIndices(many).values.all { it in 0 until AvatarPalette.SIZE })
        assertEquals(AvatarPalette.SIZE, many.take(AvatarPalette.SIZE).map(avatarPaletteIndices(many)::getValue).toSet().size)
    }

    @Test fun addingADeviceNeverChangesAnotherDevicesColour() {
        val random = Random(11)
        repeat(500) {
            val ids = List(1 + random.nextInt(12)) { UUID(random.nextLong(), random.nextLong()).toString() }
            val before = avatarPaletteIndices(ids)
            val after = avatarPaletteIndices(ids + UUID(random.nextLong(), random.nextLong()).toString())
            for (id in ids) assertEquals("$id after an addition", before.getValue(id), after.getValue(id))
        }
    }

    @Test fun aDeviceWhoseColourIsTakenGetsADistantOne() {
        // Neighbours in the palette are neighbouring hues; blue, indigo and purple side by side
        // hardly told three devices apart.
        val sameColour = (0 until 2000).map { "device-$it" }.groupBy(::avatarPaletteIndex).values.first { it.size >= 3 }
        val colours = avatarPaletteIndices(sameColour.take(3)).values.toList()
        for ((first, second) in listOf(colours[0] to colours[1], colours[1] to colours[2], colours[0] to colours[2])) {
            val apart = Math.floorMod(second - first, AvatarPalette.SIZE).let { minOf(it, AvatarPalette.SIZE - it) }
            assertTrue("Colours $colours are palette neighbours", apart >= 2)
        }
    }

    @Test fun everyAvatarsLettersStandOutFromItsFillInBothThemes() {
        for ((theme, pairs) in listOf("light" to AvatarPalette.light, "dark" to AvatarPalette.dark)) {
            assertEquals(theme, AvatarPalette.SIZE, pairs.size)
            pairs.forEachIndexed { index, (fill, letters) ->
                val ratio = contrast(fill, letters)
                // WCAG AA for normal text: the initials are small and semibold, not large text.
                assertTrue("$theme colour $index is only ${"%.2f".format(ratio)}:1", ratio >= 4.5)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (lighter, darker) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linear(channel: Float): Double =
            if (channel <= 0.04045f) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
