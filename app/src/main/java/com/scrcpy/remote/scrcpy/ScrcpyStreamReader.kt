package com.scrcpy.remote.scrcpy

import java.io.DataInputStream
import java.io.InputStream

/**
 * One packet as framed by `com.genymobile.scrcpy.device.Streamer`.
 *
 * Session and media packets are distinguished by the most significant bit of the 12-byte header.
 */
sealed class StreamPacket {

    /** Emitted on the video socket for every capture session (i.e. on every rotation/fold). */
    data class Session(val width: Int, val height: Int, val clientResized: Boolean) : StreamPacket()

    /**
     * A media packet.
     *
     * [data] is a shared, reused buffer and only the first [length] bytes are valid: it must be
     * consumed before the next call to [ScrcpyStreamReader.readPacket]. Use [copyOfPayload] to
     * retain it beyond that, which is what config packets need.
     */
    data class Media(
        val pts: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val data: ByteArray,
        val length: Int,
    ) : StreamPacket() {
        fun copyOfPayload(): ByteArray = data.copyOf(length)

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Media && pts == other.pts && isConfig == other.isConfig &&
                isKeyFrame == other.isKeyFrame && length == other.length &&
                data.regionMatches(other.data, length))

        override fun hashCode(): Int {
            var result = pts.hashCode()
            result = 31 * result + isConfig.hashCode()
            result = 31 * result + isKeyFrame.hashCode()
            result = 31 * result + length
            return result
        }

        private fun ByteArray.regionMatches(other: ByteArray, count: Int): Boolean {
            if (size < count || other.size < count) return false
            for (i in 0 until count) if (this[i] != other[i]) return false
            return true
        }
    }
}

/**
 * Reads the video or audio stream produced by scrcpy-server.
 *
 * The stream starts with the codec id (`u32`), followed by session packets (video only) and
 * media packets prefixed with a 12-byte frame header.
 */
interface PacketSource {
    val codecId: Int
    fun readPacket(): StreamPacket
}

class ScrcpyStreamReader(input: InputStream) : PacketSource {

    private val dis = DataInputStream(input.buffered(BUFFER_SIZE))

    /** Codec id as sent by the server, see [ScrcpyCodecs]. */
    override val codecId: Int = dis.readInt()
    private val maxPacketSize = MediaPacketLimits.maxPayloadBytes(codecId)

    override fun readPacket(): StreamPacket {
        dis.readFully(header)

        val isSession = (header[0].toInt() and 0x80) != 0
        return if (isSession) {
            val clientResized = (header[3].toInt() and 0x01) != 0
            val width = readInt(header, 4)
            val height = readInt(header, 8)
            if (width !in 1..65535 || height !in 1..65535) {
                throw MediaProtocolException("Invalid capture size: ${width}x$height")
            }
            StreamPacket.Session(
                width = width,
                height = height,
                clientResized = clientResized,
            )
        } else {
            val ptsAndFlags = readLong(header, 0)
            val size = readInt(header, 8)
            MediaPacketLimits.checkLength(size, codecId)
            // Grown and reused: a fresh array per frame is meaningful churn at 60fps.
            if (payload.size < size) {
                payload = ByteArray(size.coerceAtLeast(payload.size * 2).coerceAtMost(maxPacketSize))
            }
            dis.readFully(payload, 0, size)
            StreamPacket.Media(
                pts = ptsAndFlags and PTS_MASK,
                isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L,
                isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L,
                data = payload,
                length = size,
            )
        }
    }

    private val header = ByteArray(HEADER_SIZE)
    private var payload = ByteArray(INITIAL_PAYLOAD_SIZE)

    private fun readInt(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun readLong(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private companion object {
        const val HEADER_SIZE = 12
        const val BUFFER_SIZE = 1 shl 16
        const val INITIAL_PAYLOAD_SIZE = 1 shl 16
        const val PACKET_FLAG_CONFIG = 1L shl 62
        const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = (1L shl 61) - 1
    }
}
