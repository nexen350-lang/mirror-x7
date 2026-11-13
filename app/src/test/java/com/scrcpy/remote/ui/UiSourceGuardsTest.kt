package com.scrcpy.remote.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level UI guards verified without a Compose runtime in unit tests. */
class UiSourceGuardsTest {
    private fun src(name: String): String {
        val base = listOf("src/main/java/com/scrcpy/remote/ui/", "app/src/main/java/com/scrcpy/remote/ui/")
        return base.map { File(it + name) }.first { it.exists() }.readText()
    }

    @Test
    fun removeIsGatedWhileBusy() {
        val s = src("MainActivity.kt")
        assertTrue(s.contains("if (removeEnabled) { onDelete(); true } else false"))
        assertTrue(s.contains("enabled = removeEnabled"))
        assertTrue(s.contains("removeEnabled = !removalBusy"))
    }

    @Test
    fun encoderDialogShowsUnlistedSelection() {
        assertTrue(src("DeviceEncodersDialog.kt").contains("R.string.encoders_current_unlisted"))
    }

    @Test
    fun assetReadsAreGuarded() {
        val s = src("AppInfoDialog.kt")
        assertTrue(s.contains("runCatching { OpenSourceNotices.parse"))
        assertTrue(s.contains("app_info_load_failed"))
        assertTrue(s.contains("heading()"))
        assertTrue(s.contains("clearAndSetSemantics"))
    }

    @Test
    fun uppercaseIsLocaleAware() {
        for (f in listOf("DeviceCamerasDialog.kt", "DeviceEncodersDialog.kt", "AppInfoDialog.kt", "MainActivity.kt")) {
            assertFalse(f, Regex("\\.uppercase\\(\\)").containsMatchIn(src(f)))
        }
    }
}
