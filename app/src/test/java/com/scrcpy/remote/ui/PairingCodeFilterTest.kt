package com.scrcpy.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeFilterTest {
    @Test fun keepsOnlyAsciiDigitsAndTruncates() {
        assertEquals("123456", asciiDigitsOnly("12a3-4 5678", 6))
    }

    @Test fun rejectsNonAsciiDigits() {
        // Arabic-Indic, fullwidth and Devanagari digits satisfy Char.isDigit but not the endpoint check.
        assertEquals("1", asciiDigitsOnly("\u0661\uFF12\u0663\u0964" + "1", 6).let { it })
        assertEquals("", asciiDigitsOnly("\u0661\uFF12\u0663", 6))
    }
}
