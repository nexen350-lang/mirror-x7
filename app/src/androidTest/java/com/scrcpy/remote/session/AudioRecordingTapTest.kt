package com.scrcpy.remote.session

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The encoded sound a recording takes from the audio stream, beside what it plays. */
@RunWith(AndroidJUnit4::class)
class AudioRecordingTapTest {

    @Test fun aRecordingGetsTheConfigurationThatWentPastThenEveryPacketOnce() {
        val sound = EncodedMedia.aac(seconds = 0.4)
        val source = Source(ScrcpyCodecs.AUDIO_AAC)
        val sink = Sink()
        val player = AudioPlayer(source, {}, {}, bufferMillis = 20)
        try {
            player.start()
            source.offer(config(sound))
            sound.frames.take(5).forEach { source.offer(packet(it)) }
            await { source.reads.get() == 6 && source.packets.isEmpty() }
            // Attached part way through, as a recording started mid-session is. Packets still
            // queued for the worker when it is attached are its to take too.
            player.setRecorder(sink)
            sound.frames.drop(5).forEach { source.offer(packet(it)) }
            await { sink.samples.lastOrNull()?.first == sound.frames.last().ptsUs }
            assertEquals("The configuration comes first, once", listOf("format"), sink.events.take(1))
            assertEquals(1, sink.events.count { it == "format" })
            assertArrayEquals(sound.config, sink.formats.single())
            val all = sound.frames.map { it.ptsUs }
            val taken = sink.samples.map { it.first }
            val first = all.indexOf(taken.first())
            assertTrue("Everything offered after attaching is there", first in 0..5)
            assertEquals("A contiguous run to the end, each packet once", all.subList(first, all.size), taken)
            sound.frames.drop(first).zip(sink.samples).forEach { (frame, copy) -> assertArrayEquals(frame.data, copy.second) }
            assertEquals(0, sink.stopped.get())
        } finally {
            player.stop()
            assertTrue(player.awaitStopped(3_000))
        }
    }

    @Test fun aFailingSoundStreamEndsTheRecording() {
        val sound = EncodedMedia.aac(seconds = 0.2)
        val source = Source(ScrcpyCodecs.AUDIO_AAC)
        val sink = Sink()
        val error = AtomicReference<Exception>()
        val player = AudioPlayer(source, error::set, {}, bufferMillis = 20)
        try {
            player.setRecorder(sink)
            player.start()
            source.offer(config(sound))
            sound.frames.take(3).forEach { source.offer(packet(it)) }
            await { sink.samples.size == 3 }
            source.offer(StreamPacket.Session(640, 480, false))
            await { error.get() != null }
            assertEquals("Told once that the sound stopped", 1, sink.stopped.get())
        } finally {
            player.stop()
            assertTrue(player.awaitStopped(3_000))
        }
    }

    @Test fun endingTheSessionIsNotASoundFailure() {
        val sound = EncodedMedia.aac(seconds = 0.2)
        val source = Source(ScrcpyCodecs.AUDIO_AAC)
        val sink = Sink()
        val player = AudioPlayer(source, {}, {}, bufferMillis = 20)
        player.setRecorder(sink)
        player.start()
        source.offer(config(sound))
        sound.frames.take(3).forEach { source.offer(packet(it)) }
        await { sink.samples.size == 3 }
        player.stop()
        assertTrue(player.awaitStopped(3_000))
        assertEquals(0, sink.stopped.get())
    }

    @Test fun detachingStopsTheCopies() {
        val sound = EncodedMedia.aac(seconds = 0.4)
        val source = Source(ScrcpyCodecs.AUDIO_AAC)
        val sink = Sink()
        val player = AudioPlayer(source, {}, {}, bufferMillis = 20)
        try {
            player.setRecorder(sink)
            player.start()
            source.offer(config(sound))
            sound.frames.take(4).forEach { source.offer(packet(it)) }
            await { sink.samples.size == 4 }
            player.setRecorder(null)
            sound.frames.drop(4).forEach { source.offer(packet(it)) }
            await { source.reads.get() == sound.frames.size + 1 && source.packets.isEmpty() }
            SystemClock.sleep(200)
            assertEquals(4, sink.samples.size)
        } finally {
            player.stop()
            assertTrue(player.awaitStopped(3_000))
        }
    }

    @Test fun soundAnMp4CannotHoldIsNeverOffered() {
        val source = Source(ScrcpyCodecs.AUDIO_RAW)
        val sink = Sink()
        val player = AudioPlayer(source, {}, {}, bufferMillis = 20)
        try {
            player.setRecorder(sink)
            player.start()
            repeat(5) { index ->
                val pcm = ByteArray(960 * 4)
                source.offer(StreamPacket.Media(index * 20_000L, false, false, pcm, pcm.size))
            }
            await { source.reads.get() == 5 && source.packets.isEmpty() }
            SystemClock.sleep(200)
            assertEquals(emptyList<String>(), sink.events)
        } finally {
            player.stop()
            assertTrue(player.awaitStopped(3_000))
        }
    }

    private class Source(override val codecId: Int) : PacketSource {
        val packets = LinkedBlockingQueue<StreamPacket>()
        val reads = AtomicInteger()
        fun offer(packet: StreamPacket) = packets.put(packet)
        override fun readPacket(): StreamPacket = packets.take().also { reads.incrementAndGet() }
    }

    private class Sink : SoundRecording {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val formats: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val samples: MutableList<Pair<Long, ByteArray>> = Collections.synchronizedList(mutableListOf())
        val stopped = AtomicInteger()
        override fun onAudioFormat(config: ByteArray) { events += "format"; formats += config.copyOf() }
        override fun onAudioSample(data: ByteArray, length: Int, ptsUs: Long) {
            events += "sample"
            samples += ptsUs to data.copyOf(length)
        }
        override fun onAudioStopped() { events += "stopped"; stopped.incrementAndGet() }
    }

    private fun config(stream: EncodedMedia.Stream) = StreamPacket.Media(0, true, false, stream.config, stream.config.size)

    // The reader hands over buffers longer than the packet; only `length` is meant.
    private fun packet(frame: EncodedMedia.Frame) =
        StreamPacket.Media(frame.ptsUs, false, false, frame.data + ByteArray(7), frame.data.size)

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("Timed out", predicate())
    }
}
