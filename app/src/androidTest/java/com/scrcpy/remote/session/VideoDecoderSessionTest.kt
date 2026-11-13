package com.scrcpy.remote.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VideoDecoderSessionTest {
    private data class Header(val width: Int, val height: Int, val generation: Long, val clientResized: Boolean)

    @Test fun captureSessionCallbackPreservesResizeFlagsAndExistingSizeNotifications() {
        val packets = LinkedBlockingQueue<StreamPacket>()
        val source = object : PacketSource {
            override val codecId = ScrcpyCodecs.VIDEO_H264
            override fun readPacket(): StreamPacket = packets.take()
        }
        val sizes = CopyOnWriteArrayList<Triple<Int, Int, Long>>()
        val captures = CopyOnWriteArrayList<Header>()
        val callbackThreads = CopyOnWriteArrayList<String>()
        val notified = CountDownLatch(3)
        val failure = AtomicReference<Exception>()
        val decoder = VideoDecoder(
            source,
            onVideoSizeChanged = { width, height, generation -> sizes += Triple(width, height, generation) },
            onError = failure::set,
            onDecodedFormat = {},
            onCaptureSession = { width, height, generation, clientResized ->
                captures += Header(width, height, generation, clientResized)
                callbackThreads += Thread.currentThread().name
                notified.countDown()
            },
        )
        try {
            decoder.start()
            packets.put(StreamPacket.Session(640, 480, false))
            packets.put(StreamPacket.Session(800, 600, true))
            packets.put(StreamPacket.Session(800, 600, false))
            assertTrue("Session headers were not acknowledged: ${failure.get()}", notified.await(3, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertEquals(listOf(
                Header(640, 480, 1, false),
                Header(800, 600, 2, true),
                Header(800, 600, 3, false),
            ), captures)
            assertEquals(captures.map { Triple(it.width, it.height, it.generation) }, sizes)
            assertEquals(setOf("scrcpy-video-decoder"), callbackThreads.toSet())
            assertEquals("A header acknowledgment does not require decoded frames", 0L, decoder.frameStats().decodedFrames)
        } finally {
            decoder.stop()
            assertTrue(decoder.awaitStopped(3_000))
        }
    }
}
