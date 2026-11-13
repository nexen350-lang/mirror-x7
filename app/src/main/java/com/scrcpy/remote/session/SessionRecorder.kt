package com.scrcpy.remote.session

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Writes the session's video, and its sound when [withSound] is set, to an MP4 file.
 *
 * The frames arriving from the device are already encoded, so this copies them into a container
 * rather than encoding anything: recording costs a file write per frame and no extra decoding,
 * and the recording is exactly what the device sent rather than a second-generation copy.
 *
 * Sound is AAC only, the one audio codec Android's MP4 writer accepts on every version this app
 * supports. When it is recorded, [SoundRecordingSync] decides where the file starts and the time
 * of each sample, and a recording that cannot keep its sound never quietly carries on without it:
 * it ends, keeping what it has, and says why.
 *
 * Every method is synchronized because frames arrive on the codec worker, sound on the audio
 * worker, and the user starts and stops from the main thread.
 */
internal class SessionRecorder(
    private val destination: File,
    private val withSound: Boolean = false,
    freeSpace: () -> Long = { (destination.absoluteFile.parentFile ?: destination).usableSpace },
) : SoundRecording {

    private val storage = StorageGuard(freeSpace = freeSpace)

    /**
     * Called once, off the main thread, when the recording stops for a reason of its own.
     *
     * Without it a recording that ended itself — the capture size changed, the disk filled — was
     * known only to this object: the panel went on saying "Recording", the timer went on counting,
     * and the user found out when they pressed Stop and were told their recording had ended some
     * minutes earlier.
     */
    @Volatile
    var onStopped: (() -> Unit)? = null

    /**
     * Called, off the main thread, when the recording needs the device to send a key frame.
     *
     * A recording with sound can only open on a key frame its sound covers, so one that went past
     * before the sound could start it is no use, and waiting for the device's next scheduled key
     * frame would leave the start of the recording to chance.
     */
    @Volatile
    var onKeyFrameNeeded: (() -> Unit)? = null

    private val timeline = RecordingTimeline()
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var format: MediaFormat? = null
    private var parameterSets: ByteArray? = null
    private var frames = 0L
    private var failure: String? = null
    private var done = false
    private var unindexed = false
    private var width = 0
    private var height = 0
    private var lastVideoUs = 0L
    private var audioConfig: ByteArray? = null
    private var audioFormat: MediaFormat? = null
    private var audioTrack = -1
    private var audioSamples = 0L
    private var sync: SoundRecordingSync? = null
    private var keyFrameMissed = false

    /** Why the recording stopped on its own, if it did. */
    val error: String? @Synchronized get() = failure

    /** Whether frames are being written right now. */
    val isRecording: Boolean @Synchronized get() = muxer != null

    /** How many frames have been written, so the caller can refuse to keep an empty file. */
    val frameCount: Long @Synchronized get() = frames

    /** Whether any sound has been written. */
    val hasSound: Boolean @Synchronized get() = audioSamples > 0

    /** How far into the recording the last picture written is. */
    val durationUs: Long @Synchronized get() = sync?.durationUs ?: lastVideoUs

    /**
     * Describes the stream to record.
     *
     * Called whenever capture restarts, which happens on a genuine size change and also on an
     * ordinary restart at the same size — returning from Home, or closing a picker, asks the
     * device to send a fresh key frame and it reports its format again. A restart at the same size
     * describing the same stream is something the recording simply carries on through; a change of
     * dimensions, or of the parameter sets the frames are coded against, ends the file, because one
     * MP4 track describes its stream once, in a header written when the track is created.
     */
    @Synchronized
    fun onFormat(mime: String, width: Int, height: Int, codecConfig: ByteArray?) {
        if (done) return
        val resized = this.width != 0 && this.height != 0 && (this.width != width || this.height != height)
        // A restart arrives as a session packet carrying no configuration, followed by the new
        // one. Only the second of those can say whether the stream itself changed, so a missing
        // configuration is never taken for a change.
        val restated = !resized && codecConfig != null &&
            (parameterSets == null || !codecConfig.contentEquals(parameterSets))
        this.width = width
        this.height = height
        if (muxer != null) {
            if (!resized && !restated) {
                // Carry on with the file already open. The frames after a restart continue the
                // same track, and the next key frame arrives immediately because the restart
                // asked for one.
                return
            }
            stopMuxer()
            fail(if (resized) SIZE_CHANGED else STREAM_CHANGED)
        }
        // A restart carries no configuration of its own; the one already held still describes the
        // stream, so keeping it lets the recording reopen without waiting for the next config.
        val requiresConfig = mime == MediaFormat.MIMETYPE_VIDEO_AVC || mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        // Optional-CSD codecs must reach the muxer, including its unsupported-container error path.
        if (codecConfig != null || !requiresConfig) {
            parameterSets = codecConfig
            format = MediaFormat.createVideoFormat(mime, width, height).apply {
                // H.264 is the one that needs taking apart: a writer builds its header from the two
                // parameter sets given separately, and produces a track it cannot describe when only
                // handed the pair concatenated the way a decoder accepts them.
                val split = if (mime == MediaFormat.MIMETYPE_VIDEO_AVC && codecConfig != null) {
                    ScrcpyCodecs.avcParameterSets(codecConfig)
                } else null
                if (split != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(split.first))
                    setByteBuffer("csd-1", ByteBuffer.wrap(split.second))
                } else if (codecConfig != null) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(codecConfig))
                }
            }
        } else if (resized) {
            // The old configuration described the old size, so it cannot open the next file.
            format = null
            parameterSets = null
        }
        timeline.closed()
        // Pictures held for their sound belong to the stream that just restarted.
        sync?.videoRestarted()
    }

    /**
     * Offers one encoded frame.
     *
     * The file opens on the first key frame after [onFormat], not at whatever frame the user
     * happened to press record on.
     */
    @Synchronized
    fun onSample(data: ByteArray, length: Int, ptsUs: Long, isKeyFrame: Boolean) {
        if (done || failure != null) return
        val description = format ?: return
        if (muxer != null && storage.isLow()) {
            // Stopped while a reserve remains, so the index can still be written and the file kept.
            fail(STORAGE_FULL)
            stopMuxer()
            return
        }
        if (withSound) {
            val alignment = sync
            if (alignment == null) {
                // No sound to start with yet: this frame cannot open the file, so the next one
                // has to be asked for once the sound is described.
                if (isKeyFrame) keyFrameMissed = true
                return
            }
            apply(alignment.onVideo(data, length, ptsUs, isKeyFrame), description)
            return
        }
        if (muxer == null) {
            if (!timeline.canOpenAt(isKeyFrame)) return
            if (!open(description)) return
            timeline.opened(ptsUs)
        }
        val time = timeline.sampleTimeUs(ptsUs) ?: return
        lastVideoUs = time
        val info = MediaCodec.BufferInfo().apply {
            set(0, length, time, if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            muxer?.writeSampleData(track, ByteBuffer.wrap(data, 0, length), info)
            frames++
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a recorded frame", e)
            stopMuxer()
            fail(WRITE_FAILED)
        }
    }

    /**
     * Describes the device's AAC sound, from the configuration packet it sends.
     *
     * Called when the recording is attached, with the configuration the sound stream already
     * sent, and again whenever the stream sends one. A different configuration once the file is
     * open means the sound encoder restarted, and one MP4 track cannot follow that.
     */
    @Synchronized
    override fun onAudioFormat(config: ByteArray) {
        if (!withSound || done || failure != null) return
        val current = audioConfig
        if (current != null && current.contentEquals(config)) return
        if (muxer != null) {
            stopMuxer()
            fail(AUDIO_STOPPED)
            return
        }
        val parsed = ScrcpyCodecs.aacAudioConfig(config)
        if (parsed == null) {
            fail(AUDIO_UNSUPPORTED)
            return
        }
        audioConfig = config.copyOf()
        audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, parsed.sampleRate, parsed.channels).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config.copyOf()))
        }
        val replaced = sync != null
        sync = SoundRecordingSync(SoundRecordingSync.aacFrameUs(parsed.sampleRate))
        if (keyFrameMissed || replaced) {
            keyFrameMissed = false
            onKeyFrameNeeded?.invoke()
        }
    }

    /** Offers one encoded AAC packet, as the device sent it. */
    @Synchronized
    override fun onAudioSample(data: ByteArray, length: Int, ptsUs: Long) {
        if (!withSound || done || failure != null) return
        val alignment = sync ?: return
        val description = format ?: return
        apply(alignment.onAudio(data, length, ptsUs), description)
    }

    /**
     * The sound stream failed.
     *
     * A recording asked for with sound must not go on as a silent one, so it ends here, keeping
     * everything written so far, and says why. Mirroring carries on.
     */
    @Synchronized
    override fun onAudioStopped() {
        if (!withSound || done || failure != null) return
        stopMuxer()
        fail(AUDIO_STOPPED)
    }

    private fun apply(result: SoundRecordingSync.Result, description: MediaFormat) {
        when (result) {
            is SoundRecordingSync.Result.Write -> {
                if (result.open && !open(description)) return
                for (sample in result.samples) {
                    if (!write(sample)) return
                }
            }
            SoundRecordingSync.Result.NeedKeyFrame -> onKeyFrameNeeded?.invoke()
            SoundRecordingSync.Result.GiveUp -> {
                stopMuxer()
                fail(AUDIO_TIMELINE)
            }
            SoundRecordingSync.Result.Wait -> Unit
        }
    }

    private fun write(sample: SoundRecordingSync.Sample): Boolean {
        val video = sample.track == SoundRecordingSync.Track.VIDEO
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.length, sample.timeUs, if (sample.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        return try {
            val target = muxer ?: return false
            target.writeSampleData(if (video) track else audioTrack, ByteBuffer.wrap(sample.data, 0, sample.length), info)
            if (video) frames++ else audioSamples++
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not write a recorded sample", e)
            stopMuxer()
            fail(WRITE_FAILED)
            false
        }
    }

    /**
     * Closes the file and returns it, or null when there is nothing worth keeping.
     *
     * A recording that never opened, opened and wrote nothing, or could not be given its index
     * leaves no file behind: none of those is playable, and offering to save one would only waste
     * the user's time and leave them believing they have a copy of something they do not.
     */
    @Synchronized
    fun finish(): File? {
        // Marked finished before anything else: frames keep arriving on the codec worker, and
        // without this one landing after the file closed would open a second, headless recording.
        done = true
        stopMuxer()
        timeline.closed()
        if (frames == 0L || unindexed) {
            destination.delete()
            return null
        }
        return destination.takeIf { it.isFile && it.length() > 0 }
    }

    /** Stops and deletes whatever was written. */
    @Synchronized
    fun discard() {
        done = true
        stopMuxer()
        timeline.closed()
        destination.delete()
    }

    private fun open(description: MediaFormat): Boolean {
        var created: MediaMuxer? = null
        var addingSound = false
        return try {
            created = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = created.addTrack(description)
            if (withSound) {
                addingSound = true
                audioTrack = created.addTrack(checkNotNull(audioFormat) { "Sound opened without its format" })
                addingSound = false
            }
            created.start()
            muxer = created
            true
        } catch (e: Exception) {
            // An unsupported codec is the usual cause: MP4 carries H.264 and H.265 everywhere, but
            // VP8, VP9 and AV1 depend on the device.
            Log.w(TAG, "Could not start recording", e)
            // Released here because it never became `muxer`, so nothing else will ever close it.
            // `addTrack` and `start` are both able to throw after the muxer itself was created,
            // and a native muxer left to the garbage collector holds its file open meanwhile.
            created?.let { runCatching { it.release() } }
            track = -1
            audioTrack = -1
            fail(when {
                e is IOException -> WRITE_FAILED
                addingSound -> AUDIO_UNSUPPORTED
                else -> UNSUPPORTED_CODEC
            })
            false
        }
    }

    /** Records why the recording ended and tells the session, once. */
    private fun fail(reason: String) {
        if (failure != null) return
        failure = reason
        // Handed over rather than acted on here: this runs on the codec worker, under the lock
        // every other method takes, so anything the session does in response has to happen
        // somewhere it cannot deadlock against the next frame.
        onStopped?.invoke()
    }

    private fun stopMuxer() {
        val current = muxer ?: return
        muxer = null
        track = -1
        audioTrack = -1
        try {
            // Throws when nothing was written, which is not worth reporting: the empty file is
            // deleted either way.
            current.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Recording did not stop cleanly", e)
            // Stopping is where the index is written, and an MP4 without one is not a slightly
            // damaged recording but an unopenable file. When frames did reach the disk this is a
            // real loss, so it is remembered rather than shrugged off: offering the file anyway
            // told the user their recording was saved and left them with something no player will
            // open.
            if (frames > 0L) {
                unindexed = true
                // Named as a failure too. Without this the session saw no reason and no file, and
                // told the user "nothing was recorded" — which is the one thing that was not true:
                // minutes of video reached the disk and only its index did not.
                fail(WRITE_FAILED)
            }
        }
        runCatching { current.release() }
    }

    companion object {
        const val SIZE_CHANGED = "sizeChanged"
        const val STREAM_CHANGED = "streamChanged"
        const val WRITE_FAILED = "writeFailed"
        const val STORAGE_FULL = "storageFull"
        const val UNSUPPORTED_CODEC = "unsupportedCodec"
        const val AUDIO_STOPPED = "audioStopped"
        const val AUDIO_TIMELINE = "audioTimeline"
        const val AUDIO_UNSUPPORTED = "audioUnsupported"
        private const val TAG = "SessionRecorder"
    }
}
