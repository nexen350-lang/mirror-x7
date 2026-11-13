package com.scrcpy.remote.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareIngestTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    /**
     * A copy can finish while the device list is stopped and wait there unclaimed. Sharing a
     * second file must withdraw it at once: otherwise the returning screen adopts the first file
     * and a device chosen before the second copy finishes is sent the file the user replaced.
     */
    @Test fun aNewShareImmediatelyWithdrawsAnEarlierUnclaimedCopy() {
        val store = ViewModelStore()
        val earlier = File(File(app.cacheDir, "shared-uploads").apply { mkdirs() }, UUID.randomUUID().toString())
        earlier.writeBytes(byteArrayOf(1, 2, 3))
        try {
            instrumentation.runOnMainSync {
                val model = MainViewModel(app)
                store.put("share-ingest-test", model)
                @Suppress("UNCHECKED_CAST")
                val ingest = MainViewModel::class.java.getDeclaredField("_shareIngest")
                    .apply { isAccessible = true }.get(model) as MutableStateFlow<ShareIngest?>
                ingest.value = ShareIngest.Ready(earlier.absolutePath, "earlier.bin")
                model.ingestShare(Uri.parse("content://com.scrcpy.remote.test.unavailable/later.bin"))
                // The later share may already have its own outcome here: reading a missing provider
                // can fail before the copy suspends, and then it resumes on this thread at once.
                // Either way, the copy it replaced is never offered again.
                val state = model.shareIngest.value
                assertTrue("The replaced copy must not remain claimable while the new one is read: $state",
                    state == null || state == ShareIngest.Unreadable)
            }
            assertFalse("The replaced copy belongs to nobody and must be deleted", earlier.exists())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            earlier.delete()
        }
    }
}
