package com.scrcpy.remote.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.scrcpy.remote.ui.screenshot.ArtefactSharing
import java.io.File
import java.util.UUID

/**
 * Holds a file another app shared until a device has been chosen for it.
 *
 * A share's read grant belongs to the activity that received the intent and is gone by the time
 * the session activity exists, so the bytes are copied into this app's own cache while the grant
 * is still valid. Everything downstream then works with an ordinary private file.
 */
object ShareCache {
    private const val TAG = "ShareCache"
    private const val DIRECTORY = "shared-uploads"
    private const val BUFFER_BYTES = 64 * 1024

    /** How long an unused copy is kept before a later share may clean it up. */
    private const val STALE_MILLIS = 6L * 60 * 60 * 1000

    /** Upper bound on everything held here at once; the oldest copies go first. */
    internal const val MAX_TOTAL_BYTES = 1L shl 30

    data class Copied(val path: String, val name: String)

    /**
     * Copies [uri] into the cache, or null if it is not something another app may share.
     *
     * Call off the main thread. [maxBytes] exists so tests can exercise the bound without
     * writing half a gigabyte. [isCancelled] is checked between chunks; a cancelled copy is
     * deleted and reported as null.
     */
    fun copyIn(
        context: Context,
        uri: Uri,
        maxBytes: Long = FileTransfers.MAX_BYTES,
        isCancelled: () -> Boolean = { false },
    ): Copied? = try {
        if (!isShareable(context, uri)) {
            // Any app can send this activity an ACTION_SEND, and openInputStream resolves a
            // `file://` URI with *this* app's permissions. Without this check a malicious app
            // could name a path inside this app's own private storage — the ADB private key, the
            // saved device list — and have the app dutifully copy it out and upload it to a
            // device. Only a content:// URI carries a grant that says its sender may read it.
            Log.w(TAG, "Refused a shared ${uri.scheme} URI; only content URIs may be shared in")
            null
        } else {
            copyContent(context, uri, maxBytes, isCancelled)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not take a copy of the shared file", e)
        null
    }

    /** Whether another app may hand this URI to the share target. */
    fun isShareable(context: Context, uri: Uri): Boolean =
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) &&
            // Our own non-exported FileProvider opens with our identity, so a sender could name
            // an artefact it holds no grant for.
            !uri.authority.equals(ArtefactSharing.authority(context), ignoreCase = true)

    private fun copyContent(context: Context, uri: Uri, maxBytes: Long, isCancelled: () -> Boolean): Copied? {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        sweepStale(directory)
        val target = File(directory, UUID.randomUUID().toString())
        var oversized = false
        var cancelled = false
        val written = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    // Stop at the limit rather than checking the length afterwards: the sender
                    // decides how many bytes to serve, and a stream with no end would otherwise
                    // fill the cache partition before anyone looked at how big it had become.
                    var copied = 0L
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            cancelled = true
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) {
                            oversized = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            // A share that dies partway through leaves a truncated file that would otherwise be
            // sent on as if it were whole.
            discard(target.absolutePath)
            throw e
        }
        return if (written == null || oversized || cancelled) {
            discard(target.absolutePath)
            if (oversized) Log.w(TAG, "Shared file is larger than $maxBytes bytes")
            null
        } else {
            trimToTotal(directory, MAX_TOTAL_BYTES, keep = target)
            Copied(target.absolutePath, displayName(context, uri))
        }
    }

    /** Deletes oldest files (never [keep]) until the directory holds at most [maxTotal] bytes. */
    internal fun trimToTotal(directory: File, maxTotal: Long, keep: File? = null) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (file in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= maxTotal) break
            val size = file.length()
            discard(file.absolutePath)
            if (!file.exists()) total -= size
        }
    }

    /** Deletes a copy once it has been sent, or abandoned. */
    fun discard(path: String?) {
        if (path.isNullOrEmpty()) return
        try {
            val file = File(path)
            if (!file.delete() && file.exists()) Log.w(TAG, "Could not remove a cached shared upload")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not remove a cached shared upload", e)
        }
    }

    /**
     * Removes copies old enough that nothing can still be waiting to send them.
     *
     * This used to empty the directory on every incoming share, on the reasoning that only one
     * share is ever pending. That is not true: a copy can be waiting for a destination, already
     * handed to a session, or still being written by another share that arrived moments earlier.
     * Wiping the directory deleted those, leaving the app offering a file that no longer existed.
     *
     * Deleting only what has aged out keeps abandoned copies from accumulating — the realistic
     * source being a process that died between taking a copy and sending it — without touching
     * anything that could still be in use.
     */
    private fun sweepStale(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) discard(file.absolutePath)
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) {
                        cursor.getString(index)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared-file"
    }
}
