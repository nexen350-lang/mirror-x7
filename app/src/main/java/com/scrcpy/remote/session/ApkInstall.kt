package com.scrcpy.remote.session

import androidx.annotation.StringRes
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import java.util.Locale
import java.util.UUID

/**
 * Installing an app package on the device: the commands it runs and what the answer means.
 *
 * The package is copied into the shell's temporary folder and installed from there with the
 * device's own package manager, which is what `adb install` did before streamed installs. That
 * works on every Android version a scrcpy server runs on, and the copy is the same push the app
 * already uses for files.
 */
object ApkInstall {
    const val MIME_TYPE = "application/vnd.android.package-archive"

    /** The device's own explanation is shown as written, but a stack trace is not an explanation. */
    const val MAX_DETAIL_LENGTH = 300

    private const val REMOTE_DIRECTORY = "/data/local/tmp"
    private const val REMOTE_PREFIX = "scrcpy-remote-install-"
    private val FAILURE = Regex("""Failure \[([A-Z][A-Z0-9_]*)(?::[^\]]*)?]""")
    private const val NOT_PARSEABLE = "Failed to parse APK file"

    sealed interface Result {
        data object Installed : Result

        /** The package manager ended without saying either way; the app may well be installed. */
        data object Unconfirmed : Result

        /**
         * [code] is the package manager's own, such as `INSTALL_FAILED_VERSION_DOWNGRADE`. [local] is
         * a refusal decided on this phone before anything was sent, whose [detail] is its own.
         */
        data class Refused(val reason: Refusal, val code: String?, val detail: String, val local: Boolean = false) : Result
    }

    enum class Refusal {
        NEWER_INSTALLED, SIGNATURE_MISMATCH, NO_SPACE, NEEDS_NEWER_ANDROID, WRONG_PROCESSOR, NOT_AN_APP,
        SPLIT_APP, BLOCKED_BY_DEVICE, VERIFICATION, CANCELLED_ON_DEVICE, OLD_TARGET, CONFLICT, UNDESCRIBED_DEVICE, OTHER,
    }

    /**
     * Reads what `pm install` printed.
     *
     * Android 8 says "Failure [CODE]" and Android 16 "Failure [CODE: explanation]". A file that is
     * not an app at all gets no code on either: Android 8 prints an `Error:` line and Android 16 an
     * exception with its stack trace, so the reason is recognised from the message both share.
     */
    fun parse(output: String): Result {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return Result.Unconfirmed
        val failure = lines.firstNotNullOfOrNull { line -> FAILURE.find(line)?.let { line to it.groupValues[1] } }
        if (failure != null) {
            val (line, code) = failure
            return Result.Refused(refusal(code), code, bounded(line))
        }
        if (lines.any { it == "Success" }) return Result.Installed
        // The device's own sentence rather than the exception wrapping it or its stack frames.
        val sentence = lines.firstOrNull { it.startsWith("Error:") }
            ?: lines.firstNotNullOfOrNull { line -> line.indexOf("Error:").takeIf { it >= 0 }?.let { line.substring(it) } }
            ?: lines.first()
        val reason = if (lines.any { NOT_PARSEABLE in it }) Refusal.NOT_AN_APP else Refusal.OTHER
        return Result.Refused(reason, null, bounded(sentence))
    }

    private fun refusal(code: String): Refusal = when (code) {
        "INSTALL_FAILED_VERSION_DOWNGRADE" -> Refusal.NEWER_INSTALLED
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
        "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> Refusal.SIGNATURE_MISMATCH
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> Refusal.NO_SPACE
        "INSTALL_FAILED_OLDER_SDK" -> Refusal.NEEDS_NEWER_ANDROID
        "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> Refusal.WRONG_PROCESSOR
        "INSTALL_FAILED_INVALID_APK" -> Refusal.NOT_AN_APP
        "INSTALL_FAILED_MISSING_SPLIT" -> Refusal.SPLIT_APP
        "INSTALL_FAILED_USER_RESTRICTED" -> Refusal.BLOCKED_BY_DEVICE
        "INSTALL_FAILED_VERIFICATION_FAILURE", "INSTALL_FAILED_VERIFICATION_TIMEOUT" -> Refusal.VERIFICATION
        "INSTALL_FAILED_ABORTED" -> Refusal.CANCELLED_ON_DEVICE
        "INSTALL_FAILED_DEPRECATED_SDK_VERSION" -> Refusal.OLD_TARGET
        "INSTALL_FAILED_DUPLICATE_PERMISSION", "INSTALL_FAILED_CONFLICTING_PROVIDER" -> Refusal.CONFLICT
        else -> if (code.startsWith("INSTALL_PARSE_FAILED_")) Refusal.NOT_AN_APP else Refusal.OTHER
    }

    private fun bounded(text: String): String =
        if (text.length <= MAX_DETAIL_LENGTH) text else text.take(MAX_DETAIL_LENGTH - 1).trimEnd() + "…"

    @StringRes
    fun message(refusal: Refusal): Int = when (refusal) {
        Refusal.NEWER_INSTALLED -> R.string.install_refused_newer
        Refusal.SIGNATURE_MISMATCH -> R.string.install_refused_signature
        Refusal.NO_SPACE -> R.string.install_refused_space
        Refusal.NEEDS_NEWER_ANDROID -> R.string.install_refused_android
        Refusal.WRONG_PROCESSOR -> R.string.install_refused_processor
        Refusal.NOT_AN_APP -> R.string.install_refused_not_app
        Refusal.SPLIT_APP -> R.string.install_refused_split
        Refusal.BLOCKED_BY_DEVICE -> R.string.install_refused_blocked
        Refusal.VERIFICATION -> R.string.install_refused_verification
        Refusal.CANCELLED_ON_DEVICE -> R.string.install_refused_cancelled
        Refusal.OLD_TARGET -> R.string.install_refused_old_target
        Refusal.CONFLICT -> R.string.install_refused_conflict
        Refusal.UNDESCRIBED_DEVICE -> R.string.install_refused_undescribed
        Refusal.OTHER -> R.string.install_refused_other
    }

    fun remotePath(token: String): String = "$REMOTE_DIRECTORY/$REMOTE_PREFIX$token.apk"

    /** A file of its own for each install, so two can never meet on the device. */
    fun newRemotePath(): String = remotePath(UUID.randomUUID().toString().replace("-", ""))

    /** `-r` replaces an installed copy; `-t` accepts test-only builds, as Android Studio makes them. */
    fun installCommand(remotePath: String): String = "pm install -r -t ${ScrcpyOptions.shellQuote(remotePath)}"

    fun removeCommand(remotePath: String): String = "rm -f ${ScrcpyOptions.shellQuote(remotePath)}"

    /**
     * Removes install copies stranded on the device, which happens only when the connection drops
     * after a copy lands and before it is removed. An hour old at least: anything younger may be
     * another install still in progress.
     */
    fun sweepCommand(): String =
        "find $REMOTE_DIRECTORY -maxdepth 1 -name '$REMOTE_PREFIX*.apk' -mmin +$STRANDED_MINUTES -exec rm -f {} +"

    private const val STRANDED_MINUTES = 60

    /** A name of its own for each bundle install's copies, so two installs can never meet. */
    fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Where the [index]th file of the bundle install [token] is copied: named like a single app's
     * copy, so the sweep also finds one a dropped connection stranded.
     */
    fun partPath(token: String, index: Int): String = "$REMOTE_DIRECTORY/$REMOTE_PREFIX$token-$index.apk"

    /** Removes every copy the bundle install [token] made, however many there were, in one command. */
    fun removePartsCommand(token: String): String =
        "rm -f ${ScrcpyOptions.shellQuote("$REMOTE_DIRECTORY/$REMOTE_PREFIX$token-")}*.apk"

    /**
     * Opens an install session, in which an app's files are installed together or not at all, as
     * `adb install-multiple` does. Android 8 and 16 both read each file from the shell's
     * temporary folder; `-r` and `-t` mean what they mean for a single app.
     */
    fun createSessionCommand(totalBytes: Long): String = "pm install-create -r -t -S $totalBytes"

    private val SESSION_ID = Regex("""\[(\d+)]""")

    /** The install session `pm install-create` opened ("Success: created install session [N]"), if it did. */
    fun sessionId(output: String): Int? =
        output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Success") }
            ?.let { SESSION_ID.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Puts one file into the session under a name of its own; the device's names for splits come from their manifests. */
    fun writeCommand(sessionId: Int, index: Int, size: Long, remotePath: String): String =
        "pm install-write -S $size $sessionId ${index}_part.apk ${ScrcpyOptions.shellQuote(remotePath)}"

    /** Whether `pm install-write` took the file ("Success: streamed N bytes"). */
    fun wrote(output: String): Boolean = output.lineSequence().any { it.trim().startsWith("Success") }

    fun commitCommand(sessionId: Int): String = "pm install-commit $sessionId"

    /** Drops a session that will not be committed, so its files do not wait on the device for days. */
    fun abandonCommand(sessionId: Int): String = "pm install-abandon $sessionId"

    /** Whether a file is a single app package; see [AppBundle.looksLikeBundle] for app bundles. */
    fun looksLikeApk(name: String?, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.lowercase(Locale.ROOT) == MIME_TYPE) return true
        val lower = name?.trim()?.lowercase(Locale.ROOT) ?: return false
        return lower.length > ".apk".length && lower.endsWith(".apk")
    }
}
