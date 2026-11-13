package com.scrcpy.remote.scrcpy

/** UTF-8 byte limits without splitting code points or silently replacing malformed UTF-16. */
internal object Utf8Text {
    fun chunks(text: String, maxBytes: Int): List<String> {
        require(maxBytes > 0) { "Chunk size must be positive" }
        val chunks = ArrayList<String>()
        var start = 0
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val size = codePointBytes(text, index)
            require(size <= maxBytes) { "Code point exceeds chunk byte limit" }
            if (bytes > maxBytes - size) {
                chunks.add(text.substring(start, index))
                start = index
                bytes = 0
            }
            bytes += size
            index += if (size == 4) 2 else 1
        }
        if (start < text.length) chunks.add(text.substring(start))
        return chunks
    }

    fun truncate(text: String, maxBytes: Int): String {
        require(maxBytes >= 0) { "Byte limit must not be negative" }
        var index = 0
        var end = 0
        var bytes = 0
        var full = false
        while (index < text.length) {
            val size = codePointBytes(text, index)
            index += if (size == 4) 2 else 1
            if (!full && bytes <= maxBytes - size) {
                bytes += size
                end = index
            } else {
                full = true
            }
            // Continue validating even the discarded suffix; malformed input is never silent.
        }
        return text.substring(0, end)
    }

    fun encode(text: String, maxBytes: Int = Int.MAX_VALUE): ByteArray {
        require(maxBytes >= 0) { "Byte limit must not be negative" }
        var index = 0
        var bytes = 0
        while (index < text.length) {
            val size = codePointBytes(text, index)
            require(bytes <= maxBytes - size) { "UTF-8 text exceeds $maxBytes bytes" }
            bytes += size
            index += if (size == 4) 2 else 1
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun codePointBytes(text: String, index: Int): Int {
        val char = text[index]
        return when {
            char.isHighSurrogate() -> {
                require(index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    "Unpaired high surrogate at UTF-16 index $index"
                }
                4
            }
            char.isLowSurrogate() -> throw IllegalArgumentException(
                "Unpaired low surrogate at UTF-16 index $index",
            )
            char.code <= 0x7F -> 1
            char.code <= 0x7FF -> 2
            else -> 3
        }
    }
}
