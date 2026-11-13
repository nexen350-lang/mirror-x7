package com.scrcpy.remote.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.ui.screenshot.ScreenshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenshotStateTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val cache = ScreenshotCache(app.cacheDir)

    @Test fun restoredPendingImageLaunchesOnePickerAndCancelCleansCache() = runBlocking {
        val pending = image()
        val saved = SavedStateHandle(mapOf("screenshot.token" to pending.token, "screenshot.pickerLaunched" to false))
        try {
            val vm = withContext(Dispatchers.Main) { ScreenshotViewModel(app, saved) }
            withContext(Dispatchers.Main) {
                assertTrue(vm.state.value.busy)
                assertEquals(pending.suggestedName, vm.markPickerLaunched())
                assertNull(vm.markPickerLaunched())
                vm.saveTo(null)
            }
            withTimeout(5_000) { vm.state.first { !it.busy } }
            assertNull(cache.restore(pending.token))
            assertNull(saved.get<String>("screenshot.token"))
            assertEquals(app.getString(R.string.capture_cancelled), vm.state.value.notice)
        } finally { cache.discard(pending.token) }
    }

    @Test fun recreationWhilePickerOpenDoesNotLaunchItAgainAndStillSaves() = runBlocking {
        val pending = image()
        val saved = SavedStateHandle(mapOf("screenshot.token" to pending.token, "screenshot.pickerLaunched" to true))
        val output = File(app.cacheDir, "snapshot-state-test-${UUID.randomUUID()}.png")
        try {
            val vm = withContext(Dispatchers.Main) { ScreenshotViewModel(app, saved) }
            withContext(Dispatchers.Main) {
                assertNull(vm.markPickerLaunched())
                vm.saveTo(Uri.fromFile(output))
            }
            withTimeout(5_000) { vm.state.first { !it.busy } }
            assertTrue(output.length() > 57)
            assertNull(cache.restore(pending.token))
            assertEquals(app.getString(R.string.capture_saved), vm.state.value.notice)
        } finally {
            cache.discard(pending.token)
            if (output.exists()) assertTrue(output.delete())
        }
    }

    @Test fun missingRestoredImageShowsErrorInsteadOfWaitingForever() = runBlocking {
        val saved = SavedStateHandle(mapOf("screenshot.token" to UUID.randomUUID().toString(),
            "screenshot.pickerLaunched" to true))
        val vm = withContext(Dispatchers.Main) { ScreenshotViewModel(app, saved) }
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.pendingToken)
        assertEquals(app.getString(R.string.screenshot_error_expired), vm.state.value.notice)
    }

    @Test fun restoredUnconsumedShareIsOfferedAgainWithoutLaunchingDocumentPicker() = runBlocking {
        val saved = SavedStateHandle()
        val original = withContext(Dispatchers.Main) { ScreenshotViewModel(app, saved) }
        var restored: ScreenshotViewModel? = null
        var token: String? = null
        try {
            withSurface { surface ->
                withContext(Dispatchers.Main) { original.shareCapture(surface) }
                val captured = withTimeout(15_000) {
                    original.state.first { it.shareFile != null || !it.busy }
                }
                token = captured.pendingToken
                assertNotNull("A real capture must be staged before testing restore: ${captured.notice}", captured.shareFile)
                assertTrue(requireNotNull(captured.shareFile).isFile)

                withContext(Dispatchers.Main) {
                    // Copy what capture actually persisted, including any future share-mode key.
                    val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
                    val recreated = ScreenshotViewModel(app, snapshot)
                    restored = recreated
                    assertNull("A share must never reopen the document picker", recreated.markPickerLaunched())
                    val recovered = recreated.state.value
                    assertNotNull(
                        "Restored sharing must have a consumer, not remain busy waiting for a nonexistent picker",
                        recovered.shareFile,
                    )
                    assertEquals(captured.pendingToken, recovered.pendingToken)
                    assertEquals(captured.shareFile, recovered.shareFile)
                    assertTrue(requireNotNull(recovered.shareFile).isFile)

                    recreated.consumeShare()
                    assertFalse(recreated.state.value.busy)
                    assertNull(snapshot.get<String>("screenshot.token"))
                    assertTrue("The recipient still needs the granted file after consumption",
                        requireNotNull(recovered.shareFile).isFile)
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                original.consumeShare()
                restored?.consumeShare()
            }
            token?.let(cache::discard)
        }
    }

    private suspend fun withSurface(block: suspend (SurfaceView) -> Unit) {
        val ready = CountDownLatch(1)
        lateinit var surface: SurfaceView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                surface = SurfaceView(activity)
                surface.holder.setFormat(PixelFormat.RGBA_8888)
                surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        val canvas = holder.lockCanvas()
                        try {
                            canvas.drawColor(Color.GREEN)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                        ready.countDown()
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
                activity.setContentView(FrameLayout(activity).apply {
                    addView(surface, FrameLayout.LayoutParams(128, 96))
                })
            }
            assertTrue("Test SurfaceView must become ready", ready.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            block(surface)
        }
    }

    private fun image(): com.scrcpy.remote.ui.screenshot.PendingScreenshot {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            return cache.create { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
