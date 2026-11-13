package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.MediaPacketLimits
import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.ScrcpyStreamReader
import com.scrcpy.remote.scrcpy.StreamPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Verifies the demuxer against the framing produced by
 * `com.genymobile.scrcpy.device.Streamer` in scrcpy 5.0, unchanged since 4.1.
 */
class ScrcpyStreamReaderTest {

    private companion object {
        const val PACKET_FLAG_SESSION = 1L shl 63
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61
    }

    private fun buildStream(block: DataOutputStream.() -> Unit): ScrcpyStreamReader {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return ScrcpyStreamReader(buffer.toByteArray().inputStream())
    }

    /** Mirrors `Streamer.writeSessionMeta()`. */
    private fun DataOutputStream.writeSessionMeta(width: Int, height: Int, clientResize: Boolean) {
        var flags = (PACKET_FLAG_SESSION shr 32).toInt()
        if (clientResize) flags = flags or 1
        writeInt(flags)
        writeInt(width)
        writeInt(height)
    }

    /** Mirrors `Streamer.writeFrameMeta()`. */
    private fun DataOutputStream.writeMediaPacket(
        pts: Long,
        config: Boolean,
        keyFrame: Boolean,
        payload: ByteArray,
    ) {
        val ptsAndFlags = when {
            config -> PACKET_FLAG_CONFIG
            keyFrame -> pts or PACKET_FLAG_KEY_FRAME
            else -> pts
        }
        writeLong(ptsAndFlags)
        writeInt(payload.size)
        write(payload)
    }

    @Test
    fun readsCodecIdThenSessionAndMediaPackets() {
        val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val frame = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)

        val reader = buildStream {
            writeInt(ScrcpyCodecs.VIDEO_H264)
            writeSessionMeta(1080, 2400, clientResize = false)
            writeMediaPacket(0, config = true, keyFrame = false, payload = config)
            writeMediaPacket(123_456L, config = false, keyFrame = true, payload = frame)
        }

        assertEquals(ScrcpyCodecs.VIDEO_H264, reader.codecId)
        assertEquals("h264", ScrcpyCodecs.codecName(reader.codecId))

        val session = reader.readPacket() as StreamPacket.Session
        assertEquals(1080, session.width)
        assertEquals(2400, session.height)
        assertFalse(session.clientResized)

        // The payload buffer is reused, so each packet must be consumed before the next read.
        val configPacket = reader.readPacket() as StreamPacket.Media
        assertTrue(configPacket.isConfig)
        assertEquals(config.size, configPacket.length)
        assertArrayEquals(config, configPacket.copyOfPayload())

        val framePacket = reader.readPacket() as StreamPacket.Media
        assertFalse(framePacket.isConfig)
        assertTrue(framePacket.isKeyFrame)
        assertEquals(123_456L, framePacket.pts)
        assertEquals(frame.size, framePacket.length)
        assertArrayEquals(frame, framePacket.copyOfPayload())
    }

    @Test
    fun payloadBufferIsReusedAcrossPackets() {
        // Documents the contract the decoder relies on: the array is shared and only `length`
        // bytes are valid, so anything kept beyond the next read has to be copied.
        val reader = buildStream {
            writeInt(ScrcpyCodecs.VIDEO_H264)
            writeMediaPacket(1L, config = false, keyFrame = true, payload = ByteArray(64) { 1 })
            writeMediaPacket(2L, config = false, keyFrame = false, payload = ByteArray(8) { 2 })
        }

        val first = reader.readPacket() as StreamPacket.Media
        val firstCopy = first.copyOfPayload()
        val second = reader.readPacket() as StreamPacket.Media

        assertSame(first.data, second.data)
        assertEquals(8, second.length)
        // The copy is unaffected by the second read; the shared buffer is not.
        assertEquals(64, firstCopy.size)
        assertTrue(firstCopy.all { it == 1.toByte() })
    }

    @Test
    fun detectsClientResizeFlagOnSessionPackets() {
        val reader = buildStream {
            writeInt(ScrcpyCodecs.VIDEO_H265)
            writeSessionMeta(720, 1280, clientResize = true)
        }

        val session = reader.readPacket() as StreamPacket.Session
        assertTrue(session.clientResized)
        assertEquals(720, session.width)
        assertEquals(1280, session.height)
    }

    @Test
    fun ptsFlagsAreStrippedFromTimestamp() {
        val pts = 0x1FFF_FFFF_FFFF_FFFEL // 61-bit maximum-ish value
        val reader = buildStream {
            writeInt(ScrcpyCodecs.AUDIO_OPUS)
            writeMediaPacket(pts, config = false, keyFrame = true, payload = byteArrayOf(1, 2, 3))
        }

        val packet = reader.readPacket() as StreamPacket.Media
        assertEquals(pts, packet.pts)
        assertTrue(packet.isKeyFrame)
    }

    @Test
    fun mapsCodecIdsToMimeTypes() {
        assertEquals("video/avc", ScrcpyCodecs.videoMimeType(ScrcpyCodecs.VIDEO_H264))
        assertEquals("video/hevc", ScrcpyCodecs.videoMimeType(ScrcpyCodecs.VIDEO_H265))
        assertEquals("video/av01", ScrcpyCodecs.videoMimeType(ScrcpyCodecs.VIDEO_AV1))
        assertEquals("video/x-vnd.on2.vp8", ScrcpyCodecs.videoMimeType(ScrcpyCodecs.VIDEO_VP8))
        assertEquals("video/x-vnd.on2.vp9", ScrcpyCodecs.videoMimeType(ScrcpyCodecs.VIDEO_VP9))
        assertEquals("audio/opus", ScrcpyCodecs.audioMimeType(ScrcpyCodecs.AUDIO_OPUS))
        assertEquals(null, ScrcpyCodecs.audioMimeType(ScrcpyCodecs.AUDIO_RAW))
    }

    @Test fun oversizedAudioConfigAndFramesAreRejectedBeforeReadingTheirPayload() {
        for (codec in listOf(ScrcpyCodecs.AUDIO_OPUS, ScrcpyCodecs.AUDIO_AAC,
            ScrcpyCodecs.AUDIO_FLAC, ScrcpyCodecs.AUDIO_RAW)) {
            for (config in listOf(false, true)) {
                val reader = buildStream {
                    writeInt(codec)
                    writeLong(if (config) PACKET_FLAG_CONFIG else 1L)
                    writeInt(MediaPacketLimits.AUDIO_PAYLOAD_BYTES + 1)
                    // No payload: attempting to read/allocate it would produce EOF instead.
                }
                assertThrows(MediaProtocolException::class.java) { reader.readPacket() }
            }
        }
    }

    @Test fun videoAndAudioAcceptTheirExactLimitsWithoutGrowingBeyondThem() {
        for (codec in listOf(ScrcpyCodecs.VIDEO_H264, ScrcpyCodecs.AUDIO_RAW)) {
            val limit = MediaPacketLimits.maxPayloadBytes(codec)
            val reader = buildStream {
                writeInt(codec)
                writeMediaPacket(0, false, true, ByteArray(limit - 1))
                writeMediaPacket(1, false, false, ByteArray(limit))
            }
            val first = reader.readPacket() as StreamPacket.Media
            assertTrue(first.data.size <= limit)
            val second = reader.readPacket() as StreamPacket.Media
            assertEquals(limit, second.length)
            assertEquals(limit, second.data.size)
        }
    }

    @Test fun negativeAndOversizedVideoSizesAreProtocolFailures() {
        for (size in listOf(-1, Int.MIN_VALUE, MediaPacketLimits.VIDEO_PAYLOAD_BYTES + 1, Int.MAX_VALUE)) {
            val reader = buildStream {
                writeInt(ScrcpyCodecs.VIDEO_H265)
                writeLong(0)
                writeInt(size)
            }
            assertThrows(MediaProtocolException::class.java) { reader.readPacket() }
        }
    }

    @Test fun malformedSessionDimensionsAreProtocolFailures() {
        val reader = buildStream {
            writeInt(ScrcpyCodecs.VIDEO_H264)
            writeSessionMeta(0, 1080, false)
        }
        assertThrows(MediaProtocolException::class.java) { reader.readPacket() }
    }
}
