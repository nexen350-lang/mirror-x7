package com.scrcpy.remote.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.io.InputStream

/** Signals that the read deadline passed before the command finished. */
class AdbShellTimeoutException : SocketTimeoutException("The shell read deadline passed")

/**
 * Collects everything a shell stream produced, tolerating libadb's unusual end-of-stream signal.
 *
 * Extracted from the stream plumbing so the parts that are easy to get wrong — treating a thrown
 * end-of-stream as success, telling that apart from a real failure, honouring the size cap, and
 * reporting a deadline instead of silently returning a truncated answer — can be tested without a
 * device.
 *
 * [hasExpired] reports whether the read deadline has passed. It is consulted only when a read
 * fails, because closing the stream is how the deadline is enforced.
 */
@Throws(IOException::class)
internal fun readShellOutput(
    input: InputStream,
    hasExpired: () -> Boolean = { false },
    maxBytes: Int = MAX_SHELL_OUTPUT_BYTES,
    bufferBytes: Int = SHELL_BUFFER_BYTES,
): String {
    require(maxBytes > 0 && bufferBytes > 0) { "Sizes must be positive" }
    val collected = ByteArrayOutputStream()
    val chunk = ByteArray(bufferBytes)
    while (true) {
        val read = try {
            input.read(chunk)
        } catch (error: IOException) {
            // A deadline closes the stream, so the resulting failure is the deadline, not the
            // stream's own end: returning the partial output here would look like success.
            if (hasExpired()) throw AdbShellTimeoutException()
            if (isAdbStreamEof(error)) break else throw error
        }
        if (read < 0) break
        collected.write(chunk, 0, read)
        if (collected.size() > maxBytes) {
            throw IOException("Shell output exceeded $maxBytes bytes")
        }
    }
    return collected.toString(Charsets.UTF_8.name())
}

internal const val SHELL_BUFFER_BYTES = 8 * 1024

/** A shell command's output is held in memory, so it must not be able to grow without end. */
internal const val MAX_SHELL_OUTPUT_BYTES = 4 * 1024 * 1024
