package com.scrcpy.remote.session

import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.scrcpy.ScrcpyOptions

/**
 * The device's own pages about one of its apps: App info, and the question whether to uninstall it.
 *
 * Opened with the device's activity manager over the session's shell, because scrcpy's control
 * channel can launch an app but not one of the system's pages about it. Both pages belong to the
 * device, so the decision stays there: nothing here stops, clears or removes an app without the
 * device asking first.
 */
object DeviceAppPages {
    /** The device's explanation is kept for the log, but a stack trace is not an explanation. */
    const val MAX_DETAIL_LENGTH = 300

    enum class Page(internal val action: String, internal val options: String) {
        /**
         * Settings keeps App info in a task of its own, and on Android 16 `am start` alone only
         * brought that task back, showing whichever app's page was open there last.
         */
        APP_INFO("android.settings.APPLICATION_DETAILS_SETTINGS", "--activity-clear-task "),

        /**
         * The package installer's confirmation, which asks before anything is removed. It asks about
         * the app it is given every time, and clearing its task could take with it an install
         * question still waiting on the device.
         */
        UNINSTALL("android.intent.action.DELETE", ""),
    }

    sealed interface Result {
        data object Opened : Result

        /** [detail] is what the device said instead. */
        data class Failed(val detail: String) : Result
    }

    /**
     * What the activity manager says about the intent it was given; it may name any package. Both
     * braces are escaped: Android compiles patterns with ICU, which rejects a bare `}` the JVM allows.
     */
    private val INTENT = Regex("""Intent \{[^\}]*\}""")

    /**
     * The command that opens [page] for [packageName] on [displayId], or null when the name is not
     * a package name. The main display is left implicit: Android 7 has no `--display` option.
     */
    fun command(page: Page, packageName: String, displayId: Int): String? {
        if (displayId < 0 || !AppPackage.isPackageName(packageName)) return null
        val display = if (displayId == 0) "" else "--display $displayId "
        return "am start ${page.options}$display-a ${page.action} -d ${ScrcpyOptions.shellQuote("package:$packageName")}"
    }

    /**
     * Uninstall is never offered for an app that came with the device. Android 16 asks "Do you
     * want to uninstall this app?" even about its own Settings, so that question is no safeguard.
     */
    fun offered(app: DeviceApp): List<Page> =
        if (app.system) listOf(Page.APP_INFO) else listOf(Page.APP_INFO, Page.UNINSTALL)

    /**
     * Whether the session shows one of the device's own displays, which is where a page opens.
     * A display made for the session has an id only the device knows, and a camera has no screen.
     */
    fun availableFor(settings: MirrorSettings?): Boolean =
        settings != null && settings.virtualDisplay == null && settings.camera == null && settings.displayId >= 0

    /**
     * Reads what `am start` printed.
     *
     * Android 8 refuses with an `Error:` or `Security exception:` line; Android 16 with an exception
     * and its stack trace. Either repeats the intent it was given, which can name a package called
     * anything, so the intent's own description is not read for words like "Error".
     */
    fun parse(output: String): Result {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val reasons = lines.filterNot { it.startsWith("at ") }.filter { line ->
            val said = INTENT.replace(line, "Intent {}")
            said.startsWith("Error") || said.contains("exception", ignoreCase = true)
        }
        if (reasons.isNotEmpty()) return Result.Failed(bounded(reasons.joinToString(" ")))
        // `am start` always says what it is starting, so silence means the command never ran.
        if (lines.none { it.startsWith("Starting:") }) return Result.Failed(bounded(lines.joinToString(" ")))
        return Result.Opened
    }

    private fun bounded(text: String): String =
        if (text.length <= MAX_DETAIL_LENGTH) text else text.take(MAX_DETAIL_LENGTH - 1).trimEnd() + "…"

    /**
     * The command that lists [packageName] if the device still has it, or null when the name is
     * not a package name. `pm list packages` matches by substring, so [stillInstalled] reads it.
     */
    fun installedCheckCommand(packageName: String): String? =
        if (AppPackage.isPackageName(packageName)) "pm list packages ${ScrcpyOptions.shellQuote(packageName)}" else null

    /**
     * Whether `pm list packages` says [packageName] is still installed, or null when it answered
     * anything but a package list. Its errors arrive with the list, and an error must never take
     * an app off the list. A package that is gone gets no line at all.
     */
    fun stillInstalled(output: String, packageName: String): Boolean? {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.any { !it.startsWith("package:") }) return null
        return lines.any { it == "package:$packageName" }
    }
}
