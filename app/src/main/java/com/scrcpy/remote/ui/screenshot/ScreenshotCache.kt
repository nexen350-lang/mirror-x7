package com.scrcpy.remote.ui.screenshot

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

class PendingScreenshot internal constructor(
    val token: String,
    val suggestedName: String,
)

internal data class ScreenshotSize(val width: Int, val height: Int)

internal fun boundedScreenshotSize(width: Int, height: Int): ScreenshotSize {
    require(width > 0 && height > 0)
    val scale = min(
        1.0,
        min(
            ScreenshotCache.MAX_DIMENSION.toDouble() / maxOf(width, height),
            sqrt(ScreenshotCache.MAX_PIXELS.toDouble() / (width.toLong() * height)),
        ),
    )
    return ScreenshotSize(
        floor(width * scale).toInt().coerceAtLeast(1),
        floor(height * scale).toInt().coerceAtLeast(1),
    )
}

internal class ScreenshotCache(
    private val cacheRoot: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun create(encode: (OutputStream) -> Unit): PendingScreenshot {
        val directory = directory()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create screenshot cache")
        }
        removeExpired(directory)
        val token = UUID.randomUUID().toString()
        val staging = File(directory, "$token.part")
        val destination = File(directory, "$token.png")
        Closeable { Files.deleteIfExists(staging.toPath()) }.use {
            Files.newOutputStream(
                staging.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use(encode)
            if (!isBoundedPng(staging)) throw IOException("Invalid encoded screenshot")
            if (!staging.renameTo(destination)) throw IOException("Cannot finalize screenshot")
        }
        return pending(token, destination.lastModified())
    }

    fun restore(token: String): PendingScreenshot? {
        val file = find(token) ?: run {
            discard(token)
            return null
        }
        return pending(token, file.lastModified())
    }

    fun find(token: String): File? {
        if (!TOKEN.matches(token)) return null
        val file = File(directory(), "$token.png")
        if (!isDirectPath(file) || !file.isFile || expired(file)) return null
        return file.takeIf(::isBoundedPng)
    }

    fun discard(token: String) {
        if (!TOKEN.matches(token)) return
        val file = File(directory(), "$token.png")
        if (!isDirectPath(file)) throw IOException("Invalid cache location")
        Files.deleteIfExists(file.toPath())
    }

    private fun directory(): File {
        val root = cacheRoot.canonicalFile
        val directory = File(root, DIRECTORY)
        if (!isDirectPath(directory)) {
            throw IOException("Invalid screenshot cache directory")
        }
        return directory
    }

    private fun removeExpired(directory: File) {
        val files = directory.listFiles() ?: throw IOException("Cannot read screenshot cache")
        for (file in files) {
            if (file.extension !in setOf("png", "part") || !TOKEN.matches(file.nameWithoutExtension)) continue
            if (isDirectPath(file) && expired(file)) Files.deleteIfExists(file.toPath())
        }
    }

    private fun isDirectPath(file: File): Boolean =
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile

    private fun expired(file: File): Boolean = now() - file.lastModified() !in 0..MAX_AGE_MILLIS

    private fun pending(token: String, timestamp: Long) = PendingScreenshot(
        token,
        "scrcpy-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))}.png",
    )

    private fun isBoundedPng(file: File): Boolean {
        if (file.length() !in 57..MAX_PNG_BYTES) return false
        return RandomAccessFile(file, "r").use { input ->
            if (input.readLong() != PNG_SIGNATURE || input.readInt() != 13 || input.readInt() != IHDR) {
                return@use false
            }
            val width = input.readInt()
            val height = input.readInt()
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION ||
                width.toLong() * height > MAX_PIXELS
            ) {
                return@use false
            }
            input.seek(input.length() - 12)
            input.readInt() == 0 && input.readInt() == IEND && input.readInt() == IEND_CRC
        }
    }

    companion object {
        const val DIRECTORY = "screenshots"
        const val MAX_DIMENSION = 4096
        const val MAX_PIXELS = 8_388_608L
        const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
        private const val MAX_PNG_BYTES = MAX_PIXELS * 4 + 1024 * 1024
        private const val PNG_SIGNATURE = -8552249625308161526L
        private const val IHDR = 0x49484452
        private const val IEND = 0x49454e44
        private const val IEND_CRC = -1371381630
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
