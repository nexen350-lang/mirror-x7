package com.scrcpy.remote.scrcpy

import java.io.IOException

/** One launchable application reported by the device. */
data class DeviceApp(val name: String, val packageName: String, val system: Boolean)

/**
 * Reads the app list the scrcpy server prints for `list_apps=true`.
 *
 * The server formats each entry as a `*` (system) or `-` (user) marker, the app name padded to a
 * 30 character column, then the package name. A name that reaches the column width does not fit,
 * so the server wraps the package onto the following line instead — both shapes are handled here,
 * because otherwise every long app name would silently lose its package.
 *
 * Pure so the format can be tested without a device.
 */
object DeviceAppListParser {

    /**
     * Header the server prints before the entries. Its absence means the list never ran — the
     * server aborts without any Java-level error when, for example, its jar is missing.
     */
    const val HEADER = "List of apps:"

    /**
     * One entry line: the marker, a single space, then the name column.
     *
     * The space is matched exactly once because upstream writes exactly one. Accepting a run of
     * them let a label that began with spaces move the apparent start of the name column, so the
     * package no longer sat where its offset said and the app was dropped.
     */
    private val ENTRY = Regex("^ *([*-]) (.*)$")

    /**
     * Column the server pads an app name to before writing the package name.
     *
     * Upstream `LogUtils.buildAppListMessage` pads to 30 and then writes a single space, so the
     * package always starts at the same offset. A name that reaches the column leaves no room and
     * is wrapped onto the following line instead.
     */
    private const val NAME_COLUMN = 30

    /**
     * Shape of a package name: dot-separated segments.
     *
     * Checked as well as the column because an app name is allowed to contain runs of spaces of
     * its own. A wrapped label such as `Files  Tools` would otherwise be split at its own spaces,
     * taking `Tools` for the package and dropping the real one on the next line.
     */
    private val PACKAGE_TOKEN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    /**
     * A package pushed onto its own line, which upstream indents by a fixed amount.
     *
     * `LogUtils` writes `"\n   "`, then the name column as spaces, then one more space, so the
     * continuation always begins at [NAME_COLUMN] + 4. Matching that exactly, rather than "some
     * indent", keeps an unrelated line of device output from being mistaken for a package.
     */
    private val WRAPPED_PACKAGE = Regex("^ {${NAME_COLUMN + 4},}($PACKAGE_TOKEN)$")

    fun parse(output: String): List<DeviceApp> {
        val apps = mutableListOf<DeviceApp>()
        val lines = output.lineSequence().map { it.trimEnd('\r') }.toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index++
            val entry = ENTRY.find(line) ?: continue
            val system = entry.groupValues[1] == "*"
            val name = entry.groups[2] ?: continue
            // The continuation is checked before the column, because the column alone cannot tell
            // a padded name followed by its package from a long label that merely happens to have
            // a space at the column and a package-shaped last word — for example a label ending
            // in "v1.2". Upstream only ever emits a continuation after a name that did not fit,
            // so its presence settles which of the two this is.
            val wrapped = lines.getOrNull(index)?.let { WRAPPED_PACKAGE.find(it)?.groupValues?.get(1) }
            if (wrapped != null) {
                apps += DeviceApp(name.value.trim(), wrapped, system)
                index++
                continue
            }
            // No continuation, so any package is on this line. A name with neither is dropped
            // rather than paired with an invented package.
            splitNameAndPackage(line, name.range.first)?.let { (title, packageName) ->
                apps += DeviceApp(title, packageName, system)
            }
        }
        return apps
    }

    /**
     * Splits an entry line into its name and package, or null when the package was wrapped.
     *
     * Both the offset and the shape have to agree. The offset alone would split a long label
     * whose own space happens to fall on the column, and the shape alone would split one whose
     * last word looks like a package.
     */
    private fun splitNameAndPackage(line: String, nameStart: Int): Pair<String, String>? {
        val packageStart = nameStart + NAME_COLUMN + 1
        if (line.length <= packageStart || line[packageStart - 1] != ' ') return null
        val candidate = line.substring(packageStart).trim()
        if (!PACKAGE_TOKEN.matches(candidate)) return null
        val name = line.substring(nameStart, packageStart - 1).trim()
        return if (name.isEmpty()) null else name to candidate
    }

    /**
     * Parses server output, rejecting output that never reached the listing stage so the caller
     * reports a failure instead of an empty device.
     */
    @Throws(IOException::class)
    fun parseServerOutput(output: String): List<DeviceApp> {
        if (!output.contains(HEADER)) {
            throw IOException(
                "The device did not return an app list: " +
                    output.trim().ifBlank { "no output" }.take(200)
            )
        }
        return parse(output)
    }
}
