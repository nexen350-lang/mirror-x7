package com.scrcpy.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the session control panel against hard-coded colours.
 *
 * The panel was written against a dark scrim, so some of its text is literal white while its
 * neighbours — `PanelButton`, `PanelSectionLabel` — take their colours from the theme. Both look
 * right today only because [MirrorActivity] pins the session to `darkTheme = true`: the literal
 * colours are invisible under any light-themed host, so the panel silently depends on that one
 * caller. Making every colour come from the theme removes the dependency.
 *
 * This is a source check rather than a rendered one on purpose. The palette contrast test cannot
 * see a composable that ignores the palette, and capturing a single text node picks up whatever
 * neighbouring content shares its bounds, so neither notices a literal colour creeping back.
 */
class PanelThemeColorsTest {

    private val panel = File("src/main/java/com/scrcpy/remote/ui/FloatingControls.kt")

    @Test fun theSessionPanelTakesEveryColourFromTheTheme() {
        assertTrue("Expected to find ${panel.absolutePath}", panel.isFile)
        val offenders = panel.readLines().withIndex().filter { (_, line) ->
            val code = line.substringBefore("//")
            LITERAL_COLOR.containsMatchIn(code)
        }
        assertTrue(
            "The session panel must use MaterialTheme.colorScheme so it stays readable in both " +
                "themes, but these lines hard-code a colour:\n" +
                offenders.joinToString("\n") { (index, line) -> "  ${index + 1}: ${line.trim()}" },
            offenders.isEmpty(),
        )
    }

    private companion object {
        /** `Color.White`, `Color.Black` and literal `Color(0x…)` values. */
        val LITERAL_COLOR = Regex("""\bColor\.(White|Black)\b|\bColor\(0x""")
    }
}
