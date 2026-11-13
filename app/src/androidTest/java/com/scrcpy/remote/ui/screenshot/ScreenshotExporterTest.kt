package com.scrcpy.remote.ui.screenshot

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.ui.MainActivity
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotExporterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val exporter = ScreenshotExporter(context)

    @Test
    fun capturedPngContainsOnlySurfacePixelsAndExportsAfterSourceActivityCloses() = runBlocking {
        val pending = withSurface { surface -> exporter.capture(surface) }
        val destination = File(context.cacheDir, "screenshot-export-test-${UUID.randomUUID()}.png")
        try {
            val recreatedExporter = ScreenshotExporter(context)
            val restored = requireNotNull(recreatedExporter.restore(pending.token))
            recreatedExporter.export(restored, Uri.fromFile(destination))
            assertNull(recreatedExporter.restore(pending.token))
            val options = BitmapFactory.Options()
            val bitmap = BitmapFactory.decodeFile(destination.absolutePath, options)
            assertNotNull(bitmap)
            requireNotNull(bitmap)
            try {
                assertEquals("image/png", options.outMimeType)
                assertEquals(WIDTH, bitmap.width)
                assertEquals(HEIGHT, bitmap.height)
                for ((x, y) in listOf(0 to 0, WIDTH / 2 to HEIGHT / 2, WIDTH - 1 to HEIGHT - 1)) {
                    assertEquals("The opaque red overlay must not be captured", SOURCE_COLOR, bitmap.getPixel(x, y))
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            exporter.discard(pending)
            if (destination.exists()) assertTrue(destination.delete())
        }
    }

    @Test
    fun cancellingDocumentPickerDiscardsTheCachedPng() = runBlocking {
        val pending = withSurface { exporter.capture(it) }
        assertNotNull(exporter.restore(pending.token))
        exporter.discard(pending)
        assertNull(exporter.restore(pending.token))
        assertFalse(File(File(context.cacheDir, ScreenshotCache.DIRECTORY), "${pending.token}.png").exists())
    }

    @Test
    fun cancelledPixelCopyProducesNoCachedFile() = runBlocking {
        val before = cacheEntries()
        withSurface { surface ->
            withContext(Dispatchers.Main.immediate) {
                // PixelCopy posts its callback to this looper, so cancellation precedes the callback.
                val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                    exporter.capture(surface)
                    fail("Cancelled capture must not return an image")
                }
                capture.cancelAndJoin()
                assertTrue(capture.isCancelled)
            }
        }
        instrumentation.waitForIdleSync()
        assertEquals(before, cacheEntries())
    }

    @Test
    fun invalidSurfaceReportsAnErrorWithoutLeavingCacheFiles() = runBlocking {
        val before = cacheEntries()
        val view = withContext(Dispatchers.Main) { SurfaceView(context) }
        try {
            exporter.capture(view)
            fail("An unattached surface cannot be captured")
        } catch (error: ScreenshotException) {
            assertEquals(context.getString(R.string.screenshot_error_not_ready), error.message)
        }
        assertEquals(before, cacheEntries())
    }

    @Test
    fun sourceWithoutVideoFramesReportsPixelCopyFailure() = runBlocking {
        val before = cacheEntries()
        withSurface(drawFrame = false) { surface ->
            try {
                exporter.capture(surface)
                fail("A surface without a queued frame cannot be captured")
            } catch (error: ScreenshotException) {
                assertTrue(
                    error.message.orEmpty().contains("PixelCopy") ||
                        error.message == context.getString(R.string.screenshot_error_timeout),
                )
            }
        }
        assertEquals(before, cacheEntries())
    }

    @Test
    fun exportFailureReportsPossiblePartialSaveAndDoesNotDeleteDestination() = runBlocking {
        val pending = withSurface { exporter.capture(it) }
        val destination = File(context.cacheDir, "screenshot-save-failure-test-${UUID.randomUUID()}")
        assertTrue(destination.mkdir())
        val unrelated = File(destination, "keep.txt").apply { writeText("Existing user content") }
        try {
            try {
                exporter.export(pending, Uri.fromFile(destination))
                fail("A directory is not a writable PNG document")
            } catch (error: ScreenshotException) {
                assertEquals(context.getString(R.string.screenshot_error_save), error.message)
                assertNotNull(error.cause)
            }
            assertEquals("Existing user content", unrelated.readText())
            assertTrue(destination.isDirectory)
            assertNull(exporter.restore(pending.token))
        } finally {
            exporter.discard(pending)
            assertTrue(unrelated.delete())
            assertTrue(destination.delete())
        }
    }

    @Test
    fun cancelledExportStillDiscardsCacheWithoutTouchingDestination() = runBlocking {
        val pending = withSurface { exporter.capture(it) }
        val destination = File(context.cacheDir, "screenshot-cancelled-export-test-${UUID.randomUUID()}.png")
        destination.writeText("Existing user content")
        try {
            val export = launch {
                cancel()
                exporter.export(pending, Uri.fromFile(destination))
            }
            export.join()
            assertTrue(export.isCancelled)
            assertNull(exporter.restore(pending.token))
            assertEquals("Existing user content", destination.readText())
        } finally {
            exporter.discard(pending)
            assertTrue(destination.delete())
        }
    }

    private suspend fun <T> withSurface(
        drawFrame: Boolean = true,
        block: suspend (SurfaceView) -> T,
    ): T {
        val ready = CountDownLatch(1)
        lateinit var surface: SurfaceView
        return ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                surface = SurfaceView(activity)
                surface.holder.setFormat(PixelFormat.RGBA_8888)
                surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (drawFrame) {
                            val canvas = holder.lockCanvas()
                            try {
                                canvas.drawColor(SOURCE_COLOR)
                            } finally {
                                holder.unlockCanvasAndPost(canvas)
                            }
                        }
                        ready.countDown()
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
                activity.setContentView(FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(surface, FrameLayout.LayoutParams(WIDTH, HEIGHT))
                    addView(View(activity).apply { setBackgroundColor(Color.RED) }, FrameLayout.LayoutParams(WIDTH, HEIGHT))
                })
            }
            assertTrue("Test SurfaceView must become ready", ready.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            block(surface)
        }
    }

    @Test
    fun aSavedScreenshotIsStillASuccessWhenClearingItsCacheCopyFails() = runBlocking {
        // The discard runs in a finally, so a failure there used to replace a completed save with
        // an error — telling the user the screenshot failed while it sat correctly in the folder
        // they picked. The stale cache copy is the ordinary prune's problem, not theirs.
        val cacheRoot = File(context.cacheDir, "exporter-cleanup-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = ScreenshotExporter(context, cacheRoot)
        val pending = withSurface { surface -> isolated.capture(surface) }
        val destination = File(context.cacheDir, "screenshot-cleanup-${UUID.randomUUID()}.png")
        val staged = File(cacheRoot, ScreenshotCache.DIRECTORY)
        try {
            // Reads still resolve; only unlinking needs write permission on the directory.
            assertTrue("Cannot revoke write permission", staged.setWritable(false, false))
            isolated.export(pending, Uri.fromFile(destination))

            val bitmap = BitmapFactory.decodeFile(destination.absolutePath)
            assertNotNull("The file the user asked for must still be written", bitmap)
            requireNotNull(bitmap).recycle()
        } finally {
            staged.setWritable(true, false)
            staged.listFiles()?.forEach { it.delete() }
            staged.delete()
            cacheRoot.delete()
            if (destination.exists()) assertTrue(destination.delete())
        }
    }

    private fun cacheEntries(): Set<String> =
        File(context.cacheDir, ScreenshotCache.DIRECTORY).list()?.toSet().orEmpty()

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 96
        val SOURCE_COLOR = Color.rgb(20, 160, 210)
    }
}
