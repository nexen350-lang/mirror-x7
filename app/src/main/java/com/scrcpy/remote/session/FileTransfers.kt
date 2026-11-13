package com.scrcpy.remote.session

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.OpenableColumns
import android.util.Log
import com.scrcpy.remote.adb.AdbSync
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Source metadata and cancellation-owned input for document transfers. */
object FileTransfers {
    private const val TAG = "FileTransfers"
    /**
     * Transfers run over the same ADB connection as the live session's video and control streams,
     * so a very large one would stall mirroring for as long as it takes. The cap keeps a transfer
     * to something a user will wait through.
     */
    const val MAX_BYTES = 512L * 1024 * 1024

    data class Details(val name: String, val size: Long, val mimeType: String? = null)

    /**
     * What to say about a transfer the session's end cut off. "It is not on the device" is only
     * true until DONE: adbd discards a partial file, but once DONE may have reached it the file
     * may be saved and only its confirmation was lost. Byte counts cannot tell those apart: every
     * byte can be sent before DONE is, and an empty file is all DONE.
     *
     * An app is different: even a whole copy is only a file in a temporary folder until the
     * device's package manager has it, and from then on only the device knows how far it got.
     */
    @androidx.annotation.StringRes
    fun interruptedNotice(state: FileTransferState): Int = when {
        state.install && state.installing -> com.scrcpy.remote.R.string.install_interrupted_unconfirmed
        state.install -> com.scrcpy.remote.R.string.install_interrupted
        state.committing -> com.scrcpy.remote.R.string.send_file_unconfirmed
        else -> com.scrcpy.remote.R.string.send_file_interrupted
    }

    /** What to say about a transfer that failed while its session stayed up. */
    @androidx.annotation.StringRes
    fun failureNotice(error: Throwable): Int =
        if (generateSequence(error) { it.cause }.take(32).any { it is AdbSync.SyncCommitUnconfirmedException }) {
            com.scrcpy.remote.R.string.send_file_unconfirmed
        } else {
            com.scrcpy.remote.R.string.send_file_failed
        }

    /** The source can block independently of the ADB destination, so cancellation owns both. */
    internal fun <T> withSource(
        resolver: ContentResolver,
        uri: Uri,
        signal: CancellationSignal,
        onOpened: (Closeable) -> Unit,
        onClosed: (Closeable) -> Unit,
        consume: (InputStream) -> T,
    ): T {
        signal.throwIfCanceled()
        val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal)
            ?: throw IOException("The chosen file could not be opened")
        val stream = try {
            signal.throwIfCanceled()
            descriptor.createInputStream()
        } catch (error: Throwable) {
            try { descriptor.close() }
            catch (closeError: IOException) { error.addSuppressed(closeError) }
            throw error
        }
        // FileChannel supplies asynchronous close for a blocked pipe read on older Android.
        return TrackedInputStream(
            Channels.newInputStream(stream.channel), descriptor.declaredLength,
            checkSourceError = {
                val provider = descriptor.parcelFileDescriptor
                if (provider.canDetectErrors()) provider.checkError()
            },
            onClosed = onClosed,
        ).use { input ->
            onOpened(input)
            signal.throwIfCanceled()
            consume(input)
        }
    }

    /**
     * A provider's queried size is trusted only when positive: 0 and -1 are what providers answer
     * when they do not know, so the descriptor length gets the next say, and the queried value is
     * the last resort (a genuinely empty file).
     */
    internal fun chooseSize(queried: Long?, descriptorLength: () -> Long?): Long? {
        if (queried != null && queried > 0) return queried
        return descriptorLength()?.takeIf { it >= 0 } ?: queried
    }

    @Throws(IOException::class)
    fun describe(resolver: ContentResolver, uri: Uri, signal: CancellationSignal? = null): Details {
        signal?.throwIfCanceled()
        val queried = queryOpenableColumns(resolver, uri, signal)
        signal?.throwIfCanceled()
        val name = queried?.first?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
        // Not every provider implements OpenableColumns — MediaStore ids, custom providers and
        // file:// URIs all turn up without one — but the sync protocol needs an exact byte count
        // before the first chunk, so fall back to asking the descriptor itself.
        val size = chooseSize(queried?.second) { descriptorLength(resolver, uri, signal) }
            ?: throw IOException("The chosen file does not report its size")
        signal?.throwIfCanceled()
        if (size < 0) throw IOException("The chosen file reports an invalid size")
        // Only a hint: a provider may report any type, or none, so the name is checked as well.
        val mimeType = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching { resolver.getType(uri) }.getOrNull()
        } else {
            null
        }
        return Details(name, size, mimeType)
    }

    /**
     * Null when the provider answers nothing usable; a null size means only the size is missing.
     *
     * [signal] is passed to the provider so a cancelled transfer stops waiting on it. These calls
     * are ordinary blocking IPC: cancelling the coroutine around them does nothing until the
     * provider decides to answer, and the transfer slot stays reserved for exactly that long.
     */
    // Closed by use {}; lint's Recycle check does not follow a closeable through use on a named value.
    @android.annotation.SuppressLint("Recycle")
    private fun queryOpenableColumns(
        resolver: ContentResolver,
        uri: Uri,
        signal: CancellationSignal?,
    ): Pair<String?, Long?>? =
        runCatching {
            val found = resolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
                ?: return@runCatching null
            found.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    name to size
                }
        }.onFailure(::reportMetadataFallback).getOrNull()

    // Closed by use {}; lint's Recycle check does not follow a closeable through use on a named value.
    @android.annotation.SuppressLint("Recycle")
    private fun descriptorLength(resolver: ContentResolver, uri: Uri, signal: CancellationSignal?): Long? {
        signal?.throwIfCanceled()
        runCatching {
            val descriptor = resolver.openAssetFileDescriptor(uri, "r", signal) ?: return@runCatching null
            descriptor.use { it.length }
        }
            .onFailure(::reportMetadataFallback)
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?.let { return it }
        signal?.throwIfCanceled()
        return runCatching {
            val descriptor = resolver.openFileDescriptor(uri, "r", signal) ?: return@runCatching null
            descriptor.use { it.statSize }
        }
            .onFailure(::reportMetadataFallback)
            .getOrNull()
            ?.takeIf { it >= 0 }
    }

    private fun reportMetadataFallback(error: Throwable) {
        if (error is OperationCanceledException || error !is Exception) throw error
        Log.w(TAG, "Document metadata unavailable; trying the next supported lookup", error)
    }

    private class TrackedInputStream(
        input: InputStream,
        private var remaining: Long,
        private val checkSourceError: () -> Unit,
        private val onClosed: (Closeable) -> Unit,
    ) : FilterInputStream(input) {
        private val closed = AtomicBoolean()

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (remaining == 0L) return endOfSource()
            val count = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
            val read = `in`.read(bytes, offset, count)
            if (read < 0) return endOfSource()
            if (read > 0 && remaining >= 0) remaining -= read
            return read
        }

        private fun endOfSource(): Int {
            // Channel reads bypass AutoCloseInputStream's reliable-pipe EOF validation.
            checkSourceError()
            return -1
        }

        override fun skip(count: Long): Long {
            if (count <= 0) return 0
            val requested = if (remaining < 0) count else minOf(count, remaining)
            if (requested == 0L) return 0
            val buffer = ByteArray(minOf(requested, 8192).toInt())
            var skipped = 0L
            // Asset channels do not use consistent absolute/relative seek positions across APIs.
            while (skipped < requested) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), requested - skipped).toInt())
                if (read <= 0) break
                skipped += read
            }
            return skipped
        }

        override fun available(): Int =
            if (remaining < 0) `in`.available() else minOf(`in`.available().toLong(), remaining).toInt()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { super.close() }
            finally { onClosed(this) }
        }
    }

    /** Human-readable size used only in messages, so binary units are fine. */
    fun formatSize(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        // Round before choosing precision so 99.96 never prints as "100.0".
        val roundsToHundred = unit > 0 && value < 100 && Math.round(value * 10) >= 1000
        return if (roundsToHundred) {
            "100 ${units[unit]}"
        } else if (unit == 0 || value >= 100) {
            "${value.toLong()} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
