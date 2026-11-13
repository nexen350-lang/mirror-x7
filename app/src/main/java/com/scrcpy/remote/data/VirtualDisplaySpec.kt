package com.scrcpy.remote.data

/**
 * The `new_display` value the scrcpy server accepts, which decides the size and density of the
 * virtual display it creates.
 *
 * The server's own grammar is:
 * ```
 *   ""                        same size and density as the device's own display
 *   "<width>x<height>"        explicit size, device density
 *   "<width>x<height>/<dpi>"  explicit size and density
 *   "/<dpi>"                  device size, explicit density
 * ```
 * A malformed value makes the server throw before any socket is opened, which the client can only
 * report as a failed connection. Validating here turns that into an explanation next to the field.
 */
object VirtualDisplaySpec {
    val SIZE_RANGE = 160..7680
    val DPI_RANGE = 60..1000

    private val SIZE = Regex("(\\d{1,5})x(\\d{1,5})")

    /** Canonical form: whitespace removed and the separator lowercased. */
    fun normalize(spec: String): String = spec.trim().replace('X', 'x').replace(" ", "")

    /** Null when [spec] is usable as a `new_display` value, otherwise why it is not. */
    fun error(spec: String): String? {
        val value = normalize(spec)
        if (value.isEmpty()) return null
        if (value.count { it == '/' } > 1) return "Use at most one \"/\" before the density"
        val sizePart = value.substringBefore('/')
        val dpiPart = if ('/' in value) value.substringAfter('/') else null

        if (sizePart.isNotEmpty()) {
            val match = SIZE.matchEntire(sizePart)
                ?: return "Size must look like 1280x720"
            val (width, height) = match.destructured
            for (side in listOf(width, height)) {
                val number = side.toIntOrNull()
                if (number == null || number !in SIZE_RANGE) {
                    return "Width and height must be between ${SIZE_RANGE.first} and ${SIZE_RANGE.last}"
                }
            }
        }
        if (dpiPart != null) {
            if (dpiPart.isEmpty()) return "Add a density after \"/\", for example /240"
            val dpi = dpiPart.toIntOrNull()
            if (dpi == null || dpi !in DPI_RANGE) {
                return "Density must be between ${DPI_RANGE.first} and ${DPI_RANGE.last}"
            }
        }
        return null
    }
}
