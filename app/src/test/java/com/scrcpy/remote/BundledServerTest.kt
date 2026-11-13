package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Pins every bundled scrcpy server to the exact artifact upstream published.
 *
 * A server jar is the one piece of this app that is not built from its own sources: it is pushed to
 * the user's device and executed there with ADB's privileges. Nothing else checks that a file in
 * `assets` is the one Genymobile released — [ServerIntegrityTest] covers the mechanism that
 * re-hashes a copy already on a device, not the identity of what is shipped.
 *
 * Only official scrcpy 5.0.1 is bundled. This test independently pins its upstream checksum,
 * size and binary protocol version and rejects extra or obsolete server assets.
 */
class BundledServerTest {

    @Test fun everyBundledServerIsTheOfficialUpstreamRelease() {
        for (server in ScrcpyServerVersion.entries) {
            val release = RELEASES.getValue(server.version)
            val bytes = File(ASSETS, server.assetName).readBytes()
            assertEquals("Bundled ${server.version} server size changed", release.size, bytes.size.toLong())
            assertEquals(
                "Bundled scrcpy server does not match the official v${server.version} release",
                release.digest,
                sha256(bytes),
            )
        }
    }

    @Test fun theAppVerifiesEachServerAgainstItsReleaseDigest() {
        // Before pushing a server the app re-hashes the asset and refuses one that differs from its
        // registered digest. Replaced without it, the jar fails every connection's integrity check,
        // which nothing caught before a session on a device.
        assertEquals(RELEASES.keys, ScrcpyServerVersion.entries.map { it.version }.toSet())
        for (server in ScrcpyServerVersion.entries) {
            val release = RELEASES.getValue(server.version)
            assertEquals(server.version, release.digest, server.sha256)
            assertEquals(server.version, release.size, server.size.toLong())
        }
    }

    @Test fun eachDeclaredVersionIsTheOneItsBinaryChecks() {
        // The version is sent on the server's own command line, and the server refuses a client
        // that sends any other: a registry entry pointing at the other jar fails every session with
        // nothing useful to show the user. Each binary inlines its version into that refusal.
        for (server in ScrcpyServerVersion.entries) {
            val dex = ZipFile(File(ASSETS, server.assetName)).use { zip ->
                zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
            }
            val checked = Regex("""The server version \(([0-9.]+)\) does not match the client""")
                .findAll(String(dex, Charsets.ISO_8859_1)).map { it.groupValues[1] }.toSet()
            assertEquals(server.assetName, setOf(server.version), checked)
        }
    }

    @Test fun theAssetsHoldExactlyTheRegisteredServers() {
        // A server left behind under an old name is dead weight in every APK, and one added without
        // an entry is a binary nothing verifies.
        val shipped = ASSETS.listFiles().orEmpty().map { it.name }.filter { it.startsWith("scrcpy-server") }.toSet()
        assertEquals(ScrcpyServerVersion.entries.map { it.assetName }.toSet(), shipped)
    }

    @Test fun eachServerHasItsOwnPathOnTheDevice() {
        // A device keeps a pushed server between sessions; its path names the exact version.
        val paths = ScrcpyServerVersion.entries.map { it.remotePath }
        assertEquals(paths.size, paths.toSet().size)
        for (server in ScrcpyServerVersion.entries) {
            assertTrue(server.remotePath, server.remotePath.startsWith("/data/local/tmp/"))
            assertTrue(server.remotePath, server.remotePath.contains(server.version))
        }
    }

    @Test fun theNewestServerIsTheDefault() {
        assertEquals(ScrcpyServerVersion.V5_0, ScrcpyServerVersion.DEFAULT)
        assertEquals(ScrcpyServerVersion.DEFAULT, ScrcpyServerVersion.of("4.1"))
        assertEquals(ScrcpyServerVersion.V5_0, ScrcpyServerVersion.of("5.0.1"))
        assertEquals(null, ScrcpyServerVersion.of("6.0"))
    }

    @Test fun legacyVersionLinksSelectThePatchUpgrade() {
        assertEquals(listOf("5.0.1"), ScrcpyServerVersion.entries.map { it.version })
        for (token in listOf("4.1", "5.0", "V4_1", "V5_0", "5.0.1")) {
            assertEquals(ScrcpyServerVersion.DEFAULT, ScrcpyServerVersion.of(token))
        }
        assertEquals(ScrcpyServerVersion.DEFAULT, ScrcpyServerVersion.of("5.0"))
        assertEquals("5.0.1", ScrcpyServerVersion.V5_0.version)
        assertEquals("V5_0", ScrcpyServerVersion.V5_0.name)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Release(val digest: String, val size: Long)

    private companion object {
        val ASSETS = File("src/main/assets")

        /**
         * The releases the app ships, from each release's `scrcpy-server-v<version>` asset at
         * https://github.com/Genymobile/scrcpy/releases (uploaded by the scrcpy maintainer, listed in
         * the release's `SHA256SUMS.txt`, whose signature verifies with the maintainer's key).
         *
         * Version, digest and size belong together — a digest identifies exactly one release — so
         * they are written as one record per release rather than as constants that can drift.
         */
        val RELEASES = mapOf(
            "5.0.1" to Release("764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536", 733_930L),
        )
    }
}