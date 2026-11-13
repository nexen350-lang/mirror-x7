package com.scrcpy.remote.ui.screenshot

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test

class ScreenshotCacheTest {
    private lateinit var root: File
    private lateinit var cache: ScreenshotCache

    @Before
    fun setUp() {
        val build = File("build").absoluteFile
        check(build.isDirectory || build.mkdirs())
        root = File(build, "screenshot-cache-test-${UUID.randomUUID()}")
        check(root.mkdir())
        cache = ScreenshotCache(root)
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) assertTrue(root.deleteRecursively())
    }

    @Test
    fun finalizedPngRestoresWithOnlyItsOpaqueToken() {
        val pending = cache.create { it.write(PNG) }
        val restored = ScreenshotCache(root).restore(pending.token)
        assertNotNull(restored)
        assertEquals(pending.token, restored!!.token)
        assertEquals(pending.suggestedName, restored.suggestedName)
        assertTrue(pending.suggestedName.endsWith(".png"))
        assertArrayEquals(PNG, cache.find(pending.token)!!.readBytes())
        assertEquals(listOf("${pending.token}.png"), cacheDirectory().list()!!.toList())
        cache.discard(restored.token)
        cache.discard(restored.token)
        assertNull(cache.restore(restored.token))
        assertTrue(cacheDirectory().list()!!.isEmpty())
    }

    @Test
    fun encoderFailureAndCancellationLeaveNoPartialFile() {
        assertThrows(IOException::class.java) {
            cache.create { it.write(PNG); throw IOException("Disk full") }
        }
        assertTrue(cacheDirectory().list()!!.isEmpty())
        assertThrows(CancellationException::class.java) {
            cache.create { it.write(PNG); throw CancellationException("Capture cancelled") }
        }
        assertTrue(cacheDirectory().list()!!.isEmpty())
    }

    @Test
    fun nonPngOrUnboundedEncoderOutputIsNeverPublished() {
        assertThrows(IOException::class.java) { cache.create { it.write(ByteArray(80)) } }
        val oversized = PNG.clone()
        ByteBuffer.wrap(oversized).putInt(16, ScreenshotCache.MAX_DIMENSION + 1)
        assertThrows(IOException::class.java) { cache.create { it.write(oversized) } }
        assertThrows(IOException::class.java) { cache.create { it.write(PNG.dropLast(1).toByteArray()) } }
        assertTrue(cacheDirectory().list()!!.isEmpty())
    }

    @Test
    fun expiredOrMissingScreenshotCannotRestoreAndIsPrunedOnNextCapture() {
        val expired = cache.create { it.write(PNG) }
        val expiredFile = requireNotNull(cache.find(expired.token))
        assertTrue(expiredFile.setLastModified(System.currentTimeMillis() - ScreenshotCache.MAX_AGE_MILLIS - 1_000))
        assertNull(cache.restore(expired.token))
        val active = cache.create { it.write(PNG) }
        assertFalse(expiredFile.exists())
        assertNotNull(cache.restore(active.token))
        cache.discard(active.token)
        assertNull(cache.restore(active.token))
    }

    @Test
    fun tokensCannotReadOrDeleteOtherFiles() {
        val unrelated = File(root, "keep.png").apply { writeBytes(PNG) }
        for (token in listOf("../keep", "..\\keep", unrelated.absolutePath, "keep", UUID.randomUUID().toString() + ".png")) {
            assertNull(cache.restore(token))
            cache.discard(token)
            assertArrayEquals(PNG, unrelated.readBytes())
        }
    }

    @Test
    fun symlinkedTokenIsRejectedWithoutDeletingItsTarget() {
        val pending = cache.create { it.write(PNG) }
        val source = requireNotNull(cache.find(pending.token))
        val target = File(root, "keep.png")
        Files.move(source.toPath(), target.toPath())
        try {
            Files.createSymbolicLink(source.toPath(), target.toPath())
        } catch (cause: UnsupportedOperationException) {
            assumeNoException("Symbolic links are unavailable on this platform", cause)
        } catch (cause: java.nio.file.FileSystemException) {
            assumeNoException("Creating symbolic links requires permission on Windows", cause)
        }
        assertNull(cache.find(pending.token))
        assertThrows(IOException::class.java) { cache.restore(pending.token) }
        assertThrows(IOException::class.java) { cache.discard(pending.token) }
        assertArrayEquals(PNG, target.readBytes())
        Files.delete(source.toPath())
    }

    @Test
    fun dimensionsAreBoundedWithoutUpscalingOrIntegerOverflow() {
        assertEquals(ScreenshotSize(1920, 1080), boundedScreenshotSize(1920, 1080))
        assertEquals(ScreenshotSize(1, 1), boundedScreenshotSize(1, 1))
        for ((width, height) in listOf(
            7680 to 4320,
            4320 to 7680,
            Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MAX_VALUE to 1,
            1 to Int.MAX_VALUE,
        )) {
            val size = boundedScreenshotSize(width, height)
            assertTrue(size.width in 1..ScreenshotCache.MAX_DIMENSION)
            assertTrue(size.height in 1..ScreenshotCache.MAX_DIMENSION)
            assertTrue(size.width.toLong() * size.height <= ScreenshotCache.MAX_PIXELS)
            assertTrue(size.width <= width && size.height <= height)
        }
        assertThrows(IllegalArgumentException::class.java) { boundedScreenshotSize(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { boundedScreenshotSize(100, -1) }
    }

    private fun cacheDirectory() = File(root, ScreenshotCache.DIRECTORY)

    private companion object {
        val PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNg+M/wHwAEAQH/cetH5QAAAABJRU5ErkJggg==",
        )
    }
}
