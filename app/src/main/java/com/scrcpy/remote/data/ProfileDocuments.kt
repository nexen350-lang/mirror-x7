package com.scrcpy.remote.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Storage Access Framework I/O only; selecting a file never applies its contents. */
class ProfileDocuments(context: Context) {
    private val context = context.applicationContext
    private val cache = ProfileTransferCache(context.cacheDir)

    suspend fun readDocument(uri: Uri): ConfigurationSnapshot = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected backup")
        val job = currentCoroutineContext()
        input.use { ProfileBackupCodec.decode(readProfileBytes(it) { job.ensureActive() }) }
    }

    fun cache(snapshot: ConfigurationSnapshot): String = cache.create(ProfileBackupCodec.encode(snapshot))
    fun restore(token: String): ConfigurationSnapshot = ProfileBackupCodec.decode(cache.read(token))
    fun discard(token: String) = cache.discard(token)

    /** Removes expired plaintext transfer files; safe to call from app start. */
    fun sweepExpired() = cache.sweep()

    // Closed by use {}; lint's Recycle check does not follow a closeable through use on a named value.
    @android.annotation.SuppressLint("Recycle")
    suspend fun writeDocument(token: String, uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = cache.read(token)
        currentCoroutineContext().ensureActive()
        // "wt" truncates the existing backup on open, so once started the write must run to the
        // end: cancelling between chunks would leave the old backup gone and the new one partial.
        withContext(NonCancellable) {
            try {
                writeOnce(uri, bytes)
            } catch (first: IOException) {
                try {
                    writeOnce(uri, bytes)
                } catch (second: IOException) {
                    second.addSuppressed(first)
                    throw second
                }
            }
        }
    }

    @android.annotation.SuppressLint("Recycle")
    private fun writeOnce(uri: Uri, bytes: ByteArray) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open the selected destination")
        output.use {
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(16 * 1024, bytes.size - offset)
                it.write(bytes, offset, count)
                offset += count
            }
            it.flush()
        }
    }

}

internal fun readProfileBytes(input: InputStream, checkCancellation: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        checkCancellation()
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (count == 0) throw IOException("The document provider stopped making read progress")
        if (output.size() > ProfileBackupCodec.MAX_BYTES - count) {
            throw IOException("Backup exceeds the 1 MiB size limit")
        }
        output.write(buffer, 0, count)
    }
}

/** Only opaque UUIDs are saved in instance state, never file paths or large backup payloads. */
internal class ProfileTransferCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun directory(): File {
        val folder = File(root.canonicalFile, "profile-transfers")
        if (Files.isSymbolicLink(folder.toPath()) || folder.canonicalFile != folder.absoluteFile) {
            throw IOException("Invalid transfer cache location")
        }
        return folder
    }

    fun create(bytes: ByteArray): String {
        require(bytes.size <= ProfileBackupCodec.MAX_BYTES)
        val folder = directory()
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Cannot create transfer cache")
        sweep()
        val token = UUID.randomUUID().toString()
        val file = File(folder, "$token.json")
        try {
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(bytes)
            }
        } catch (e: IOException) {
            try { Files.deleteIfExists(file.toPath()) } catch (cleanup: IOException) { e.addSuppressed(cleanup) }
            throw e
        }
        return token
    }

    /** Deletes expired transfer files; call at app start and before every create. */
    fun sweep() {
        val folder = directory()
        for (file in folder.listFiles() ?: return) {
            if (TOKEN.matches(file.nameWithoutExtension) && file.extension == "json" &&
                !Files.isSymbolicLink(file.toPath()) && expired(file)
            ) Files.deleteIfExists(file.toPath())
        }
    }

    fun read(token: String): ByteArray {
        val file = checkedFile(token)
        if (!file.isFile || expired(file) || file.length() !in 1..ProfileBackupCodec.MAX_BYTES.toLong()) {
            throw IOException("The pending backup is missing or expired. Select it again.")
        }
        return file.inputStream().use { readProfileBytes(it) }
    }

    fun discard(token: String) {
        Files.deleteIfExists(checkedFile(token).toPath())
    }

    private fun checkedFile(token: String): File {
        if (!TOKEN.matches(token)) throw IOException("Invalid transfer token")
        val file = File(directory(), "$token.json")
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile) {
            throw IOException("Invalid transfer cache path")
        }
        return file
    }

    private fun expired(file: File) = now() - file.lastModified() !in 0..86_400_000L
    private companion object {
        val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
