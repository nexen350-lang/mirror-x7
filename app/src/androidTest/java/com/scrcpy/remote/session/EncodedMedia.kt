package com.scrcpy.remote.session

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real encoded H.264 and AAC, made on the device, for recordings that have to open again. */
internal object EncodedMedia {
    class Frame(val data: ByteArray, val ptsUs: Long, val keyFrame: Boolean)
    class Stream(val config: ByteArray, val frames: List<Frame>)

    const val WIDTH = 176
    const val HEIGHT = 144

    /**
     * [count] H.264 frames 1/15 s apart with a key frame every second; [bright] frames are white
     * and the rest black, so a decoder can tell which is which.
     */
    fun h264(count: Int = 16, bright: Set<Int> = emptySet()): Stream {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val ySize = WIDTH * HEIGHT
        return encode(MediaFormat.MIMETYPE_VIDEO_AVC, format, count) { index, buffer ->
            val luma: Byte = if (index in bright) 235.toByte() else 16
            buffer.put(ByteArray(ySize) { luma })
            buffer.put(ByteArray(ySize / 2) { 128.toByte() })
            index * 66_666L
        }
    }

    /**
     * [seconds] of 48 kHz stereo AAC-LC; [beepAtUs] starts a loud 1 kHz tone lasting 100 ms there
     * in otherwise silent sound.
     */
    fun aac(seconds: Double, beepAtUs: Long? = null): Stream {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        val total = (48_000 * seconds).toInt()
        val beepStart = beepAtUs?.let { (it * 48_000 / 1_000_000).toInt() } ?: -1
        val beepEnd = beepStart + 4_800
        var fed = 0
        return encode(MediaFormat.MIMETYPE_AUDIO_AAC, format, (total + 1_023) / 1_024) { _, buffer ->
            val pts = fed * 1_000_000L / 48_000
            val frames = minOf(1_024, total - fed)
            val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { i ->
                val n = fed + i
                val sample = if (n in beepStart until beepEnd) {
                    (Math.sin(2 * Math.PI * 1_000 * n / 48_000.0) * 20_000).toInt().toShort()
                } else 0
                pcm.putShort(sample); pcm.putShort(sample)
            }
            buffer.put(pcm.array())
            fed += frames
            pts
        }
    }

    private fun encode(
        mime: String,
        format: MediaFormat,
        inputs: Int,
        fill: (Int, ByteBuffer) -> Long,
    ): Stream {
        assumeTrue("No $mime encoder", MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } })
        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var config: ByteArray? = null
        val frames = mutableListOf<Frame>()
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var lastPts = 0L
        var inputEnded = false
        val deadline = SystemClock.elapsedRealtime() + 20_000
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        if (fed < inputs) {
                            lastPts = fill(fed, buffer)
                            codec.queueInputBuffer(index, 0, buffer.position(), lastPts, 0)
                            fed++
                        } else {
                            codec.queueInputBuffer(index, 0, 0, lastPts + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out < 0) continue
                val buffer = codec.getOutputBuffer(out)!!
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = (config ?: byteArrayOf()) + bytes
                else if (info.size > 0) frames += Frame(bytes, info.presentationTimeUs,
                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                codec.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        check(frames.isNotEmpty()) { "$mime encoder produced nothing" }
        return Stream(checkNotNull(config) { "$mime encoder produced no configuration" }, frames)
    }
}
