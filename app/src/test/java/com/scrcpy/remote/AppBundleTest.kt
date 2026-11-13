package com.scrcpy.remote

import com.scrcpy.remote.session.ApkManifest
import com.scrcpy.remote.session.AppBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * App bundles as real tools make them: bundletool's own APK set with its three variants, and the
 * same splits as an XAPK and an APKM, all built by tools/make-bundle-fixtures.ps1.
 */
class AppBundleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aSplitsManifestNamesItsAppAndWhichPartItIs() {
        val base = manifestIn("bundle-fixture.apks.bin", "splits/base-master_3.apk")
        assertEquals(PACKAGE, base.packageName)
        assertNull("A base names no split", base.split)
        assertEquals(1L, base.versionCode)
        assertFalse(base.isFeatureSplit)
        assertEquals("config.x86_64", manifestIn("bundle-fixture.apks.bin", "splits/base-x86_64_2.apk").split)
        assertEquals("config.xxhdpi", manifestIn("bundle-fixture.apks.bin", "splits/base-xxhdpi.apk").split)
        assertEquals("config.fr", manifestIn("bundle-fixture.apks.bin", "splits/base-fr.apk").split)
        // aapt2 writes a single APK's manifest too, and with no split.
        val single = requireNotNull(ApkManifest.read(fixture("install-fixture-v2.bin").inputStream()))
        assertEquals("com.scrcpy.remote.installfixture", single.packageName)
        assertNull(single.split)
        assertEquals(2L, single.versionCode)
    }

    @Test fun onlyACompiledManifestIsRead() {
        assertThrows(IllegalArgumentException::class.java) { ApkManifest.parse("<manifest package=\"a.b\"/>".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ApkManifest.parse(ByteArray(0)) }
        val real = rawManifest(entryBytes("bundle-fixture.apks.bin", "splits/base-x86_64_2.apk"))
        assertEquals("config.x86_64", ApkManifest.parse(real).split)
        for (length in listOf(8, real.size / 3, real.size / 2, real.size - 1)) {
            assertThrows("Cut to $length bytes", IllegalArgumentException::class.java) { ApkManifest.parse(real.copyOf(length)) }
        }
        assertNull("Not an APK", ApkManifest.read("not a zip".byteInputStream()))
        assertNull("An APK without a manifest", ApkManifest.read(zipBytes("classes.dex" to byteArrayOf(1, 2, 3)).inputStream()))
    }

    @Test fun anApkSetIsReadAsItsVariantsAndTheFilesEachOneNeeds() {
        val bundle = requireNotNull(read("bundle-fixture.apks.bin", "Bundle fixture.apks"))
        assertEquals(AppBundle.Format.APK_SET, bundle.format)
        assertEquals(PACKAGE, bundle.packageName)
        assertNull(bundle.problem)
        assertEquals("One variant for Android 5, one for 6 and one for 10", listOf(21, 23, 29), bundle.variants.map { it.minSdk })
        assertEquals("Shared files are counted once", 17, bundle.files)
        assertEquals("The newest variant's base is the one shown", "splits/base-master_3.apk", bundle.baseEntry)
        val newest = bundle.variants.last()
        assertEquals(SPLITS, newest.parts.map { it.split }.toSet())
        assertEquals("splits/base-x86_64_2.apk", newest.parts.single { it.split == "config.x86_64" }.entry)
        assertEquals("splits/base-xxhdpi.apk", newest.parts.single { it.split == "config.xxhdpi" }.entry)
        assertEquals("splits/base-x86_64.apk", bundle.variants.first().parts.single { it.split == "config.x86_64" }.entry)
        ZipFile(fixtureFile("bundle-fixture.apks.bin")).use { zip ->
            for (part in bundle.variants.flatMap { it.parts }) {
                assertEquals(part.entry, zip.getEntry(part.entry).size, part.size)
                assertFalse(part.onDemand)
            }
        }
    }

    @Test fun xapkAndApkmFilesAreReadFromTheirSplitsOwnManifests() {
        for ((name, format, base) in listOf(
            Triple("bundle-fixture.xapk.bin", AppBundle.Format.XAPK, "com.scrcpy.remote.bundlefixture.apk"),
            Triple("bundle-fixture.apkm.bin", AppBundle.Format.APKM, "base.apk"),
        )) {
            val bundle = requireNotNull(read(name, "download.${format.name.lowercase()}"), { name })
            assertEquals(name, format, bundle.format)
            assertEquals(name, PACKAGE, bundle.packageName)
            assertNull(name, bundle.problem)
            assertEquals(name, 12, bundle.files)
            assertEquals(name, base, bundle.baseEntry)
            val variant = bundle.variants.single()
            assertEquals(name, 0, variant.minSdk)
            assertEquals(name, SPLITS, variant.parts.map { it.split }.toSet())
        }
        // Named however the store named it, it is the manifest that counts.
        val renamed = requireNotNull(read("bundle-fixture.apkm.bin", "app"))
        assertEquals(AppBundle.Format.APKM, renamed.format)
    }

    @Test fun aSingleApkOrAnyOtherFileIsNotABundle() {
        assertNull(read("install-fixture-v2.bin", "tool.apk"))
        assertNull(AppBundle.read(zipFile("notes.txt" to "Not an app".toByteArray()), "notes.zip"))
        assertNull(AppBundle.read(file("plain text".toByteArray()), "notes.txt"))
    }

    @Test fun aFileNamedAsABundleThatIsNotOneCompleteAppCannotBeInstalled() {
        val split = entryBytes("bundle-fixture.apks.bin", "splits/base-x86_64_2.apk")
        for ((label, bundle) in listOf(
            "damaged" to AppBundle.read(file(ByteArray(64) { it.toByte() }), "game.apkm"),
            "two bases" to AppBundle.read(zipFile("a.apk" to fixture("install-fixture-v1.bin"),
                "b.apk" to fixture("install-fixture-v2.bin")), "two.xapk"),
            "two apps" to AppBundle.read(zipFile("base.apk" to fixture("install-fixture-v2.bin"),
                "split_config.x86_64.apk" to split), "mixed.apkm"),
            "no base" to AppBundle.read(zipFile("split_config.x86_64.apk" to split), "parts.apks"),
        )) {
            assertNotNull(label, bundle)
            assertEquals(label, AppBundle.Problem.UNREADABLE, bundle!!.problem)
        }
    }

    @Test fun expansionFilesMakeABundleThatCannotBeInstalledFromHere() {
        val entries = entriesOf("bundle-fixture.xapk.bin") +
            ("Android/obb/$PACKAGE/main.1.$PACKAGE.obb" to ByteArray(32))
        val bundle = requireNotNull(AppBundle.read(zipFile(*entries.toTypedArray()), "game.xapk"))
        assertEquals(AppBundle.Problem.EXPANSION_FILES, bundle.problem)
        assertEquals(PACKAGE, bundle.packageName)
    }

    @Test fun splitsChosenBySomethingADeviceCannotBeAskedMakeABundleThatCannotBeInstalled() {
        val master = entryBytes("bundle-fixture.apks.bin", "splits/base-master_3.apk")
        val toc = toc(variant(21, apkSet("base", 1,
            description("splits/base-master.apk", master = true),
            description("splits/base-astc.apk", split = "config.astc"))))
        val bundle = requireNotNull(AppBundle.read(zipFile("toc.pb" to toc, "splits/base-master.apk" to master,
            "splits/base-astc.apk" to master), "game.apks"))
        assertEquals(AppBundle.Problem.UNMATCHABLE_PARTS, bundle.problem)
        assertEquals(listOf("astc"), bundle.unmatchable)
    }

    @Test fun anApkSetsModulesForLaterAreMarkedAsSuch() {
        val master = entryBytes("bundle-fixture.apks.bin", "splits/base-master_3.apk")
        val toc = toc(variant(21,
            apkSet("base", 1, description("splits/base-master.apk", master = true)),
            apkSet("camera", 2, description("splits/camera-master.apk", split = "camera", master = true)),
            apkSet("levels", 3, description("splits/levels-master.apk", split = "levels", master = true)),
            apkSet("maps", 1, description("splits/maps-master.apk", split = "maps", master = true))))
        val bundle = requireNotNull(AppBundle.read(zipFile("toc.pb" to toc, "splits/base-master.apk" to master,
            "splits/camera-master.apk" to master, "splits/levels-master.apk" to master,
            "splits/maps-master.apk" to master), "app.apks"))
        assertNull(bundle.problem)
        val onDemand = bundle.variants.single().parts.associate { it.split to it.onDemand }
        assertEquals(mapOf(null to false, "camera" to true, "levels" to true, "maps" to false), onDemand)
    }

    @Test fun anApkSetThatListsAFileItDoesNotHoldCannotBeInstalled() {
        val master = entryBytes("bundle-fixture.apks.bin", "splits/base-master_3.apk")
        val toc = toc(variant(21, apkSet("base", 1, description("splits/base-master.apk", master = true),
            description("splits/base-x86_64.apk", split = "config.x86_64"))))
        val bundle = requireNotNull(AppBundle.read(zipFile("toc.pb" to toc, "splits/base-master.apk" to master), "app.apks"))
        assertEquals(AppBundle.Problem.UNREADABLE, bundle.problem)
    }

    @Test fun anApkCarryingAnotherApkIsStillOneApp() {
        // An app this phone cannot read, made for a newer Android, that carries a helper app in
        // its assets: it is the app the user picked, not a bundle of the helper.
        val manifest = rawManifest(fixture("install-fixture-v2.bin"))
        val outer = zipFile("AndroidManifest.xml" to manifest, "classes.dex" to byteArrayOf(1, 2, 3),
            "assets/helper.apk" to fixture("install-fixture-v1.bin"))
        assertNull(AppBundle.read(outer, "tool.apk"))
        assertNull("Named as a bundle, it is still one app", AppBundle.read(outer, "tool.xapk"))
    }

    @Test fun anApkSetsInstantAppIsLeftOutRatherThanTakenForDamage() {
        // bundletool adds a variant of instant APKs for a module that allows instant use; an
        // install from adb is never an instant one.
        val master = entryBytes("bundle-fixture.apks.bin", "splits/base-master_3.apk")
        val toc = toc(
            variant(21, apkSet("base", 1, description("instant/instant-base-master.apk", master = true, kind = 5),
                description("instant/instant-base-en.apk", split = "config.en", kind = 5))),
            variant(21, apkSet("base", 1, description("splits/base-master.apk", master = true),
                description("splits/base-en.apk", split = "config.en"))))
        val bundle = requireNotNull(AppBundle.read(zipFile("toc.pb" to toc, "instant/instant-base-master.apk" to master,
            "instant/instant-base-en.apk" to master, "splits/base-master.apk" to master, "splits/base-en.apk" to master), "app.apks"))
        assertNull(bundle.problem)
        assertEquals(listOf(listOf("splits/base-master.apk", "splits/base-en.apk")), bundle.variants.map { variant -> variant.parts.map { it.entry } })
    }

    @Test fun filesAMacAddsToAZipAreIgnored() {
        // Finder zips a file's extended attributes, such as a download's quarantine flag, as
        // AppleDouble files named like the files themselves.
        val entries = entriesOf("bundle-fixture.xapk.bin") + ("__MACOSX/._config.x86_64.apk" to ByteArray(82) { 7 }) +
            ("._com.scrcpy.remote.bundlefixture.apk" to ByteArray(82) { 7 })
        val bundle = requireNotNull(AppBundle.read(zipFile(*entries.toTypedArray()), "app.xapk"))
        assertNull(bundle.problem)
        assertEquals(12, bundle.files)
        assertEquals(SPLITS, bundle.variants.single().parts.map { it.split }.toSet())
    }

    @Test fun aManifestWithUtf16TextIsRead() {
        val manifest = ApkManifest.parse(utf16Manifest("com.example.app", "config.x86_64"))
        assertEquals("com.example.app", manifest.packageName)
        assertEquals("config.x86_64", manifest.split)
    }

    @Test fun aStringLongerThanItsManifestIsRefusedRatherThanMade() {
        // A length no honest manifest has: two thousand million characters, in a file of a few
        // hundred bytes. Making room for it would end the app.
        val crafted = utf16Manifest("com.example.app", "config.x86_64", splitLength = 0x7FFFFFFF)
        assertThrows(IllegalArgumentException::class.java) { ApkManifest.parse(crafted) }
        assertNull(ApkManifest.read(zipBytes("AndroidManifest.xml" to crafted).inputStream()))
        val long = utf16Manifest("com.example.app", "config.x86_64", splitLength = 300)
        assertThrows(IllegalArgumentException::class.java) { ApkManifest.parse(long) }
    }
    @Test fun bundlesAreRecognisedByTheirNames() {
        for (name in listOf("game.apks", "Game.APKS", "app.xapk", "app.apkm", " app.xapk ")) {
            assertTrue(name, AppBundle.looksLikeBundle(name))
        }
        for (name in listOf("app.apk", "notes.zip", ".apks", "apks", "", null)) {
            assertFalse("$name", AppBundle.looksLikeBundle(name))
        }
        assertTrue(AppBundle.looksLikeBundle("download", "application/vnd.apkm"))
        assertTrue(AppBundle.looksLikeBundle(null, "application/xapk-package-archive"))
        assertFalse(AppBundle.looksLikeBundle("download", "application/zip"))
    }

    private fun read(name: String, as_: String): AppBundle? = AppBundle.read(fixtureFile(name), as_)

    private fun manifestIn(bundle: String, entry: String): ApkManifest =
        requireNotNull(ApkManifest.read(entryBytes(bundle, entry).inputStream())) { "$bundle:$entry" }

    private fun entryBytes(bundle: String, entry: String): ByteArray =
        ZipFile(fixtureFile(bundle)).use { zip -> zip.getInputStream(requireNotNull(zip.getEntry(entry))).readBytes() }

    private fun entriesOf(bundle: String): List<Pair<String, ByteArray>> =
        ZipFile(fixtureFile(bundle)).use { zip ->
            zip.entries().toList().map { it.name to zip.getInputStream(it).readBytes() }
        }

    private fun rawManifest(apk: ByteArray): ByteArray =
        ZipInputStream(apk.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "AndroidManifest.xml" }
            zip.readBytes()
        }

    private fun file(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    private fun zipFile(vararg entries: Pair<String, ByteArray>): File = file(zipBytes(*entries))

    // A table of contents in bundletool's wire format: BuildApksResult.variant (1) > Variant
    // targeting (1) > sdk (1) > value (1) > min (1) > value (1), apk_set (2) > module_metadata (1)
    // > name (1), delivery (6); apk_description (2) > path (2), split_apk_metadata (3) > split_id
    // (1), is_master_split (2). The package name is BuildApksResult field 4.
    private fun toc(vararg variants: ByteArray): ByteArray =
        variants.fold(ByteArray(0)) { all, variant -> all + field(1, variant) } + field(4, PACKAGE.toByteArray())

    private fun variant(minSdk: Int, vararg sets: ByteArray): ByteArray =
        field(1, field(1, field(1, field(1, field(1, minSdk.toLong()))))) +
            sets.fold(ByteArray(0)) { all, set -> all + field(2, set) }

    private fun apkSet(module: String, delivery: Int, vararg descriptions: ByteArray): ByteArray =
        field(1, field(1, module.toByteArray()) + field(6, delivery.toLong())) +
            descriptions.fold(ByteArray(0)) { all, description -> all + field(2, description) }

    /** [kind] is the field of the file's metadata: 3 for a split, 5 for an instant APK. */
    private fun description(path: String, split: String? = null, master: Boolean = false, kind: Int = 3): ByteArray {
        var metadata = ByteArray(0)
        if (split != null) metadata += field(1, split.toByteArray())
        if (master) metadata += field(2, 1L)
        return field(2, path.toByteArray()) + field(kind, metadata)
    }

    /**
     * A compiled manifest with a UTF-16 string pool, as older tools write them:
     * `<manifest package="[packageName]" split="[split]">`. [splitLength] overrides the length the
     * pool gives the split's name, which honest manifests never do.
     */
    private fun utf16Manifest(packageName: String, split: String, splitLength: Int? = null): ByteArray {
        val strings = listOf("manifest", "package", packageName, "split", split)
        val pool = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        for ((index, text) in strings.withIndex()) {
            offsets += pool.size()
            val length = if (index == 4 && splitLength != null) splitLength else text.length
            if (length > 0x7FFF) {
                pool.write(le16(0x8000 or (length ushr 16)))
                pool.write(le16(length and 0xFFFF))
            } else {
                pool.write(le16(length))
            }
            for (char in text) pool.write(le16(char.code))
            pool.write(le16(0))
        }
        while (pool.size() % 4 != 0) pool.write(0)
        val stringsStart = 28 + 4 * strings.size
        val poolChunk = le16(0x0001) + le16(28) + le32(stringsStart + pool.size()) + le32(strings.size) + le32(0) +
            le32(0) + le32(stringsStart) + le32(0) + offsets.fold(ByteArray(0)) { all, offset -> all + le32(offset) } +
            pool.toByteArray()
        val attributes = listOf(1 to 2, 3 to 4)
        val element = le16(0x0102) + le16(16) + le32(16 + 20 + 20 * attributes.size) + le32(1) + le32(-1) +
            le32(-1) + le32(0) + le16(20) + le16(20) + le16(attributes.size) + le16(0) + le16(0) + le16(0) +
            attributes.fold(ByteArray(0)) { all, (name, value) ->
                all + le32(-1) + le32(name) + le32(value) + le16(8) + byteArrayOf(0, 0x03) + le32(value)
            }
        val body = poolChunk + element
        return le16(0x0003) + le16(8) + le32(8 + body.size) + body
    }

    private fun le16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), (value ushr 8 and 0xFF).toByte())

    private fun le32(value: Int): ByteArray = le16(value and 0xFFFF) + le16(value ushr 16 and 0xFFFF)

    private fun field(number: Int, bytes: ByteArray): ByteArray = varint((number shl 3 or 2).toLong()) + varint(bytes.size.toLong()) + bytes

    private fun field(number: Int, value: Long): ByteArray = varint((number shl 3).toLong()) + varint(value)

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (rest and 0x7FL.inv() != 0L) {
            out.write(((rest and 0x7F) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    companion object {
        const val PACKAGE = "com.scrcpy.remote.bundlefixture"
        val SPLITS = setOf(null, "config.ldpi", "config.mdpi", "config.tvdpi", "config.hdpi", "config.xhdpi",
            "config.xxhdpi", "config.xxxhdpi", "config.fr", "config.x86_64", "config.arm64_v8a", "config.armeabi_v7a")

        /** The fixtures the instrumented tests install, read here from the source tree. */
        fun fixtureFile(name: String): File =
            File("src/androidTest/assets/install-fixtures/$name").also {
                check(it.isFile) { "Missing fixture ${it.absolutePath}; run tools/make-bundle-fixtures.ps1" }
            }

        fun fixture(name: String): ByteArray = fixtureFile(name).readBytes()

        fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }
}
