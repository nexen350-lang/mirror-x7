package com.scrcpy.remote.session

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Stands in for the app on the other side of a share. Debug builds only.
 *
 * [ShareCache] accepts `content://` URIs and nothing else, so a test cannot hand it a `file://`
 * path to a scratch file. This serves one the way a gallery or a file manager would: an
 * [openFile] descriptor for the bytes and a [OpenableColumns.DISPLAY_NAME] row for the name.
 *
 * It lives in the `debug` source set rather than `androidTest` so it runs inside the app's own
 * package — an exported provider in the test APK is started in a process without the app's
 * classloader. It is never merged into a release build.
 */
class TestShareProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        if ((accessMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
            beforeOpenOutput?.invoke(uri)
        } else {
            openInput?.let { return it(uri, signal) }
        }
        return ParcelFileDescriptor.open(fileFor(uri), accessMode)
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor = openAssetInput?.invoke(uri, signal)
        ?: AssetFileDescriptor(openFile(uri, "r", signal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        beforeQuery?.invoke(uri)
        val file = fileFor(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(file.name, file.length()))
        }
    }

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    private fun fileFor(uri: Uri) = File(directory(context!!), uri.lastPathSegment.orEmpty())

    companion object {
        const val AUTHORITY = "com.scrcpy.remote.debug.shares"

        /** Lets a test hold the metadata lookup after the shared bytes have been copied. */
        @Volatile var beforeQuery: ((Uri) -> Unit)? = null

        /** Runs before a writable descriptor is opened, so export tests can gate the handoff. */
        @Volatile var beforeOpenOutput: ((Uri) -> Unit)? = null

        @Volatile var openInput: ((Uri, CancellationSignal?) -> ParcelFileDescriptor)? = null
        @Volatile var openAssetInput: ((Uri, CancellationSignal?) -> AssetFileDescriptor)? = null

        fun directory(context: Context): File =
            File(context.cacheDir, "test-shares").apply { mkdirs() }

        /** Writes [content] under [name] and returns it as another app would offer it. */
        fun offer(context: Context, name: String, content: ByteArray): Uri {
            File(directory(context), name).writeBytes(content)
            return uriFor(name)
        }

        /** An absent name fails on read; opening it for output creates the fixture file. */
        fun uriFor(name: String): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build()

        fun clear(context: Context) {
            beforeQuery = null
            beforeOpenOutput = null
            openInput = null
            openAssetInput = null
            directory(context).listFiles()?.forEach { it.delete() }
        }
    }
}
