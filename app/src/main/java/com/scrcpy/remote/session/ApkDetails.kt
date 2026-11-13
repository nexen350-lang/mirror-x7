package com.scrcpy.remote.session

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import com.scrcpy.remote.R
import java.io.File

/**
 * What an app package says about itself, read on this phone before it goes anywhere.
 *
 * Shown before installing so the user can see which app they picked, and kept for afterwards: the
 * package name is how the installed app is found and opened on the device.
 */
data class ApkDetails(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val icon: Bitmap?,
) {
    companion object {
        private const val TAG = "ApkDetails"

        /**
         * Reads [file] as this phone's package manager would, or null when it cannot.
         *
         * Null does not mean the device cannot install it. This phone's Android can be older than
         * the app needs, and an old package manager refuses to read such an app at all; the
         * device decides for itself when it installs it.
         */
        fun read(context: Context, file: File, iconSizePx: Int): ApkDetails? {
            val manager = context.packageManager
            val info = try {
                archiveInfo(manager, file.path)
            } catch (e: Exception) {
                Log.w(TAG, "This phone could not read the app package", e)
                null
            } ?: return null
            val application = info.applicationInfo ?: return null
            // Resources are loaded from these paths, which are unset for an archive.
            application.sourceDir = file.path
            application.publicSourceDir = file.path
            val label = runCatching { application.loadLabel(manager).toString().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: info.packageName
            val icon = runCatching { application.loadIcon(manager).toBitmap(iconSizePx, iconSizePx) }
                .onFailure { Log.w(TAG, "Could not draw the app's icon", it) }.getOrNull()
            return ApkDetails(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName?.trim()?.takeIf { it.isNotEmpty() },
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                icon = icon,
            )
        }

        private fun archiveInfo(manager: PackageManager, path: String): PackageInfo? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageArchiveInfo(path, 0)
            }
    }
}

/** An app package waiting for the user to choose what happens to it. */
data class AppOffer(
    /** The file's name, as its source named it. */
    val name: String,
    val size: Long,
    /** Copying it to Download is offered too: the user asked to send a file, not to install one. */
    val canCopy: Boolean,
    /** Still being copied and read on this phone. */
    val reading: Boolean = true,
    /** Null while reading, or when this phone could not read it. */
    val app: ApkDetails? = null,
    /** Set when the file is an app bundle rather than a single APK. */
    val bundle: BundleSummary? = null,
)

/** What the offer says about an app bundle: how many files it holds, and why it cannot be installed. */
data class BundleSummary(
    val files: Int,
    val problem: AppBundle.Problem? = null,
    val unmatchable: List<String> = emptyList(),
) {
    /** Why the bundle cannot be installed from here, in the user's words; null when it can be. */
    fun problemText(context: Context): String? = when (problem) {
        null -> null
        AppBundle.Problem.EXPANSION_FILES -> context.getString(R.string.install_bundle_expansion)
        AppBundle.Problem.UNMATCHABLE_PARTS ->
            context.getString(R.string.install_bundle_unmatchable, unmatchable.joinToString(", "))
        AppBundle.Problem.UNREADABLE -> context.getString(R.string.install_bundle_unreadable)
    }
}

/** How an install ended, shown until the user closes it. */
data class AppInstallOutcome(
    /** The app's label when this phone could read it, otherwise the file's name. */
    val name: String,
    val packageName: String?,
    val result: ApkInstall.Result,
)
