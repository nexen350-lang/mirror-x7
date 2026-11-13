package com.scrcpy.remote.session

/**
 * Decides when a recording with sound opens, and the time each sample is written at.
 *
 * Both streams carry the device's own timestamps. They were already running when the user asked
 * for a recording, so the file has to begin somewhere both can begin together:
 *
 * - The picture can only begin on a key frame, so that frame is the recording's origin.
 * - The sound begins with the packet nearest that frame, within half a packet, and that packet
 *   is written at zero like the frame. Android 8's MP4 writer moves every track's first sample to
 *   zero whatever time it was given, so a sound track that started anywhere else would play early
 *   on those phones and on time elsewhere. Snapping keeps the two in step on every version, to
 *   within half a packet (about 11 ms for AAC).
 * - Sound usually arrives before the picture it belongs with, so recent packets are kept while
 *   the file waits for its key frame. When it arrives after, the frame and the pictures behind it
 *   are held for a moment instead.
 * - When the sound only begins after the key frame (it has just started, or skipped), no packet
 *   covers the origin, and a new key frame is asked for rather than starting the sound late.
 *
 * Nothing here blocks: what cannot be written yet is held within fixed limits, and a recording
 * that cannot line its sound up within [giveUpUs] of pictures is given up rather than left waiting.
 *
 * Not thread safe; the recorder serializes every call.
 */
internal class SoundRecordingSync(
    private val audioFrameUs: Long,
    private val holdLimitUs: Long = DEFAULT_HOLD_LIMIT_US,
    private val giveUpUs: Long = DEFAULT_GIVE_UP_US,
    private val prerollUs: Long = DEFAULT_PREROLL_US,
    private val maxHeldBytes: Int = DEFAULT_MAX_HELD_BYTES,
    private val maxPrerollPackets: Int = DEFAULT_MAX_PREROLL_PACKETS,
) {
    init {
        require(audioFrameUs > 0 && holdLimitUs > 0 && giveUpUs > 0 && prerollUs > 0)
        require(maxHeldBytes > 0 && maxPrerollPackets > 0)
    }

    enum class Track { VIDEO, AUDIO }

    /** One sample to write, at [timeUs] on the recording's timeline. */
    class Sample(val track: Track, val data: ByteArray, val length: Int, val timeUs: Long, val keyFrame: Boolean)

    sealed interface Result {
        /** Write [samples] in order; when [open] is true the file has to be started first. */
        data class Write(val open: Boolean, val samples: List<Sample>) : Result

        /** Nothing to write yet. */
        data object Wait : Result

        /** The key frame on offer cannot be lined up with the sound; ask the device for another. */
        data object NeedKeyFrame : Result

        /** The sound could not be lined up with the picture; the recording has to end. */
        data object GiveUp : Result
    }

    private class Kept(val data: ByteArray, val length: Int, val ptsUs: Long, val keyFrame: Boolean)

    private val preroll = ArrayDeque<Kept>()
    private val held = ArrayList<Kept>()
    private var heldBytes = 0
    private var gaveUp = false
    private var firstAttemptUs = UNSET
    private var videoOriginUs = UNSET
    private var audioOriginUs = UNSET
    private var lastVideoUs = UNSET
    private var lastAudioUs = UNSET

    val isOpen: Boolean get() = videoOriginUs != UNSET

    /** The time of the last picture written, which is how long the recording runs. */
    val durationUs: Long get() = if (lastVideoUs == UNSET) 0 else lastVideoUs

    private val halfFrameUs: Long get() = audioFrameUs / 2

    fun onVideo(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean): Result {
        if (gaveUp) return Result.GiveUp
        if (isOpen) return Result.Write(false, listOf(video(data, length, ptsUs, keyFrame)))
        if (held.isNotEmpty()) {
            if (ptsUs - held.first().ptsUs > holdLimitUs || heldBytes + length > maxHeldBytes) {
                drop()
                // A key frame arriving just as the hold runs out can be tried itself.
                return if (keyFrame) attempt(data, length, ptsUs) else Result.NeedKeyFrame
            }
            hold(data, length, ptsUs, keyFrame)
            return Result.Wait
        }
        return if (keyFrame) attempt(data, length, ptsUs) else Result.Wait
    }

    fun onAudio(data: ByteArray, length: Int, ptsUs: Long): Result {
        if (gaveUp) return Result.GiveUp
        if (isOpen) {
            // Older than the packet the sound track started with: it has nowhere to go.
            if (ptsUs < audioOriginUs) return Result.Wait
            return Result.Write(false, listOf(audio(data, length, ptsUs)))
        }
        remember(data, length, ptsUs)
        if (held.isEmpty()) return Result.Wait
        val originUs = held.first().ptsUs
        val start = startFor(originUs) ?: return Result.Wait
        if (start.ptsUs - originUs > halfFrameUs) {
            // The sound begins after the held frame: no packet covers it.
            drop()
            return Result.NeedKeyFrame
        }
        return open(held.toList(), start)
    }

    /** The picture restarted before the file opened, so anything held belongs to the old stream. */
    fun videoRestarted() {
        if (!isOpen) drop()
    }

    private fun attempt(data: ByteArray, length: Int, ptsUs: Long): Result {
        if (firstAttemptUs == UNSET) firstAttemptUs = ptsUs
        val start = startFor(ptsUs)
        // Covered: the frame opens the file however long the wait for it was. The device's own
        // next key frame can be ten seconds away, and giving up on the one that can open the
        // file would leave nothing recorded at all.
        if (start != null && start.ptsUs - ptsUs <= halfFrameUs) {
            // Written straight away, so it needs no copy.
            return open(listOf(Kept(data, length, ptsUs, keyFrame = true)), start)
        }
        if (ptsUs - firstAttemptUs > giveUpUs) {
            gaveUp = true
            preroll.clear()
            return Result.GiveUp
        }
        if (start != null) return Result.NeedKeyFrame
        if (length > maxHeldBytes) return Result.NeedKeyFrame
        hold(data, length, ptsUs, keyFrame = true)
        return Result.Wait
    }

    /** The earliest kept packet that can start the sound for a frame at [originUs]. */
    private fun startFor(originUs: Long): Kept? = preroll.firstOrNull { it.ptsUs >= originUs - halfFrameUs }

    private fun open(frames: List<Kept>, start: Kept): Result {
        videoOriginUs = frames.first().ptsUs
        audioOriginUs = start.ptsUs
        val samples = ArrayList<Sample>(frames.size + preroll.size)
        for (frame in frames) samples += video(frame.data, frame.length, frame.ptsUs, frame.keyFrame)
        for (packet in preroll) {
            if (packet.ptsUs >= audioOriginUs) samples += audio(packet.data, packet.length, packet.ptsUs)
        }
        preroll.clear()
        drop()
        return Result.Write(true, samples)
    }

    /** Buffers from the reader's pool are reused once returned, so anything kept is a copy. */
    private fun hold(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean) {
        held += Kept(data.copyOf(length), length, ptsUs, keyFrame)
        heldBytes += length
    }

    private fun remember(data: ByteArray, length: Int, ptsUs: Long) {
        preroll.addLast(Kept(data.copyOf(length), length, ptsUs, keyFrame = false))
        while (preroll.size > maxPrerollPackets) preroll.removeFirst()
        while (ptsUs - preroll.first().ptsUs > prerollUs) preroll.removeFirst()
    }

    private fun drop() {
        held.clear()
        heldBytes = 0
    }

    private fun video(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean): Sample {
        val time = increasing((ptsUs - videoOriginUs).coerceAtLeast(0), lastVideoUs)
        lastVideoUs = time
        return Sample(Track.VIDEO, data, length, time, keyFrame)
    }

    private fun audio(data: ByteArray, length: Int, ptsUs: Long): Sample {
        val time = increasing(ptsUs - audioOriginUs, lastAudioUs)
        lastAudioUs = time
        return Sample(Track.AUDIO, data, length, time, keyFrame = false)
    }

    /** A muxer requires strictly increasing times; one odd timestamp is nudged, not fatal. */
    private fun increasing(time: Long, last: Long): Long = if (last != UNSET && time <= last) last + 1 else time

    companion object {
        const val DEFAULT_HOLD_LIMIT_US = 500_000L
        const val DEFAULT_GIVE_UP_US = 3_000_000L
        const val DEFAULT_PREROLL_US = 2_000_000L
        const val DEFAULT_MAX_HELD_BYTES = 16 * 1024 * 1024
        const val DEFAULT_MAX_PREROLL_PACKETS = 128
        private const val UNSET = Long.MIN_VALUE

        /** One AAC frame: 1,024 samples per channel. */
        fun aacFrameUs(sampleRate: Int): Long = 1_024L * 1_000_000L / sampleRate
    }
}