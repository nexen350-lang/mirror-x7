package com.scrcpy.remote.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One block of a bundled legal document, written in the small part of Markdown these documents use. */
internal sealed interface LegalBlock {
    val text: String

    data class Title(override val text: String) : LegalBlock
    data class Heading(override val text: String) : LegalBlock
    data class Paragraph(override val text: String) : LegalBlock
    data class Bullet(override val text: String) : LegalBlock
}

internal object LegalText {
    /**
     * Splits [markdown] into a title (`# `), headings (`## `), bullets (`- `) and paragraphs. The
     * lines of one block are joined with spaces; `**bold**` marks are left for the renderer.
     */
    fun parse(markdown: String): List<LegalBlock> {
        val blocks = mutableListOf<LegalBlock>()
        val current = StringBuilder()
        var bullet = false
        fun flush() {
            if (current.isNotEmpty()) {
                val text = current.toString()
                blocks += if (bullet) LegalBlock.Bullet(text) else LegalBlock.Paragraph(text)
            }
            current.setLength(0)
            bullet = false
        }
        for (raw in markdown.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("## ") -> { flush(); blocks += LegalBlock.Heading(line.removePrefix("## ").trim()) }
                line.startsWith("# ") -> { flush(); blocks += LegalBlock.Title(line.removePrefix("# ").trim()) }
                line.startsWith("- ") -> { flush(); bullet = true; current.append(line.removePrefix("- ").trim()) }
                else -> { if (current.isNotEmpty()) current.append(' '); current.append(line) }
            }
        }
        flush()
        return blocks
    }
}

internal object PrivacyPolicy {
    const val ASSET = "legal/privacy-policy.md"
    const val UNNAMED_DEVELOPER = "the project maintainer"
    const val UNSTATED_CONTACT = "the repository issue tracker or published maintainer contact"

    /**
     * Renders the privacy policy template with the app name, publisher name, and contact details,
     * falling back to generic open-source maintainer references when unset in local builds.
     */
    fun render(template: String, app: String, developer: String, contact: String): String = template
        .replace("{{APP}}", app.trim())
        .replace("{{DEVELOPER}}", developer.trim().ifEmpty { UNNAMED_DEVELOPER })
        .replace("{{CONTACT}}", contact.trim().ifEmpty { UNSTATED_CONTACT })
}

@Serializable
internal data class OpenSourceComponent(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val copyright: String? = null,
    val note: String? = null,
    val files: List<String>,
    val artifacts: List<String> = emptyList(),
) {
    /** Whether a pattern in [artifacts] names [group]:[artifact]; either part may end in `*`. */
    fun covers(group: String, artifact: String): Boolean = artifacts.any { pattern ->
        val parts = pattern.split(':', limit = 2)
        parts.size == 2 && matches(parts[0], group) && matches(parts[1], artifact)
    }

    private fun matches(pattern: String, value: String): Boolean =
        if (pattern.endsWith("*")) value.startsWith(pattern.dropLast(1)) else value == pattern
}

@Serializable
internal data class OpenSourceNotices(val components: List<OpenSourceComponent>) {
    companion object {
        /** Where the notices live in the app's assets; [OpenSourceComponent.files] are relative to it. */
        const val ROOT = "open-source/"
        const val INDEX = "open-source/notices.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): OpenSourceNotices = json.decodeFromString(serializer(), text)
    }
}
