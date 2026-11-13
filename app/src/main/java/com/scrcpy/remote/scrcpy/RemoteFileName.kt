package com.scrcpy.remote.scrcpy

import java.io.IOException

/**
 * Chooses the name a transferred file is given on the device.
 *
 * The remote path is concatenated into the sync protocol's `SEND` header and later into a
 * `SCAN_FILE` control message, so a name taken from a user-chosen document must not be able to
 * escape the target directory or carry separators. Anything outside a conservative set is
 * replaced rather than rejected: a file called `report 2024/final.pdf` should still transfer.
 */
object RemoteFileName {
    // A path on the *remote* device, sent over adb; this device's Environment says nothing about it.
    @android.annotation.SuppressLint("SdCardPath")
    const val TARGET_DIRECTORY = "/sdcard/Download"

    /**
     * Keeps names inside common filesystem limits, leaving room for a suffix.
     *
     * Counted in UTF-8 bytes, which is what the filesystem limits: ext4 and FAT both cap a name
     * at 255 bytes, and a name of Han or Cyrillic characters spends two or three bytes each. A
     * limit counted in characters would let a perfectly ordinary name fail to create on the
     * device.
     */
    private const val MAX_BYTES = 100

    private const val FALLBACK = "shared-file"

    /** Last numbered candidate checked before failing without overwriting a file. */
    private const val MAX_NAME_ATTEMPTS = 99

    /** Characters kept verbatim; everything else becomes `_`. */
    private fun isSafe(c: Char) = c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' || c == ' '

    private fun byteLength(value: String) = value.toByteArray(Charsets.UTF_8).size

    /** Longest prefix of [value] that fits in [budget] UTF-8 bytes, never splitting a character. */
    private fun takeBytes(value: String, budget: Int): String {
        if (budget <= 0) return ""
        var used = 0
        val builder = StringBuilder()
        for (character in value) {
            val size = byteLength(character.toString())
            if (used + size > budget) break
            builder.append(character)
            used += size
        }
        return builder.toString()
    }

    /**
     * Sanitizes [displayName] into a bare file name.
     *
     * Leading dots are dropped so a transfer cannot create a hidden file, and `..` can never
     * survive because separators are replaced. Dots and spaces are stripped together in one pass:
     * removing dots first and trimming afterwards uncovered any dot that had been sitting behind
     * a space, which let a name like `". .nomedia"` through as `.nomedia`.
     */
    fun sanitize(displayName: String): String {
        val mapped = displayName.map { if (isSafe(it)) it else '_' }
            .joinToString("")
            .trimStart { it == '.' || it.isWhitespace() }
            .trimEnd()
        if (mapped.isEmpty() || mapped.all { it == '_' || it == '.' }) return FALLBACK
        if (byteLength(mapped) <= MAX_BYTES) return mapped
        // Truncate the stem, not the extension, so the file still opens with the right app.
        val dot = mapped.lastIndexOf('.')
        val extension = if (dot > 0 && byteLength(mapped.substring(dot)) <= 12) mapped.substring(dot) else ""
        val stem = takeBytes(mapped, MAX_BYTES - byteLength(extension)).trimEnd()
        return (if (stem.isEmpty()) FALLBACK else stem) + extension
    }

    /** Absolute path a file with [displayName] is written to. */
    fun remotePath(displayName: String): String = "$TARGET_DIRECTORY/${sanitize(displayName)}"

    /**
     * Absolute path for [displayName] that nothing is using yet.
     *
     * [taken] answers whether a candidate already exists, or null when the device will not say.
     * An unknown answer is treated as taken: the whole point is to avoid writing over something,
     * and a name nobody can vouch for is not safe to write over. If the bounded search finds no
     * confirmed free name, the transfer fails before any destination is opened.
     */
    @Throws(IOException::class)
    fun freePath(displayName: String, taken: (String) -> Boolean?): String {
        val name = sanitize(displayName)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        for (attempt in 0..MAX_NAME_ATTEMPTS) {
            val candidate = if (attempt == 0) name else "$stem ($attempt)$extension"
            val path = "$TARGET_DIRECTORY/$candidate"
            if (taken(path) == false) return path
        }
        throw IOException("Could not confirm an unused name for $name after ${MAX_NAME_ATTEMPTS + 1} checks")
    }
}
