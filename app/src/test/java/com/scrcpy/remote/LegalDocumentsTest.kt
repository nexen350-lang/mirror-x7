package com.scrcpy.remote

import com.scrcpy.remote.ui.LegalBlock
import com.scrcpy.remote.ui.LegalText
import com.scrcpy.remote.ui.OpenSourceComponent
import com.scrcpy.remote.ui.OpenSourceNotices
import com.scrcpy.remote.ui.PrivacyPolicy
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class LegalDocumentsTest {
    private val policy = File("src/main/assets/legal/privacy-policy.md").readText()
    private val notices = OpenSourceNotices.parse(File("src/main/assets/open-source/notices.json").readText())

    @Test fun theBundledPolicyReadsAsATitleHeadingsParagraphsAndBullets() {
        val blocks = LegalText.parse(policy)
        assertEquals(LegalBlock.Title("Privacy policy for {{APP}}"), blocks.first())
        assertTrue(blocks.contains(LegalBlock.Heading("What the app handles")))
        assertTrue(blocks.count { it is LegalBlock.Bullet } > 15)
        assertTrue(blocks.count { it is LegalBlock.Paragraph } > 5)
        assertTrue(blocks.none { it.text.contains('\n') || it.text.isBlank() })
    }

    @Test fun wrappedLinesJoinAndEachBulletStandsAlone() {
        val blocks = LegalText.parse(
            "# Title\n\nFirst line\nsecond line.\n\n## Part\n\n- One\n  continued.\n- Two.\n\nAfter.\n")
        assertEquals(listOf(
            LegalBlock.Title("Title"),
            LegalBlock.Paragraph("First line second line."),
            LegalBlock.Heading("Part"),
            LegalBlock.Bullet("One continued."),
            LegalBlock.Bullet("Two."),
            LegalBlock.Paragraph("After."),
        ), blocks)
    }

    @Test fun thePolicyNamesTheAppItsDeveloperAndTheirContact() {
        val text = PrivacyPolicy.render(policy, "Pocket Mirror", "Jane Doe", "privacy@example.com")
        assertFalse(text, text.contains("{{"))
        assertTrue(text.contains("Pocket Mirror is published by Jane Doe."))
        assertTrue(text.contains("Questions about privacy can be sent to Jane Doe: privacy@example.com."))
    }

    @Test fun aBuildWithoutADeveloperStillReadsAsSentences() {
        val text = PrivacyPolicy.render(policy, "Pocket Mirror", " ", "")
        assertFalse(text, text.contains("{{"))
        assertTrue(text.contains("Pocket Mirror is published by ${PrivacyPolicy.UNNAMED_DEVELOPER}."))
        assertTrue(text.contains("how to contact the developer: ${PrivacyPolicy.UNSTATED_CONTACT}."))
    }

    @Test fun thePolicyDisclosesRemoteAudioAndLiveClipboardReplacement() {
        assertTrue(policy.contains("connected device's microphone, or call audio"))
        assertTrue(policy.contains("any consent required by applicable law"))
        assertTrue(policy.contains("even when automatic clipboard syncing is off"))
        assertTrue(policy.contains("does not restore them afterwards"))
    }

    @Test fun theWebPolicyMatchesTheBundledAudioClipboardAndDateDisclosures() {
        val web = File("../docs/privacy-policy.html").readText()
            .replace("&#39;", "'").replace("&quot;", "\"")
            .replace(Regex("<[^>]+>"), "")
        val disclosures = policy.lineSequence().filter {
            it.startsWith("Last updated:") || it.startsWith("- **Screen and sound.") ||
                it.startsWith("- **Clipboard.")
        }.toList()
        assertEquals(3, disclosures.size)
        disclosures.forEach { line ->
            val plain = line.removePrefix("- ").replace("**", "")
            assertTrue("Web policy is missing: $plain", web.contains(plain))
        }
    }

    @Test fun theLicensesPageNamesEveryServerVersionTheAppShips() {
        // The servers are assets, not libraries in the runtime inventory, so nothing else ties their
        // notice to the jars: after an upgrade App info went on naming the old release.
        val server = notices.components.single { it.name == "scrcpy server" }
        assertEquals(ScrcpyServerVersion.entries.joinToString(" and ") { it.version }, server.version)
    }

    @Test fun everyDependencyTheAppShipsHasItsNotice() {
        val inventory = Json.parseToJsonElement(File("../third_party/runtime-artifacts.json").readText())
        val uncovered = inventory.jsonObject.getValue("artifacts").jsonArray.map { it.jsonObject }
            .map { it.getValue("group").jsonPrimitive.content to it.getValue("artifact").jsonPrimitive.content }
            .filter { (group, artifact) -> notices.components.none { it.covers(group, artifact) } }
        assertTrue("No notice for $uncovered", uncovered.isEmpty())
        assertTrue(notices.components.size >= 12)
    }

    @Test fun whatTheAppCarriesBesidesItsLibrariesIsNoticedToo() {
        val names = notices.components.map { it.name }
        assertTrue(names.toString(), "scrcpy server" in names && "SPAKE2 from BoringSSL" in names)
    }

    @Test fun everyNoticeFileIsInTheApp() {
        val roots = listOf(File("src/main/assets/open-source"), File("../third_party/libadb/src/main/assets/open-source"))
        val missing = notices.components.flatMap { it.files }.distinct()
            .filter { file -> roots.none { File(it, file).isFile } }
        assertTrue("Missing $missing", missing.isEmpty())
        assertTrue(notices.components.all { it.files.isNotEmpty() && it.url.startsWith("https://") })
    }

    @Test fun artifactPatternsNameOnlyWhatTheySay() {
        val androidx = component("androidx.*:*")
        assertTrue(androidx.covers("androidx.compose.ui", "ui-android"))
        assertTrue(androidx.covers("androidx.core", "core"))
        assertFalse(androidx.covers("org.androidx", "core"))
        val coroutines = component("org.jetbrains.kotlinx:kotlinx-coroutines-*")
        assertTrue(coroutines.covers("org.jetbrains.kotlinx", "kotlinx-coroutines-android"))
        assertFalse(coroutines.covers("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"))
        assertFalse(component("org.jetbrains:annotations").covers("org.jetbrains", "annotations-java5"))
    }

    @Test fun packagedLibadbPatchesMatchSourceModulePatches() {
        val modulePatches = File("../third_party/libadb/PATCHES.txt").readText().replace("\r\n", "\n")
        val assetPatches = File("../third_party/libadb/src/main/assets/open-source/libadb/PATCHES.txt")
            .readText().replace("\r\n", "\n")
        assertEquals(modulePatches, assetPatches)
    }

    @Test fun everyModifiedLibadbFileCarriesAModificationNotice() {
        val libadbRoot = File("../third_party/libadb")
        val manifest = Json.parseToJsonElement(File(libadbRoot, "upstream-manifest.json").readText())
            .jsonObject.getValue("unmodifiedImportedFiles").jsonObject
        for ((relPath, expectedElem) in manifest) {
            if (!relPath.endsWith(".java")) continue
            val file = File(libadbRoot, relPath)
            val actualSha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            if (actualSha != expectedElem.jsonPrimitive.content) {
                val header = file.readLines().take(15).joinToString("\n")
                assertTrue(
                    "Modified file $relPath must carry an Apache-2.0 4(b) modification notice",
                    header.contains("Modified for") || header.contains("PATCHES.txt"),
                )
            }
        }
    }

    private fun component(pattern: String) =
        OpenSourceComponent(name = "x", version = "1", license = "MIT", url = "https://x", files = listOf("x"),
            artifacts = listOf(pattern))
}
