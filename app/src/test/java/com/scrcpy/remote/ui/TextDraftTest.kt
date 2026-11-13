package com.scrcpy.remote.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDraftTest {
    @Test
    fun rejectedInputKeepsTheEntireDraftAndSelection() {
        val draft = TextFieldValue("Keep this text", TextRange(3, 7), TextRange(0, 4))
        assertEquals(draft, commitTextDraft(draft) { false })
    }

    @Test
    fun sendsUnicodeOnceAndClearsLocalComposition() {
        val messages = mutableListOf<String>()
        val text = "한글 e\u0301 \uD83D\uDE00"
        val draft = TextFieldValue(text, selection = TextRange(text.length), composition = TextRange(0, 2))
        val result = commitTextDraft(draft) { messages += it; true }
        assertEquals(listOf(text), messages)
        assertEquals("", result.text)
        assertEquals(TextRange.Zero, result.selection)
        assertNull(result.composition)
    }

    @Test
    fun doesNotReplayKoreanPreedit() {
        val messages = mutableListOf<String>()
        var draft = TextFieldValue()
        for (preedit in listOf("ㅎ", "하", "한")) {
            draft = TextFieldValue(preedit, composition = TextRange(0, preedit.length))
        }
        assertTrue(messages.isEmpty())
        commitTextDraft(draft) { messages += it; true }
        assertEquals(listOf("한"), messages)
    }

    @Test
    fun localDeletionAndReplacementOnlySendFinalDraft() {
        val messages = mutableListOf<String>()
        val result = commitTextDraft(TextFieldValue("A\uD83D\uDE00B").copy(text = "A한")) { messages += it; true }
        assertEquals(listOf("A한"), messages)
        assertEquals("", result.text)
    }

    @Test
    fun emptyDraftDoesNotSend() {
        val messages = mutableListOf<String>()
        assertEquals(TextFieldValue(), commitTextDraft(TextFieldValue()) { messages += it; true })
        assertTrue(messages.isEmpty())
    }

    @Test
    fun whitespaceIsNotSilentlyDiscarded() {
        val messages = mutableListOf<String>()
        commitTextDraft(TextFieldValue("  ")) { messages += it; true }
        assertEquals(listOf("  "), messages)
    }

    @Test
    fun aRejectedDraftCanBeExplicitlyRetriedWithoutLosingOrDuplicatingText() {
        val messages = mutableListOf<String>()
        val original = TextFieldValue("Retry this draft", TextRange(2, 6))
        val kept = commitTextDraft(original) { false }
        val cleared = commitTextDraft(kept) { messages += it; true }
        assertEquals(listOf(original.text), messages)
        assertEquals("", cleared.text)
    }
}
