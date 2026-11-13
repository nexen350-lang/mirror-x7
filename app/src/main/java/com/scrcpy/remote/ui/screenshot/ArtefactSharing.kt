package com.scrcpy.remote.ui.screenshot

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.scrcpy.remote.R
import com.scrcpy.remote.session.MirrorViewModel
import java.io.File
import java.io.IOException

/**
 * Hands a captured screenshot or recording to another app.
 *
 * Saving through the document picker and then hunting for the file in a gallery is a poor path
 * for the commonest thing anyone does with a screenshot of a remote device, which is send it to
 * somebody. This is the short way.
 *
 * The grant is deliberately narrow. [AUTHORITY] serves only the two artefact directories, the
 * intent carries a read grant and nothing else, and the URI is put in [ClipData] as well as in the
 * extra — some receivers read only one of the two, and a receiver that never reads the URI never
 * gets the grant at all.
 */
object ArtefactSharing {

    /** Must match the authority declared for the provider in the manifest. */
    const val AUTHORITY_SUFFIX = ".artefacts"

    /** Directory names the provider is configured to serve, mirrored here so tests can pin them. */
    val SHAREABLE_DIRECTORIES =
        setOf(ScreenshotCache.DIRECTORY, MirrorViewModel.RECORDING_DIRECTORY)

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /**
     * A content URI for [file], or null when it is not one of ours to share.
     *
     * The directory check is not merely a shortcut past an exception: it states the rule in one
     * place rather than leaving it implied by an XML file, so widening what can be shared takes a
     * deliberate edit here as well as there.
     */
    fun uriFor(context: Context, file: File): Uri? {
        if (!isShareable(context, file)) return null
        return try {
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (_: IllegalArgumentException) {
            // The provider refused the path; treat it exactly as "not ours", never as a crash.
            null
        }
    }

    /**
     * Whether [file] sits directly inside one of the directories the provider serves.
     *
     * Resolved through canonical paths so that a name containing `..` cannot walk out of the
     * cache and present some other file as an artefact.
     */
    fun isShareable(context: Context, file: File): Boolean = try {
        val cache = context.cacheDir.canonicalFile
        val parent = file.canonicalFile.parentFile
        parent != null &&
            parent.parentFile == cache &&
            parent.name in SHAREABLE_DIRECTORIES
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * A chooser that sends [file] to another app, or null when it cannot be shared.
     *
     * Returning null rather than an intent that will fail keeps the decision with the caller,
     * which is the only place that knows what to tell the user.
     */
    fun chooserFor(context: Context, file: File, mimeType: String): Intent? {
        val uri = uriFor(context, file) ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            // Receivers that read the clip instead of the extra — several do — otherwise get a
            // grant for a URI they never see.
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.artefact_share_title))
    }

    const val MIME_SCREENSHOT = "image/png"
    const val MIME_RECORDING = "video/mp4"
}
