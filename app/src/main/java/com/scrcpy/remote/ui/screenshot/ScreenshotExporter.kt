package com.scrcpy.remote.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import com.scrcpy.remote.R
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class ScreenshotException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * @param cacheRoot where staged captures live. Overridable so a test can make the cache itself
 * fail, which is otherwise unreachable.
 */
class ScreenshotExporter(context: Context, cacheRoot: File = context.applicationContext.cacheDir) {
    private val context = context.applicationContext
    private val cache = ScreenshotCache(cacheRoot)
    private val callbackHandler = Handler(Looper.getMainLooper())

    /** Capture before opening the document picker; only the SurfaceView's video buffer is copied. */
    suspend fun capture(view: SurfaceView): PendingScreenshot {
        var bitmap: Bitmap? = null
        var lifecycle: PixelCopyLifecycle? = null
        var pending: PendingScreenshot? = null
        var delivered = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                val frame = view.holder.surfaceFrame
                if (!view.isAttachedToWindow || !view.holder.surface.isValid ||
                    view.width <= 0 || view.height <= 0 || frame.width() <= 0 || frame.height() <= 0
                ) {
                    throw error(R.string.screenshot_error_not_ready)
                }
                val size = boundedScreenshotSize(frame.width(), frame.height())
                val allocated = try {
                    Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                } catch (cause: OutOfMemoryError) {
                    throw error(R.string.screenshot_error_memory, cause)
                }
                bitmap = allocated
                val ownership = PixelCopyLifecycle(allocated::recycle)
                lifecycle = ownership
                val result = withTimeoutOrNull(COPY_TIMEOUT_MILLIS) {
                    copy(view, allocated, ownership)
                }
                if (result == null || result == PixelCopy.ERROR_TIMEOUT) {
                    throw error(R.string.screenshot_error_timeout)
                }
                if (result != PixelCopy.SUCCESS) {
                    throw ScreenshotException(context.getString(R.string.screenshot_error_copy, result))
                }
            }
            withContext(Dispatchers.IO) {
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                pending = cache.create { output ->
                    if (!requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw IOException("PNG encoder failed")
                    }
                    coroutineContext.ensureActive()
                }
            }
            currentCoroutineContext().ensureActive()
            delivered = true
            return requireNotNull(pending)
        } catch (cause: CancellationException) {
            failure = cause
            throw cause
        } catch (cause: ScreenshotException) {
            failure = cause
            throw cause
        } catch (cause: IOException) {
            throw error(R.string.screenshot_error_prepare, cause).also { failure = it }
        } catch (cause: SecurityException) {
            throw error(R.string.screenshot_error_prepare, cause).also { failure = it }
        } catch (cause: OutOfMemoryError) {
            throw error(R.string.screenshot_error_memory, cause).also { failure = it }
        } finally {
            lifecycle?.release()
            if (!delivered) pending?.let { cleanup(it, failure) }
        }
    }

    /**
     * Writes only to the user-selected URI. A failed write can leave a partial destination document;
     * it is not deleted, since a provider may have mapped the URI to existing user content.
     * The private cached image is discarded on success, failure, and cancellation.
     */
    // Closed by use {}; lint's Recycle check does not follow a closeable through use on a named value.
    @android.annotation.SuppressLint("Recycle")
    suspend fun export(pending: PendingScreenshot, uri: Uri) {
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                val source = cache.find(pending.token) ?: throw error(R.string.screenshot_error_expired)
                if (uri.scheme != "content" && uri.scheme != "file") {
                    throw error(R.string.screenshot_error_save, IllegalArgumentException("Unsupported destination"))
                }
                source.inputStream().use { input ->
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Document provider did not open the destination")
                    output.use {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            it.write(buffer, 0, count)
                        }
                        currentCoroutineContext().ensureActive()
                        it.flush()
                    }
                }
            }
        } catch (cause: CancellationException) {
            failure = cause
            throw cause
        } catch (cause: ScreenshotException) {
            failure = cause
            throw cause
        } catch (cause: IOException) {
            throw error(R.string.screenshot_error_save, cause).also { failure = it }
        } catch (cause: SecurityException) {
            throw error(R.string.screenshot_error_save, cause).also { failure = it }
        } catch (cause: IllegalArgumentException) {
            throw error(R.string.screenshot_error_save, cause).also { failure = it }
        } catch (cause: UnsupportedOperationException) {
            throw error(R.string.screenshot_error_save, cause).also { failure = it }
        } finally {
            cleanup(pending, failure)
        }
    }

    /** Tokens are opaque UUIDs, never paths. Null means missing, expired, invalid, or unreadable. */
    fun restore(token: String): PendingScreenshot? = try {
        cache.restore(token)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /**
     * The staged file behind [pending], for handing to another app.
     *
     * Sharing reads the staged copy in place rather than exporting it first: the receiving app
     * gets a read grant on that file, so copying it somewhere else only to grant access to the
     * copy would write the image twice for no benefit. The caller keeps ownership and must still
     * discard it once the share is over.
     */
    fun stagedFile(pending: PendingScreenshot): File? = try {
        cache.find(pending.token)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Also usable from cancelled lifecycle jobs, including the document picker's cancel path. */
    suspend fun discard(pending: PendingScreenshot) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                cache.discard(pending.token)
            } catch (cause: IOException) {
                throw error(R.string.screenshot_error_cleanup, cause)
            } catch (cause: SecurityException) {
                throw error(R.string.screenshot_error_cleanup, cause)
            }
        }
    }

    private suspend fun cleanup(pending: PendingScreenshot, failure: Throwable?) {
        try {
            discard(pending)
        } catch (cleanupFailure: ScreenshotException) {
            // Only ever a footnote. This runs in a finally, so throwing here after the file was
            // written would replace a completed save with an error message — telling the user
            // their screenshot failed while it sits correctly in the folder they chose. The stale
            // cache copy is collected by the ordinary prune instead.
            if (failure != null) {
                failure.addSuppressed(cleanupFailure)
            } else {
                Log.w(TAG, "Discarding the cached screenshot after a successful export failed", cleanupFailure)
            }
        }
    }

    private suspend fun copy(
        view: SurfaceView,
        bitmap: Bitmap,
        lifecycle: PixelCopyLifecycle,
    ): Int = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) {
            lifecycle.finishCopy()
            return@suspendCancellableCoroutine
        }
        try {
            PixelCopy.request(view, bitmap, { result ->
                try {
                    continuation.resume(result)
                } finally {
                    lifecycle.finishCopy()
                }
            }, callbackHandler)
        } catch (cause: IllegalArgumentException) {
            lifecycle.finishCopy()
            continuation.resumeWithException(error(R.string.screenshot_error_not_ready, cause))
        } catch (cause: IllegalStateException) {
            lifecycle.finishCopy()
            continuation.resumeWithException(error(R.string.screenshot_error_not_ready, cause))
        } catch (cause: OutOfMemoryError) {
            lifecycle.finishCopy()
            continuation.resumeWithException(error(R.string.screenshot_error_memory, cause))
        }
    }

    private fun error(resource: Int, cause: Throwable? = null) =
        ScreenshotException(context.getString(resource), cause)

    private companion object {
        const val COPY_TIMEOUT_MILLIS = 5_000L
        const val TAG = "ScreenshotExporter"
    }
}
