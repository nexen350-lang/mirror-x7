package com.scrcpy.remote.session

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.StreamPacket
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioPlaybackTest {
    @Test fun rawPcmCatchesUpAfterBurstAndKeepsPlaying() = exercise(ScrcpyCodecs.AUDIO_RAW)
    @Test fun opusStillDecodesAndPlaysAfterCatchUp() = exercise(ScrcpyCodecs.AUDIO_OPUS)
    @Test fun aacStillDecodesAndPlaysAfterCatchUp() = exercise(ScrcpyCodecs.AUDIO_AAC)
    @Test fun flacLargeFramesStillDecodeAndPlayAfterCatchUp() = exercise(ScrcpyCodecs.AUDIO_FLAC)

    @Test fun customLowBufferPreservesOneFullFlacBlockWithoutBacklog() = exerciseLowBufferFlac(1)

    @Test fun customLowBufferPreservesTwoConsecutiveFlacBlocks() = exerciseLowBufferFlac(2)

    @Test fun malformedFlacBlockBoundsFailBeforeCreatingOutput() {
        val source = Source(ScrcpyCodecs.AUDIO_FLAC)
        val error = AtomicReference<Exception>()
        val player = AudioPlayer(source, error::set, {}, bufferMillis = 20)
        val config = ByteArray(34).apply { this[1] = 16 }
        try {
            player.start()
            source.packets.put(StreamPacket.Media(0, true, false, config, config.size))
            await { error.get() != null }
            assertTrue(error.get() is IllegalArgumentException)
            assertEquals("Invalid FLAC block size", error.get()?.message)
            assertNull(player.playbackStats())
        } finally {
            player.stop()
            assertTrue(player.awaitStopped(3_000))
        }
    }

    private fun exerciseLowBufferFlac(blockCount: Int) {
        assumeTrue("No local FLAC decoder", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { !it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_AUDIO_FLAC) })
        val packets = encode(ScrcpyCodecs.AUDIO_FLAC)
        val config = packets.first { it.isConfig }
        val header = config.data
        assertTrue("FLAC STREAMINFO is missing its block sizes", config.length >= 4)
        val minimumBlock = ((header[0].toInt() and 255) shl 8) or (header[1].toInt() and 255)
        val maximumBlock = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        assumeTrue("The fixture needs a full FLAC block larger than the 20 ms target plus 60 ms slack",
            minimumBlock == maximumBlock && minimumBlock > RATE * 80 / 1_000)
        val source = Source(ScrcpyCodecs.AUDIO_FLAC)
        val error = AtomicReference<Exception>()
        val player = AudioPlayer(source, error::set, {}, bufferMillis = 20)
        val expectedFrames = minimumBlock.toLong() * blockCount
        try {
            player.start()
            source.packets.put(config)
            packets.filter { !it.isConfig }.take(blockCount).forEach { source.packets.put(it) }
            await {
                error.get() != null || player.playbackStats()?.let {
                    it.droppedFrames > 0 || it.writtenFrames >= expectedFrames
                } == true
            }
            assertNull(error.get())
            val stats = checkNotNull(player.playbackStats())
            assertEquals("Ordinary codec blocks are not a playback backlog", 0L, stats.droppedFrames)
            assertEquals("Every decoded sample from the full blocks must reach the output",
                expectedFrames, stats.writtenFrames)
        } finally {
            player.stop()
            assertTrue("FLAC playback workers did not stop", player.awaitStopped(3_000))
        }
    }

    @Test fun actualAudioTrackAdvancesForIntegerAndFloatPcm() {
        for (encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) {
            for (channels in listOf(1, 2)) {
                val output = AudioTrackOutput(RATE, channels, encoding)
                try {
                    output.setMuted(false)
                    output.play()
                    val pcm = tone(0, RATE / 2, channels, encoding)
                    PcmWriter.writeFully(pcm, pcm.size, { true }, output::write)
                    await { output.playbackHeadFrames > 0 }
                    output.setMuted(true)
                    val before = output.playbackHeadFrames
                    PcmWriter.writeFully(pcm, pcm.size, { true }, output::write)
                    await { output.playbackHeadFrames > before }
                } finally { output.close() }
            }
        }
    }

    @Test fun repeatedStopsBeforeOrDuringStartupDoNotLeakPlaybackWorkers() {
        repeat(5) {
            val source = Source(ScrcpyCodecs.AUDIO_RAW)
            val error = AtomicReference<Exception>()
            val player = AudioPlayer(source, error::set, {})
            player.start()
            source.packets.put(StreamPacket.Media(0, false, false, tone(0, 960), 960 * 4))
            player.stop()
            assertTrue(player.awaitStopped(3_000))
            assertNull(error.get())
        }
    }

    private fun exercise(codecId: Int) {
        val packets = if (codecId == ScrcpyCodecs.AUDIO_RAW) {
            (0 until 100).map { index ->
                val data = tone(index * 960, 960)
                StreamPacket.Media(index * 20_000L, false, false, data, data.size)
            }
        } else encode(codecId)
        val source = Source(codecId)
        val error = AtomicReference<Exception>()
        val playing = AtomicInteger()
        val player = AudioPlayer(source, error::set, { playing.incrementAndGet() })
        try {
            player.start()
            // Deliver a one-second accumulated network backlog, then resume normal arrival.
            val split = packets.indexOfFirst { !it.isConfig && it.pts >= 1_000_000 }.takeIf { it >= 0 }
                ?: error("Encoder did not produce a long enough fixture")
            packets.take(split).forEach { source.packets.put(it) }
            // A slow decoder may drain the burst at playback speed, so a drop is not guaranteed.
            // Deterministic overflow/drop ordering is covered with a blocked output in JVM tests.
            await { error.get() != null || (player.playbackStats()?.writtenFrames ?: 0) > 0 }
            assertNull(error.get())
            val afterBurst = checkNotNull(player.playbackStats())
            val maximum = RATE * (if (codecId == ScrcpyCodecs.AUDIO_FLAC) 180 else 110) / 1_000
            assertTrue(afterBurst.queuedFrames <= maximum)
            var previousPts = packets[split].pts
            for ((index, packet) in packets.drop(split).withIndex()) {
                val interval = ((packet.pts - previousPts) / 1_000).coerceIn(0, 120)
                SystemClock.sleep(interval)
                source.packets.put(packet)
                previousPts = packet.pts
                if (index == 2) player.setMuted(true)
                if (index == 5) player.setMuted(false)
            }
            await { error.get() != null || source.reads.get() == packets.size }
            await { error.get() != null || (player.playbackStats()?.writtenFrames ?: 0) > afterBurst.writtenFrames }
            assertTrue(checkNotNull(player.playbackStats()).queuedFrames <= maximum)
            assertNull("Audio failed for ${ScrcpyCodecs.codecName(codecId)}", error.get())
            assertEquals(1, playing.get())
            assertEquals(packets.size, source.reads.get())
        } finally {
            player.stop()
            assertTrue("Playback workers did not stop", player.awaitStopped(3_000))
        }
    }

    private class Source(override val codecId: Int) : PacketSource {
        val packets = LinkedBlockingQueue<StreamPacket>()
        val reads = AtomicInteger()
        override fun readPacket(): StreamPacket = packets.take().also { reads.incrementAndGet() }
    }

    private fun encode(codecId: Int): List<StreamPacket.Media> {
        val mime = checkNotNull(ScrcpyCodecs.audioMimeType(codecId))
        assumeTrue("No local encoder for $mime", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } })
        val codec = MediaCodec.createEncoderByType(mime)
        val packets = mutableListOf<StreamPacket.Media>()
        var config: ByteArray? = null
        var started = false
        try {
            val format = MediaFormat.createAudioFormat(mime, RATE, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                if (codecId == ScrcpyCodecs.AUDIO_AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            var submittedFrames = 0
            var eosQueued = false
            var finished = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!finished && SystemClock.elapsedRealtime() < deadline) {
                if (!eosQueued) {
                    val index = codec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(index))
                        input.clear()
                        val frames = minOf(960, input.remaining() / 4, RATE * 2 - submittedFrames)
                        val data = tone(submittedFrames, frames)
                        input.put(data)
                        eosQueued = frames == 0
                        codec.queueInputBuffer(index, 0, data.size, submittedFrames * 1_000_000L / RATE,
                            if (eosQueued) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        submittedFrames += frames
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 1_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.getByteBuffer("csd-0")?.let { buffer ->
                            config = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        assertTrue(index >= 0)
                        try {
                            if (info.size > 0) {
                                val bytes = ByteArray(info.size)
                                checkNotNull(codec.getOutputBuffer(index)).apply { position(info.offset); get(bytes) }
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                                else packets += StreamPacket.Media(info.presentationTimeUs, false, false, bytes, bytes.size)
                            }
                            finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                    }
                }
            }
            assertTrue("Encoder did not finish $mime fixture", finished)
            // Some older software encoders end the stream without ever emitting codec-specific
            // data. Without it there is no valid fixture to play, so the device cannot exercise
            // this path at all — that is an unmet precondition, not a playback failure.
            assumeTrue("Encoder for $mime produced no codec configuration", config != null)
            var header = requireNotNull(config)
            if (codecId == ScrcpyCodecs.AUDIO_OPUS && String(header, 0, 8, Charsets.US_ASCII) == "AOPUSHDR") {
                val length = ByteBuffer.wrap(header, 8, 8).order(ByteOrder.nativeOrder()).long.toInt()
                header = header.copyOfRange(16, 16 + length)
            }
            if (codecId == ScrcpyCodecs.AUDIO_FLAC && header.size > FLAC_PREFIX &&
                String(header, 0, 4, Charsets.US_ASCII) == "fLaC"
            ) {
                // The server sends only the STREAMINFO block: it strips the "fLaC" marker and the
                // four byte block header before the packet goes out. Taking the encoder's own
                // configuration verbatim meant this test fed the decoder something the device
                // never sends, and so never exercised the path that actually runs.
                header = header.copyOfRange(FLAC_PREFIX, header.size)
            }
            return listOf(StreamPacket.Media(0, true, false, header, header.size)) + packets
        } finally {
            if (started) codec.stop()
            codec.release()
        }
    }

    private fun tone(start: Int, frames: Int, channels: Int = 2, encoding: Int = AudioFormat.ENCODING_PCM_16BIT): ByteArray {
        val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val data = ByteBuffer.allocate(frames * channels * bytes).order(ByteOrder.nativeOrder())
        repeat(frames) { index ->
            val value = sin(2 * Math.PI * 440 * (start + index) / RATE) * 0.1
            repeat(channels) {
                if (encoding == AudioFormat.ENCODING_PCM_FLOAT) data.putFloat(value.toFloat())
                else data.putShort((value * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return data.array()
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("Timed out waiting for audio playback", predicate())
    }

    private companion object {
        const val RATE = 48_000

        /** `fLaC` plus the four byte STREAMINFO block header the server strips before sending. */
        const val FLAC_PREFIX = 8
    }
}
