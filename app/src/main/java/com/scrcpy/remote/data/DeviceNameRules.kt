package com.scrcpy.remote.data

/** Shared rules for characters that make a label look like something it is not. */
internal object DeviceNameRules {
    /** Control, format (bidi, zero-width), separators, unpaired surrogates and blank-filler glyphs. */
    fun hasInvisibleOrSpoofing(value: String): Boolean {
        var index = 0
        var previous = -1
        while (index < value.length) {
            val cp = value.codePointAt(index)
            index += Character.charCount(cp)
            if (isInvisible(cp)) return true
            if (cp == ZWJ) {
                // A joiner is only meaningful between two visible code points (emoji sequences).
                if (previous < 0 || previous == ZWJ || index >= value.length) return true
                val next = value.codePointAt(index)
                if (next == ZWJ || isInvisible(next)) return true
            }
            previous = cp
        }
        return false
    }

    private const val ZWJ = 0x200D

    // U+200D joins emoji sequences (family, profession); the other format characters stay rejected.
    fun isInvisible(cp: Int): Boolean = cp != 0x200D && when (Character.getType(cp)) {
        Character.FORMAT.toInt(), Character.CONTROL.toInt(), Character.SURROGATE.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> isBlankFiller(cp)
    }

    // Variation selectors are not listed: emoji such as U+2764 U+FE0F need them.
    private fun isBlankFiller(cp: Int) = when (cp) {
        0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x17B4, 0x17B5, 0x034F -> true
        else -> false
    }
}
