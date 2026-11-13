package com.scrcpy.remote.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class BackspacedTest {
    @Test fun cursorAtEnd() {
        val r = backspaced(TextFieldValue("abc", TextRange(3)))
        assertEquals("ab", r.text); assertEquals(TextRange(2), r.selection)
    }
    @Test fun midText() {
        val r = backspaced(TextFieldValue("abc", TextRange(2)))
        assertEquals("ac", r.text); assertEquals(TextRange(1), r.selection)
    }
    @Test fun surrogatePair() {
        val r = backspaced(TextFieldValue("a\uD83D\uDE00", TextRange(3)))
        assertEquals("a", r.text); assertEquals(TextRange(1), r.selection)
    }
    @Test fun selectionRange() {
        val r = backspaced(TextFieldValue("abcd", TextRange(1, 3)))
        assertEquals("ad", r.text); assertEquals(TextRange(1), r.selection)
    }
    @Test fun empty() {
        val r = backspaced(TextFieldValue(""))
        assertEquals("", r.text); assertEquals(TextRange(0), r.selection)
    }
}
