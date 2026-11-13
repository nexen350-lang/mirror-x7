package com.scrcpy.remote

import com.scrcpy.remote.session.LiveTyping
import com.scrcpy.remote.session.LiveTyping.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTypingTest {

    /** Applies edits to a model device field whose cursor starts at the end of [start]. */
    private fun apply(start: String, edits: List<Edit>): String {
        val text = start.codePoints().toArray().toMutableList()
        var cursor = text.size
        for (edit in edits) when (edit) {
            is Edit.Move -> cursor = (cursor + edit.by).coerceIn(0, text.size)
            is Edit.Delete -> repeat(edit.count) { if (cursor > 0) text.removeAt(--cursor) }
            is Edit.Insert -> edit.text.codePoints().forEach { text.add(cursor++, it) }
            is Edit.Key -> Unit
        }
        return String(text.toIntArray(), 0, text.size)
    }

    private fun check(sent: String, current: String) =
        assertEquals(current, apply(sent, LiveTyping.diff(sent, current)))

    @Test fun eachKeystrokeIsSentAsItIsTyped() {
        assertEquals(listOf(Edit.Insert("h")), LiveTyping.diff("", "h"))
        assertEquals(listOf(Edit.Insert("i")), LiveTyping.diff("h", "hi"))
    }

    @Test fun backspaceBecomesDelete() {
        assertEquals(listOf(Edit.Delete(1)), LiveTyping.diff("hello", "hell"))
    }

    @Test fun anAutocorrectReplacesOnlyTheWord() {
        val edits = LiveTyping.diff("teh ", "the ")
        check("teh ", "the ")
        assertTrue(edits.first() is Edit.Move)
    }

    @Test fun editsInTheMiddleMoveTheRemoteCursorAndBack() {
        assertEquals(
            listOf(Edit.Move(-5), Edit.Insert("big "), Edit.Move(5)),
            LiveTyping.diff("a world", "a big world"),
        )
        check("a world", "a big world")
        check("abcdef", "abXYef")
        check("anything", "")
        check("", "everything")
    }

    @Test fun nothingChangedSendsNothing() {
        assertTrue(LiveTyping.diff("same", "same").isEmpty())
    }

    @Test fun countsAreInCodePointsNotUtf16Units() {
        assertEquals(listOf(Edit.Delete(1)), LiveTyping.diff("a😀", "a"))
        check("😀😀", "😀x😀")
        check("한글", "한국어")
    }

    @Test fun textBeingComposedIsHeldBack() {
        assertEquals("안녕", LiveTyping.committed("안녕하", 2..2))
        assertEquals("안녕하", LiveTyping.committed("안녕하", null))
        // Latin "composition" is only a correction offer: send it.
        assertEquals("abXcd", LiveTyping.committed("abXcd", 2..2))
        assertEquals("one two", LiveTyping.committed("one two", 4..6))
    }

    @Test fun onlyAsciiIsInjectedAsKeys() {
        assertTrue(LiveTyping.typeable("Hello, world! 123 ~"))
        assertFalse(LiveTyping.typeable("café"))
        assertFalse(LiveTyping.typeable("안녕"))
        assertFalse(LiveTyping.typeable("tab\there"))
    }

    @Test fun keystrokesInTheMiddleCoalesceIntoOneTrip() {
        val perKey = listOf("a wo", "a bwo", "a biwo", "a bigwo").zipWithNext { a, b -> LiveTyping.diff(a, b) }.flatten()
        assertEquals(listOf(Edit.Move(-2), Edit.Insert("big"), Edit.Move(2)), LiveTyping.coalesce(perKey))
        assertEquals("a bigwo", apply("a wo", LiveTyping.coalesce(perKey)))
    }

    @Test fun coalescingKeepsTheResult() {
        val states = listOf("", "h", "he", "hel", "he", "hex", "a hex", "a hex!", "a he!")
        val edits = states.zipWithNext { a, b -> LiveTyping.diff(a, b) }.flatten()
        assertEquals("a he!", apply("", LiveTyping.coalesce(edits)))
        assertTrue(LiveTyping.coalesce(edits).size < edits.size)
    }
}
