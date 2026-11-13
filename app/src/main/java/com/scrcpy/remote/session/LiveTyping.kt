package com.scrcpy.remote.session

/**
 * Live keyboard: turns successive states of a local text field into the edits that reproduce them
 * on the device, as they happen, instead of sending a finished draft.
 *
 * The device's cursor is assumed to sit where this field's last edit left it. An edit away from the
 * end moves the remote cursor with arrow keys, applies the change and moves it back, so corrections
 * made with the IME's own suggestions land where they were made.
 *
 * Text still being composed by the IME (a Hangul syllable, a pinyin candidate) is not sent until the
 * IME commits it, so the device never receives half-built characters.
 */
object LiveTyping {

    sealed interface Edit {
        /** Moves the device's cursor: negative is left, positive is right, in characters. */
        data class Move(val by: Int) : Edit
        /** Deletes [count] characters before the device's cursor. */
        data class Delete(val count: Int) : Edit
        /** Types [text] at the device's cursor. */
        data class Insert(val text: String) : Edit
        /** A key or chord pressed once, in order with the edits around it. Never merged. */
        data class Key(val keycode: Int, val metaState: Int = 0) : Edit
    }

    /**
     * The edits that turn [sent] into [current]. Counts are in code points, which is what one
     * arrow or Delete press removes on Android for every script this field can produce.
     */
    fun diff(sent: String, current: String): List<Edit> {
        if (sent == current) return emptyList()
        val old = sent.codePoints().toArray()
        val new = current.codePoints().toArray()
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val deleted = old.size - prefix - suffix
        val inserted = String(new, prefix, new.size - prefix - suffix)
        return buildList {
            if (suffix > 0) add(Edit.Move(-suffix))
            if (deleted > 0) add(Edit.Delete(deleted))
            if (inserted.isNotEmpty()) add(Edit.Insert(inserted))
            if (suffix > 0) add(Edit.Move(suffix))
        }
    }

    /**
     * The part of [text] the IME has committed: everything except the [composing] range, which the
     * IME may still change. A composition in the middle keeps only the text before it, so later
     * text is not sent ahead of the character being built.
     */
    fun committed(text: String, composing: IntRange?): String {
        if (composing == null || composing.isEmpty()) return text
        // A Latin keyboard marks the word under the cursor as composing just to offer corrections,
        // and moves that mark back over earlier words; holding it back made the sent text shrink
        // and grow under the user. Only scripts built from parts (Hangul, kana, pinyin) need it.
        val start0 = composing.first.coerceIn(0, text.length)
        val end0 = (composing.last + 1).coerceIn(start0, text.length)
        if (text.substring(start0, end0).all { it.code < 0x250 }) return text
        val start = composing.first.coerceIn(0, text.length)
        return text.substring(0, start)
    }

    /**
     * Whether [text] can be typed with injected key events. The server's text injection maps
     * characters through the device's key map, which covers ASCII printable text; anything else
     * goes through the clipboard so every script arrives intact.
     */
    fun typeable(text: String): Boolean = text.all { it in ' '..'~' }

    /**
     * Merges a run of queued edits: adjacent moves add up (a "back, insert, forward" per keystroke
     * becomes one trip), adjacent inserts and deletes join, and moves that cancel out vanish. Fewer
     * round trips means fewer chances for the device's cursor and this field's to drift apart.
     */
    fun coalesce(edits: List<Edit>): List<Edit> {
        val out = ArrayList<Edit>()
        for (edit in edits) {
            val last = out.lastOrNull()
            when {
                edit is Edit.Move && last is Edit.Move -> {
                    out.removeAt(out.size - 1)
                    if (last.by + edit.by != 0) out.add(Edit.Move(last.by + edit.by))
                }
                edit is Edit.Insert && last is Edit.Insert -> out[out.size - 1] = Edit.Insert(last.text + edit.text)
                edit is Edit.Delete && last is Edit.Delete -> out[out.size - 1] = Edit.Delete(last.count + edit.count)
                edit is Edit.Move && edit.by == 0 -> Unit
                else -> out.add(edit)
            }
        }
        return out
    }

    /** Above this, the field is cleared after the next sync so diffs and remote arrows stay short. */
    const val RESET_LENGTH = 200
}
