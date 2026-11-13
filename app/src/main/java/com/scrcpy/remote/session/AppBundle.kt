package com.scrcpy.remote.session

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * An app that comes as several APKs installed together, as read on this phone.
 *
 * Four kinds of file carry one: bundletool's APK sets (`.apks`), whose table of contents lists a
 * variant of the app for each range of Android versions; and XAPK, APKM or plain zip files, which
 * carry one set of splits from a store. Which of the files a device needs depends on the device,
 * so choosing them is left to [BundleSelection] once it has said what it is.
 */
data class AppBundle(
    val format: Format,
    val packageName: String,
    val variants: List<Variant>,
    /** Every APK file the bundle holds, each counted once. */
    val files: Int,
    /** The base APK whose name and icon are shown: the newest variant's. */
    val baseEntry: String,
    /** Why the bundle cannot be installed on any device, or null when it can be. */
    val problem: Problem? = null,
    /** For [Problem.UNMATCHABLE_PARTS]: what its splits are chosen by, such as `astc`. */
    val unmatchable: List<String> = emptyList(),
) {
    enum class Format { APK_SET, XAPK, APKM, ZIP }

    enum class Problem {
        /** Game data, as expansion (OBB) files or asset packs, which installing the APKs leaves out. */
        EXPANSION_FILES,

        /** Splits chosen by something this app cannot check on a device, such as texture formats. */
        UNMATCHABLE_PARTS,

        /** Not one complete app: damaged, protected, or without a single base APK. */
        UNREADABLE,
    }

    /** The files that make up the app on devices from [minSdk] up. */
    data class Variant(val minSdk: Int, val parts: List<Part>)

    data class Part(
        /** The file's path inside the bundle. */
        val entry: String,
        /** The split it is, as its manifest names it; null for a base. */
        val split: String?,
        val size: Long,
        /** A module installed only when the app asks for it, which an install leaves out. */
        val onDemand: Boolean = false,
    )

    companion object {
        private val EXTENSIONS = mapOf(".apks" to Format.APK_SET, ".xapk" to Format.XAPK, ".apkm" to Format.APKM)
        private val MIME_TYPES = setOf("application/vnd.apks", "application/vnd.apkm", "application/xapk-package-archive")
        private const val TABLE_OF_CONTENTS = "toc.pb"
        private const val MAX_TABLE_OF_CONTENTS_BYTES = 16 * 1024 * 1024
        /** More files than any app is made of: a zip of something else, not to read every file of. */
        private const val MAX_FILES = 1_000

        /** Whether a file's name or type says it is an app bundle. */
        fun looksLikeBundle(name: String?, mimeType: String? = null): Boolean {
            if (mimeType != null && mimeType.lowercase(Locale.ROOT) in MIME_TYPES) return true
            return formatNamed(name) != null
        }

        private fun formatNamed(name: String?): Format? {
            val lower = name?.trim()?.lowercase(Locale.ROOT) ?: return null
            return EXTENSIONS.entries.firstOrNull { (extension, _) -> lower.length > extension.length && lower.endsWith(extension) }?.value
        }

        /**
         * Reads [file], named [name], as an app bundle.
         *
         * Null when it is not one at all, such as a plain APK or any other zip. A file that says it
         * is a bundle but cannot be installed is read as one with its [problem].
         */
        fun read(file: File, name: String?): AppBundle? {
            val named = formatNamed(name)
            val zip = try {
                ZipFile(file)
            } catch (_: ZipException) {
                return named?.let { unreadable(it, 0) }
            } catch (_: IOException) {
                return named?.let { unreadable(it, 0) }
            }
            return zip.use {
                try {
                    readZip(zip, named)
                } catch (_: IOException) {
                    unreadable(named ?: Format.ZIP, 0)
                } catch (_: IllegalArgumentException) {
                    unreadable(named ?: Format.ZIP, 0)
                }
            }
        }

        private fun readZip(zip: ZipFile, named: Format?): AppBundle? {
            // An archive with a manifest of its own is one app, even one carrying others in its
            // assets: never a bundle of those.
            if (zip.getEntry("AndroidManifest.xml") != null) return null
            val entries = zip.entries().toList().filterNot { it.isDirectory || isMacMetadata(it.name) }
            val apks = entries.filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
            val toc = zip.getEntry(TABLE_OF_CONTENTS)
            if (toc == null && apks.isEmpty()) return named?.let { unreadable(it, 0) }
            val gameData = entries.any { entry ->
                val lower = entry.name.lowercase(Locale.ROOT)
                lower.startsWith("android/obb/") || lower.endsWith(".obb")
            }
            if (toc != null) return readApkSet(zip, toc.let { zip.getInputStream(it) }, apks.size, gameData)
            val format = when {
                zip.getEntry("manifest.json") != null -> Format.XAPK
                zip.getEntry("info.json") != null -> Format.APKM
                else -> named ?: Format.ZIP
            }
            if (apks.size > MAX_FILES) return unreadable(format, apks.size)
            val parts = mutableListOf<Part>()
            val packages = mutableSetOf<String>()
            val versions = mutableSetOf<Long>()
            for (entry in apks) {
                val manifest = zip.getInputStream(entry).use { ApkManifest.read(it) } ?: return unreadable(format, apks.size)
                if (entry.size < 0) return unreadable(format, apks.size)
                packages += manifest.packageName
                manifest.versionCode?.let(versions::add)
                parts += Part(entry.name, manifest.split, entry.size)
            }
            // One app, at one version, with one base: anything else is not an app the device can take.
            val base = parts.singleOrNull { it.split == null }
            if (packages.size != 1 || versions.size > 1 || base == null) return unreadable(format, apks.size)
            return complete(format, packages.single(), listOf(Variant(0, parts)), apks.size, base.entry, gameData)
        }

        private fun readApkSet(zip: ZipFile, source: InputStream, apkFiles: Int, gameData: Boolean): AppBundle {
            val toc = source.use { ApkSetTableOfContents.parse(readBounded(it, MAX_TABLE_OF_CONTENTS_BYTES)) }
            val variants = toc.variants.mapNotNull { variant ->
                // Instant, system-image and archived APKs are variants of their own, for other kinds
                // of install than this one.
                if (variant.entries.any { it.otherKind }) return@mapNotNull null
                val splits = variant.entries.filterNot { it.standalone }
                val entries = when {
                    splits.isNotEmpty() -> splits
                    // An APK set made with --mode=universal holds one whole app.
                    variant.entries.size == 1 -> variant.entries
                    // Standalone APKs chosen by processor and density are for Android 4, which no
                    // scrcpy server runs on.
                    else -> return@mapNotNull null
                }
                val parts = entries.map { described ->
                    val size = zip.getEntry(described.path)?.size?.takeIf { it >= 0 }
                        ?: return unreadable(Format.APK_SET, apkFiles)
                    Part(described.path, described.splitId.takeUnless { it.isEmpty() || described.standalone }, size,
                        onDemand = described.delivery == DELIVERY_ON_DEMAND || described.delivery == DELIVERY_FAST_FOLLOW)
                }
                if (parts.count { it.split == null } != 1) return unreadable(Format.APK_SET, apkFiles)
                Variant(variant.minSdk, parts)
            }.sortedBy { it.minSdk }
            if (variants.isEmpty()) return unreadable(Format.APK_SET, apkFiles)
            val base = variants.last().parts.single { it.split == null }.entry
            val packageName = toc.packageName?.takeIf { it.isNotBlank() }
                ?: zip.getInputStream(zip.getEntry(base)).use { ApkManifest.read(it) }?.packageName
                ?: return unreadable(Format.APK_SET, apkFiles)
            val files = variants.flatMap { variant -> variant.parts.map { it.entry } }.distinct().size
            return complete(Format.APK_SET, packageName, variants, files, base, gameData || toc.assetPacks)
        }

        internal fun complete(
            format: Format,
            packageName: String,
            variants: List<Variant>,
            files: Int,
            baseEntry: String,
            gameData: Boolean,
        ): AppBundle {
            // Only what an install would send: on-demand and fast-follow parts are left out.
            val unmatchable = variants.flatMap { it.parts }.filterNot { it.onDemand }
                .mapNotNull { (SplitNames.kind(it.split) as? SplitNames.Kind.Unknown)?.qualifier }.distinct()
            val problem = when {
                gameData -> Problem.EXPANSION_FILES
                unmatchable.isNotEmpty() -> Problem.UNMATCHABLE_PARTS
                else -> null
            }
            return AppBundle(format, packageName, variants, files, baseEntry, problem, unmatchable)
        }

        /**
         * Finder zips a file's extended attributes as AppleDouble files named like it, under
         * `__MACOSX/` or with `._` before the name; they are not part of the app.
         */
        private fun isMacMetadata(name: String): Boolean =
            name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith("._")

        private fun unreadable(format: Format, files: Int) =
            AppBundle(format, packageName = "", variants = emptyList(), files = files, baseEntry = "", problem = Problem.UNREADABLE)

        private fun readBounded(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return out.toByteArray()
                out.write(buffer, 0, read)
                if (out.size() > limit) throw IOException("The table of contents is too large")
            }
        }

        /**
         * Copies [input] to [output], counting the bytes actually read (a declared size can lie),
         * and throws [IOException] once more than [limit] have been. Returns the bytes copied.
         */
        @Throws(IOException::class)
        fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return total
                total += read
                if (total > limit) throw IOException("Entry is larger than $limit bytes")
                output.write(buffer, 0, read)
            }
        }

        /** True when the parts' sizes, summed without overflow, stay within [limit]. */
        fun fitsWithin(parts: List<Part>, limit: Long): Boolean {
            var total = 0L
            for (part in parts) {
                if (part.size < 0 || part.size > limit) return false
                total += part.size
                if (total > limit) return false
            }
            return true
        }

        // bundletool's DeliveryType: a module the app downloads later, not one installed with it.
        private const val DELIVERY_ON_DEMAND = 2
        private const val DELIVERY_FAST_FOLLOW = 3
    }
}

/**
 * The parts of an app bundle's split names that say which device needs a split.
 *
 * bundletool, Google Play and the stores that pass their files on all name a configuration split
 * `config.<value>` for the base and `<module>.config.<value>` for a feature, where the value is a
 * processor (`arm64_v8a`), a density bucket (`xxhdpi`) or a language (`fr`).
 */
internal object SplitNames {
    sealed interface Kind {
        /** A base or a feature itself. */
        data object Master : Kind
        data class Abi(val abi: String) : Kind
        data class Density(val dpi: Int) : Kind
        data class Language(val language: String) : Kind
        data class Unknown(val qualifier: String) : Kind
    }

    /** As Android names processors, which a split name cannot hold as they are. */
    private val ABIS = mapOf("armeabi" to "armeabi", "armeabi_v7a" to "armeabi-v7a", "arm64_v8a" to "arm64-v8a",
        "x86" to "x86", "x86_64" to "x86_64", "mips" to "mips", "mips64" to "mips64", "riscv64" to "riscv64")
    private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320,
        "xxhdpi" to 480, "xxxhdpi" to 640)
    private val LANGUAGE = Regex("[a-z]{2,3}")
    private const val CONFIG = "config."
    private const val FEATURE_CONFIG = ".config."

    /** The module a split belongs to: empty for the base and its configuration splits. */
    fun module(split: String?): String = when {
        split == null || split.startsWith(CONFIG) -> ""
        FEATURE_CONFIG in split -> split.substringBefore(FEATURE_CONFIG)
        else -> split
    }

    fun kind(split: String?): Kind {
        val qualifier = when {
            split == null -> return Kind.Master
            split.startsWith(CONFIG) -> split.removePrefix(CONFIG)
            FEATURE_CONFIG in split -> split.substringAfter(FEATURE_CONFIG)
            else -> return Kind.Master
        }
        ABIS[qualifier]?.let { return Kind.Abi(it) }
        DENSITIES[qualifier]?.let { return Kind.Density(it) }
        if (LANGUAGE.matches(qualifier)) return Kind.Language(language(qualifier))
        return Kind.Unknown(qualifier)
    }

    /** One code for each language that has two: Android's resources still use Java's old ones. */
    fun language(code: String): String = when (val lower = code.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> lower
    }
}

/**
 * What bundletool's table of contents (`toc.pb`, a `BuildApksResult`) says about each variant.
 *
 * Read with a small reader of the protocol buffer wire format rather than bundletool's generated
 * classes, for the few fields that matter: BuildApksResult.variant (1) and package_name (4), and
 * asset_slice_set (3) only for being there; Variant.targeting (1) > sdk_version_targeting (1) >
 * value (1) > min (1) > value (1), and Variant.apk_set (2); ApkSet.module_metadata (1) > name
 * (1) and delivery_type (6), and ApkSet.apk_description (2); ApkDescription.path (2),
 * split_apk_metadata (3) > split_id (1), standalone_apk_metadata (4), and the other kinds' metadata
 * (instant 5, system 6, asset slice 7, APEX 8, archived 9) only for being there.
 */
internal object ApkSetTableOfContents {
    /** [otherKind] marks an instant, system-image, asset-slice, APEX or archived APK (fields 5 to 9). */
    class Entry(
        val path: String,
        val splitId: String,
        val standalone: Boolean,
        val module: String,
        val delivery: Int,
        val otherKind: Boolean = false,
    )
    class Variant(val minSdk: Int, val entries: List<Entry>)
    class Contents(val packageName: String?, val variants: List<Variant>, val assetPacks: Boolean)

    fun parse(bytes: ByteArray): Contents {
        var packageName: String? = null
        val variants = mutableListOf<Variant>()
        var assetPacks = false
        for (field in Fields(bytes, 0, bytes.size)) {
            when (field.number) {
                1 -> variants += variant(bytes, field)
                3 -> assetPacks = true
                4 -> packageName = field.text(bytes)
            }
        }
        return Contents(packageName, variants, assetPacks)
    }

    private fun variant(bytes: ByteArray, variant: Field): Variant {
        var minSdk = 0
        val entries = mutableListOf<Entry>()
        for (field in variant.children(bytes)) {
            when (field.number) {
                1 -> field.child(bytes, 1)?.child(bytes, 1)?.child(bytes, 1)?.child(bytes, 1)
                    ?.let { minSdk = it.varint.toInt() }
                2 -> entries += apkSet(bytes, field)
            }
        }
        return Variant(minSdk, entries)
    }

    private fun apkSet(bytes: ByteArray, set: Field): List<Entry> {
        var module = ""
        var delivery = 0
        val descriptions = mutableListOf<Field>()
        for (field in set.children(bytes)) {
            when (field.number) {
                1 -> for (metadata in field.children(bytes)) {
                    when (metadata.number) {
                        1 -> module = metadata.text(bytes)
                        6 -> delivery = metadata.varint.toInt()
                    }
                }
                2 -> descriptions += field
            }
        }
        return descriptions.map { description ->
            var path = ""
            var splitId = ""
            var standalone = false
            var otherKind = false
            for (field in description.children(bytes)) {
                when (field.number) {
                    2 -> path = field.text(bytes)
                    3 -> field.child(bytes, 1)?.let { splitId = it.text(bytes) }
                    4 -> standalone = true
                    in 5..9 -> otherKind = true
                }
            }
            require(path.isNotEmpty()) { "A file without a path" }
            Entry(path, splitId, standalone, module, delivery, otherKind)
        }
    }

    /** One field: a number, and either a varint or the span of its bytes. */
    private class Field(val number: Int, val varint: Long, val start: Int, val end: Int) {
        fun children(bytes: ByteArray): Fields = Fields(bytes, start, end)
        fun child(bytes: ByteArray, number: Int): Field? = children(bytes).firstOrNull { it.number == number }
        fun text(bytes: ByteArray): String = String(bytes, start, end - start, Charsets.UTF_8)
    }

    private class Fields(private val bytes: ByteArray, private val from: Int, private val to: Int) : Iterable<Field> {
        override fun iterator(): Iterator<Field> = iterator {
            var position = from
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(position < to && shift < 64) { "A cut-off table of contents" }
                    val byte = bytes[position++].toInt()
                    result = result or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (position < to) {
                val key = varint()
                val number = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> yield(Field(number, varint(), position, position))
                    1 -> { position += 8; require(position <= to) { "A cut-off table of contents" } }
                    2 -> {
                        val length = varint()
                        require(length >= 0 && length <= to - position) { "A cut-off table of contents" }
                        val start = position
                        position += length.toInt()
                        yield(Field(number, 0, start, position))
                    }
                    5 -> { position += 4; require(position <= to) { "A cut-off table of contents" } }
                    else -> throw IllegalArgumentException("Not a table of contents")
                }
            }
        }
    }
}
