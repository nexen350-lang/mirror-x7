package com.scrcpy.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesTest {
    private fun excludes(name: String, section: String?): Set<String> {
        val file = listOf("src/main/res/xml/$name", "app/src/main/res/xml/$name").map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = if (section == null) doc.documentElement
            else doc.getElementsByTagName(section).item(0) as org.w3c.dom.Element
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as org.w3c.dom.Element
            e.getAttribute("domain") + ":" + e.getAttribute("path")
        }.toSet()
    }

    // Literals mirror the private prefs names in ui/ShortcutTrust.kt (PREFS) and ui/PrecisionPadBar.kt (FILE).
    @Test fun adbKeysAndRemotePreferencesAreNeverBackedUpOrTransferred() {
        val wanted = setOf(
            "file:adb/",
            "sharedpref:scrcpy_remote.xml",
            "sharedpref:shortcut_trust.xml",
            "sharedpref:precision_pad.xml",
        )
        assertTrue(excludes("backup_rules.xml", null).containsAll(wanted))
        assertTrue(excludes("data_extraction_rules.xml", "cloud-backup").containsAll(wanted))
        assertTrue(excludes("data_extraction_rules.xml", "device-transfer").containsAll(wanted))
    }
}
