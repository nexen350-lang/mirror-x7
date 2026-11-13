package com.scrcpy.remote.session

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * What an APK's compiled manifest says about how it installs: the app it belongs to, and which
 * part of that app it is.
 *
 * An app bundle's files are told apart by their manifests rather than their names: a split names
 * itself (`split="config.x86_64"`), and a base names no split at all.
 */
data class ApkManifest(
    val packageName: String,
    /** Null for a base APK; `config.x86_64`, `feature` or `feature.config.xxhdpi` for a split. */
    val split: String?,
    val versionCode: Long?,
    val isFeatureSplit: Boolean,
) {
    companion object {
        /** A manifest is a few kilobytes; one this large is not something to read into memory. */
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        // Chunk types of Android's binary XML (frameworks/base/libs/androidfw/ResourceTypes.h).
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val UTF8_FLAG = 1 shl 8
        private const val NO_ENTRY = -1
        private const val TYPE_STRING = 0x03
        private const val TYPE_FIRST_INT = 0x10
        private const val TYPE_LAST_INT = 0x1f

        // android.R.attr, for manifests whose attribute names were stripped.
        private const val ATTR_VERSION_CODE = 0x0101021b
        private const val ATTR_IS_FEATURE_SPLIT = 0x0101055b

        /** Reads a compiled (binary XML) `AndroidManifest.xml`. */
        fun parse(bytes: ByteArray): ApkManifest {
            try {
                return Reader(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)).manifest()
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalArgumentException("Not a whole compiled manifest", e)
            } catch (e: java.nio.BufferUnderflowException) {
                throw IllegalArgumentException("Not a whole compiled manifest", e)
            }
        }

        /** The manifest of the APK whose bytes [apk] streams, or null when it has none. */
        fun read(apk: InputStream): ApkManifest? {
            try {
                val zip = ZipInputStream(apk)
                while (true) {
                    val entry = zip.nextEntry ?: return null
                    if (entry.name != "AndroidManifest.xml") continue
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MAX_MANIFEST_BYTES) return null
                    }
                    return parse(out.toByteArray())
                }
            } catch (_: ZipException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            } catch (_: IOException) {
                return null
            }
        }
    }

    private class Reader(private val buffer: ByteBuffer) {
        private var pool: StringPool? = null
        private var resourceIds = IntArray(0)

        fun manifest(): ApkManifest {
            require(buffer.limit() >= 8 && u16(0) == RES_XML_TYPE) { "Not a compiled manifest" }
            val headerSize = u16(2)
            // A file shorter than it says it is was cut off, even if what is left looks whole.
            val declared = buffer.getInt(4).toLong() and 0xFFFFFFFFL
            require(declared in 8..buffer.limit().toLong()) { "A cut-off compiled manifest" }
            val size = declared.toInt()
            var offset = headerSize
            while (offset + 8 <= size) {
                val type = u16(offset)
                val chunkHeader = u16(offset + 2)
                val chunkSize = buffer.getInt(offset + 4)
                require(chunkHeader >= 8 && chunkSize >= chunkHeader && offset + chunkSize <= size) { "Damaged chunk" }
                when (type) {
                    RES_STRING_POOL_TYPE -> pool = stringPool(offset, chunkHeader, chunkSize)
                    RES_XML_RESOURCE_MAP_TYPE -> resourceIds = IntArray((chunkSize - chunkHeader) / 4) {
                        buffer.getInt(offset + chunkHeader + it * 4)
                    }
                    RES_XML_START_ELEMENT_TYPE -> return root(offset, chunkHeader)
                }
                offset += chunkSize
            }
            throw IllegalArgumentException("No manifest element")
        }

        /** The first element, which in a manifest is `<manifest>`, and the attributes that matter. */
        private fun root(offset: Int, headerSize: Int): ApkManifest {
            val extension = offset + headerSize
            require(string(buffer.getInt(extension + 4)) == "manifest") { "The first element is not <manifest>" }
            val attributeStart = u16(extension + 8)
            val attributeSize = u16(extension + 10)
            val attributeCount = u16(extension + 12)
            require(attributeSize >= 20) { "Damaged attributes" }
            var packageName: String? = null
            var split: String? = null
            var versionCode: Long? = null
            var featureSplit = false
            for (index in 0 until attributeCount) {
                val at = extension + attributeStart + index * attributeSize
                val namespace = buffer.getInt(at)
                val nameIndex = buffer.getInt(at + 4)
                val raw = buffer.getInt(at + 8)
                val dataType = buffer.get(at + 15).toInt() and 0xFF
                val data = buffer.getInt(at + 16)
                val name = string(nameIndex)
                val resourceId = if (nameIndex in resourceIds.indices) resourceIds[nameIndex] else 0
                val text = if (dataType == TYPE_STRING) string(data) else if (raw != NO_ENTRY) string(raw) else null
                when {
                    namespace == NO_ENTRY && name == "package" -> packageName = text
                    namespace == NO_ENTRY && name == "split" -> split = text
                    resourceId == ATTR_VERSION_CODE || (namespace != NO_ENTRY && name == "versionCode") ->
                        versionCode = if (dataType in TYPE_FIRST_INT..TYPE_LAST_INT) data.toLong() and 0xFFFFFFFFL
                        else text?.trim()?.toLongOrNull()
                    resourceId == ATTR_IS_FEATURE_SPLIT || (namespace != NO_ENTRY && name == "isFeatureSplit") ->
                        featureSplit = if (dataType in TYPE_FIRST_INT..TYPE_LAST_INT) data != 0 else text == "true"
                }
            }
            val app = packageName?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("The manifest names no package")
            return ApkManifest(app, split?.trim()?.takeIf { it.isNotEmpty() }, versionCode, featureSplit)
        }

        private fun stringPool(offset: Int, headerSize: Int, chunkSize: Int): StringPool {
            val count = buffer.getInt(offset + 8)
            val flags = buffer.getInt(offset + 16)
            val stringsStart = buffer.getInt(offset + 20)
            require(count in 0..((chunkSize - headerSize) / 4)) { "Damaged string pool" }
            return StringPool(offset, headerSize, offset + chunkSize, count, stringsStart, flags and UTF8_FLAG != 0)
        }

        /**
         * Where a manifest's strings are, read only when one is asked for: a manifest has many,
         * and a length taken on trust could otherwise ask for more memory than the app has.
         */
        private class StringPool(val start: Int, val headerSize: Int, val end: Int, val count: Int, val stringsStart: Int, val utf8: Boolean)

        private fun utf8String(pool: StringPool, at: Int): String {
            var position = at
            // The length in UTF-16 units, then in bytes; each one or two bytes long.
            position += if (buffer.get(position).toInt() and 0x80 != 0) 2 else 1
            var length = buffer.get(position).toInt() and 0xFF
            if (length and 0x80 != 0) {
                length = ((length and 0x7F) shl 8) or (buffer.get(position + 1).toInt() and 0xFF)
                position += 2
            } else {
                position += 1
            }
            require(position.toLong() + length <= pool.end) { "A string longer than its pool" }
            val bytes = ByteArray(length) { buffer.get(position + it) }
            return String(bytes, Charsets.UTF_8)
        }

        private fun utf16String(pool: StringPool, at: Int): String {
            var position = at
            var length = u16(position)
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or u16(position + 2)
                position += 4
            } else {
                position += 2
            }
            require(position.toLong() + 2L * length <= pool.end) { "A string longer than its pool" }
            val chars = CharArray(length) { buffer.getChar(position + it * 2) }
            return String(chars)
        }

        private fun string(index: Int): String? {
            val strings = pool ?: return null
            if (index !in 0 until strings.count) return null
            val at = strings.start + strings.stringsStart + buffer.getInt(strings.start + strings.headerSize + index * 4)
            require(at in strings.start until strings.end) { "Damaged string" }
            return if (strings.utf8) utf8String(strings, at) else utf16String(strings, at)
        }

        private fun u16(at: Int): Int = buffer.getShort(at).toInt() and 0xFFFF
    }
}
