package com.scrcpy.remote.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Minimal implementation of the ADB `sync:` service, used to push the scrcpy server jar to
 * `/data/local/tmp`. libadb-android exposes the raw stream but does not implement the protocol.
 *
 * See platform/system/core/adb/SYNC.TXT.
 */
object AdbSync {

    /**
     * Whether a file already exists at [remotePath].
     *
     * Asked before a transfer so one can never land on top of another. adbd empties the
     * destination as soon as a `SEND` begins and deletes it again if that transfer fails, so
     * choosing the name is the only point at which a file already there can still be protected.
     *
     * A device that will not answer is reported as unknown rather than as empty: of the two
     * guesses, "nothing there" is the one that loses a file.
     * Cancellation and interruption still abort the transfer.
     */
    fun exists(openChannel: () -> SyncChannel, remotePath: String): Boolean? =
        runCatching { stat(openChannel, remotePath).mode != 0 }.onFailure { error ->
            if (error is InterruptedException || error is CancellationException ||
                (error is InterruptedIOException && error !is SocketTimeoutException)) throw error
        }.getOrNull()

    /** RECV works on old adbd versions too, unlike assuming a sha256sum binary is installed. */
    fun sha256(openChannel: () -> SyncChannel, remotePath: String, expectedSize: Int): String =
        openChannel().use { channel ->
            val path = remotePath.toByteArray(Charsets.UTF_8)
            channel.output.write(header("RECV", path.size))
            channel.output.write(path)
            channel.output.flush()
            val digest = MessageDigest.getInstance("SHA-256")
            val frame = ByteArray(8)
            val buffer = ByteArray(MAX_CHUNK)
            var total = 0L
            while (true) {
                readFully(channel.input, frame)
                val id = String(frame, 0, 4, Charsets.US_ASCII)
                val length = readLeInt(frame, 4)
                when (id) {
                    "DONE" -> break
                    "FAIL" -> throw IOException("adbd could not read the deployed server")
                    "DATA" -> {
                        if (length !in 1..MAX_CHUNK || total + length > expectedSize) {
                            throw IOException("Invalid server transfer length")
                        }
                        DataInputStream(channel.input).readFully(buffer, 0, length)
                        digest.update(buffer, 0, length)
                        total += length
                    }
                    else -> throw IOException("Unexpected sync receive response $id")
                }
            }
            if (total != expectedSize.toLong()) throw IOException("Truncated deployed server: $total bytes")
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

    private const val ID_SEND = "SEND"
    private const val ID_DATA = "DATA"
    private const val ID_DONE = "DONE"
    private const val ID_OKAY = "OKAY"
    private const val ID_FAIL = "FAIL"
    private const val ID_STAT = "STAT"

    /** adbd refuses DATA chunks larger than this. */
    private const val MAX_CHUNK = 64 * 1024

    /** Result of a sync `STAT`; [size] is zero when the path does not exist. */
    data class RemoteStat(val mode: Int, val size: Int, val modifiedAt: Int) {
        val exists: Boolean get() = mode != 0
    }

    /**
     * The device received the whole file and the request to keep it, but the connection ended
     * before it confirmed. The file may well be there; saying it is not would be a guess.
     */
    class SyncCommitUnconfirmedException(cause: Throwable) :
        IOException("The device did not confirm the transfer: ${cause.message}", cause)

    /** adbd answered FAIL: a definite refusal, unlike a missing answer. */
    private class SyncRefusedException(message: String) : IOException(message)

    /**
     * Reads the mode, size and mtime of [remotePath], or a zeroed result when it is absent.
     *
     * Used to skip re-pushing an identical file, which otherwise costs a megabyte of transfer on
     * every single connection.
     */
    fun stat(openChannel: () -> SyncChannel, remotePath: String): RemoteStat =
        openChannel().use { stream ->
            val out = stream.output
            val input = stream.input

            val path = remotePath.toByteArray(Charsets.UTF_8)
            out.write(header(ID_STAT, path.size))
            out.write(path)
            out.flush()

            val response = ByteArray(16)
            readFully(input, response)
            val id = String(response, 0, 4, Charsets.US_ASCII)
            if (id != ID_STAT) throw IOException("Unexpected stat response: $id")
            RemoteStat(
                mode = readLeInt(response, 4),
                size = readLeInt(response, 8),
                modifiedAt = readLeInt(response, 12),
            )
        }

    private fun readLeInt(buffer: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    /**
     * Pushes [content] to [remotePath] on the device with the given unix [mode].
     *
     * @param openChannel supplies a fresh channel connected to the `sync:` service.
     */
    fun push(openChannel: () -> SyncChannel, content: ByteArray, remotePath: String, mode: Int = 0b110_100_100) {
        push(openChannel, content.inputStream(), content.size.toLong(), remotePath, mode)
    }

    /**
     * Streams [size] bytes from [source] to [remotePath].
     *
     * Streaming rather than taking a `ByteArray` so a user-chosen file of any size can be sent
     * without first holding all of it in memory.
     *
     * [onProgress] receives the running byte total. [isCancelled] is polled between chunks; a
     * cancelled transfer abandons the channel without sending DONE, so adbd discards the partial
     * file instead of leaving a truncated one behind. [onCommit] runs once, just before DONE, the
     * point after which an interrupted transfer may still have saved the file.
     */
    fun push(
        openChannel: () -> SyncChannel,
        source: InputStream,
        size: Long,
        remotePath: String,
        mode: Int = 0b110_100_100,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onCommit: () -> Unit = {},
    ) {
        require(size >= 0) { "Size must not be negative" }
        fun checkCancelled() {
            if (isCancelled()) throw InterruptedIOException("Transfer cancelled")
        }
        checkCancelled()
        openChannel().use { stream ->
            checkCancelled()
            val out = stream.output
            val input = DataInputStream(stream.input)

            val pathAndMode = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            out.write(header(ID_SEND, pathAndMode.size))
            out.write(pathAndMode)

            val buffer = ByteArray(MAX_CHUNK)
            var sent = 0L
            while (sent < size) {
                checkCancelled()
                val wanted = minOf(MAX_CHUNK.toLong(), size - sent).toInt()
                val read = source.read(buffer, 0, wanted)
                if (read < 0) throw IOException("File ended after $sent of $size bytes")
                if (read == 0) continue
                out.write(header(ID_DATA, read))
                out.write(buffer, 0, read)
                sent += read
                onProgress(sent)
            }

            checkCancelled()
            // A file shorter than promised is caught above. The opposite has to be caught here:
            // a provider that under-reports its size (some answer zero for "unknown") would
            // otherwise have the rest of the file dropped and the transfer still called a
            // success, leaving a truncated copy on the device and no sign anything went wrong.
            if (source.read(buffer, 0, 1) >= 0) {
                throw IOException("File is larger than the $size bytes it reported")
            }

            // Announced before the last cancellation check, so whoever cancels either sees the
            // announcement or stops this transfer before DONE: never DONE without one.
            onCommit()
            checkCancelled()
            // From DONE on, adbd may already have the complete file. Losing the connection now
            // leaves the outcome unknown rather than failed; only an explicit FAIL is a refusal.
            try {
                out.write(header(ID_DONE, (System.currentTimeMillis() / 1000L).toInt()))
                out.flush()
                readResult(input)
            } catch (refused: SyncRefusedException) {
                throw refused
            } catch (lost: IOException) {
                if (lost is InterruptedIOException && lost !is SocketTimeoutException) throw lost
                throw SyncCommitUnconfirmedException(lost)
            }
        }
    }

    private fun header(id: String, value: Int): ByteArray =
        ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(value)
            .array()

    private fun readResult(input: InputStream) {
        val response = ByteArray(8)
        readFully(input, response)
        val id = String(response, 0, 4, Charsets.US_ASCII)
        val value = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        when (id) {
            ID_OKAY -> return
            ID_FAIL -> {
                if (value !in 0..4096) throw IOException("Invalid sync failure length $value")
                val message = ByteArray(value)
                readFully(input, message)
                throw SyncRefusedException("adb push failed: ${String(message, Charsets.UTF_8)}")
            }
            else -> throw IOException("Unexpected sync response: $id")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) throw IOException("Unexpected end of sync stream")
            read += count
        }
    }
}
