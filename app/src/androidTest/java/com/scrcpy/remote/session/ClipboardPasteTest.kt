package com.scrcpy.remote.session

import android.app.Application
import android.content.ClipData
import android.content.res.AssetFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ClipboardPasteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @After fun clearProvider() = TestShareProvider.clear(app)

    /**
     * A clipboard item can hold only a URI, such as a file copied in a file manager. Resolving its
     * text opens the provider's stream; done on the main thread, a slow or hung provider froze the
     * mirror and its input for as long as the provider took.
     */
    @Test fun pastingAProviderBackedClipDoesNotWaitOnTheMainThread() {
        val uri = TestShareProvider.offer(app, "clip.txt", "remote text".toByteArray())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        TestShareProvider.openAssetInput = { requested, _ ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            val file = File(TestShareProvider.directory(app), requested.lastPathSegment.orEmpty())
            AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app).also { store.put("clipboard-test", it) }
        }
        // Supplied directly rather than through the system clipboard: an emulator mirrors its
        // clipboard to the host and writes the host's text back within milliseconds, which
        // replaced this URI clip with empty "host clipboard" text before the paste could read it.
        val clip = ClipData.newUri(app.contentResolver, "clip", uri)
        val returned = CountDownLatch(1)
        try {
            Handler(Looper.getMainLooper()).post { model.pushClipboard(paste = true) { clip }; returned.countDown() }
            assertTrue("The provider must actually be asked for the clip's text",
                entered.await(5, TimeUnit.SECONDS))
            assertTrue("Pasting must not hold the main thread while the provider answers",
                returned.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
