package com.scrcpy.remote.scrcpy

import java.io.IOException

/** One display the device reported, with the size it is capturing at when known. */
data class DeviceDisplay(val id: Int, val width: Int, val height: Int) {
    /** The device reports `size unknown` for a display it cannot measure. */
    val sizeKnown: Boolean get() = width > 0 && height > 0
}

/**
 * Reads the display list the scrcpy server prints for `list_displays=true`.
 *
 * The server formats each entry as the command-line flag a desktop user would pass, followed by
 * the size in brackets:
 *
 * ```
 * List of displays:
 *     --display-id=0    (1080x2400)
 *     --display-id=2    (size unknown)
 * ```
 *
 * A device with nothing to report prints `(none)` instead of any entry, which is a successful
 * listing rather than a failure.
 *
 * Pure so the format can be tested without a device.
 */
object DeviceDisplayListParser {

    /** Header the server prints before the entries. */
    const val HEADER = "List of displays:"

    private val ENTRY = Regex("""--display-id=(\d+)\s*\((?:(\d+)x(\d+)|size unknown)\)""")

    fun parse(output: String): List<DeviceDisplay> {
        val displays = mutableListOf<DeviceDisplay>()
        for (raw in output.lineSequence()) {
            val match = ENTRY.find(raw.trimEnd('\r')) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val width = match.groupValues[2].toIntOrNull() ?: 0
            val height = match.groupValues[3].toIntOrNull() ?: 0
            displays += DeviceDisplay(id, width, height)
        }
        // A device can repeat an id across the listing; keep the first description of each.
        return displays.distinctBy { it.id }
    }

    /**
     * Parses server output, rejecting output that never reached the listing stage so the caller
     * reports a failure instead of a device that appears to have no displays at all.
     */
    @Throws(IOException::class)
    fun parseServerOutput(output: String): List<DeviceDisplay> {
        if (!output.contains(HEADER)) {
            throw IOException(
                "The device did not return its displays: " +
                    output.trim().ifBlank { "no output" }.take(200)
            )
        }
        return parse(output)
    }
}
