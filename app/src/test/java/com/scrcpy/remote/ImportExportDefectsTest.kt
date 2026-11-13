package com.scrcpy.remote

import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PreferenceStorage
import com.scrcpy.remote.data.ProfileBackupCodec
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.session.ShareCache
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportExportDefectsTest {
    @get:Rule val temp = TemporaryFolder()

    private class MemoryStorage(initial: Map<String, Any?>) : PreferenceStorage {
        val values = initial.toMutableMap()
        override fun read(): Map<String, *> = values.toMap()
        override fun write(values: Map<String, Any?>): Boolean {
            values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
            return true
        }
    }

    private fun damagedMaxSize(): String {
        val ok = Json.parseToJsonElement(SettingsCodec.encodeSettings(MirrorSettings())).jsonObject
        return JsonObject(ok + ("maxSize" to JsonPrimitive("bad"))).toString()
    }

    @Test fun repeatedSavesDoNotReReportADismissedIssue() {
        val storage = MemoryStorage(mapOf("settings" to damagedMaxSize()))
        val repository = DeviceRepository(storage)
        assertTrue(repository.errors.value.any { it.field == "maxSize" })
        repository.acknowledge(repository.errors.value)
        assertTrue(repository.updateSettings { it.copy(stayAwake = true) })
        assertTrue(repository.updateSettings { it.copy(stayAwake = false) })
        assertTrue("dismissed issues must stay dismissed: ${repository.errors.value}",
            repository.errors.value.none { it.field == "maxSize" })
    }

    @Test fun aLeadingByteOrderMarkIsAccepted() {
        val snapshot = ConfigurationSnapshot(emptyList(), MirrorSettings())
        val bytes = ProfileBackupCodec.encode(snapshot)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        assertEquals(snapshot, ProfileBackupCodec.decode(withBom))
    }

    @Test fun totalCapEvictsOldestFirstAndKeepsTheNewCopy() {
        val dir = temp.newFolder()
        fun file(name: String, age: Long) = File(dir, name).apply {
            writeBytes(ByteArray(100)); setLastModified(1_000_000L + age)
        }
        val oldest = file("a", 0); val middle = file("b", 10); val newest = file("c", 20)
        ShareCache.trimToTotal(dir, maxTotal = 250, keep = newest)
        assertFalse(oldest.exists()); assertTrue(middle.exists()); assertTrue(newest.exists())
        ShareCache.trimToTotal(dir, maxTotal = 0, keep = newest)
        assertFalse(middle.exists()); assertTrue("the new copy is never evicted", newest.exists())
    }
}
