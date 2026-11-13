package com.scrcpy.remote.session

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface PcmOutput : Closeable {
    fun play()
    fun setMuted(muted: Boolean)
    fun write(data: ByteArray, offset: Int, length: Int): Int
    fun presentation(): PcmPresentation? = null
    fun reset() {}

    /**
     * Releases a writer blocked inside [write], from another thread.
     *
     * Blocking writes are not interruptible, so without this a stalled output would strand the
     * playback worker and leak both it and the native resources it owns.
     */
    fun unblockWriter() {}
}

internal data class PcmPlaybackStats(val queuedFrames: Int, val writtenFrames: Long, val droppedFrames: Long)

/** The decoder never waits for AudioTrack. Only this worker owns output operations and release. */
internal class PcmPlayback(
    private val sampleRate: Int,
    private val frameSize: Int,
    private val targetMillis: Int,
    private val outputFactory: () -> PcmOutput,
    private val muted: () -> Boolean,
    private val onPlaying: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val clock: AudioPresentationClock? = null,
    private val transition: PcmTransition? = null,
    codecBlockFrames: Int = 0,
) : Closeable {
    init {
        require(sampleRate in 1..384_000 && frameSize in 1..8 && targetMillis in 1..1_000)
        require(codecBlockFrames in 0..0xffff) { "PCM block exceeds FLAC's unsigned 16-bit frame bound" }
    }

    // A normal codec block must fit even while its predecessor is partly consumed. A real
    // backlog still catches up, but retains at least one complete block rather than its tail.
    private val buffer = PcmPlaybackBuffer(
        frameSize,
        maxOf(sampleRate * targetMillis / 1_000, codecBlockFrames, 1),
        maxOf(sampleRate * (targetMillis + 60) / 1_000, codecBlockFrames * 2, 1),
        sampleRate,
    )
    private val running = AtomicBoolean(true)
    private val started = AtomicBoolean(false)
    private val writtenFrames = AtomicLong()
    private val resetRevision = AtomicLong()
    private val chunk = ByteArray((sampleRate / 100).coerceAtLeast(1) * frameSize)
    private val thread = Thread(::run, "scrcpy-audio-playback").apply { isDaemon = true }

    /** Published so [close] can release a worker blocked in a write it cannot interrupt. */
    @Volatile
    private var activeOutput: PcmOutput? = null

    fun start() {
        check(started.compareAndSet(false, true)) { "PCM playback cannot be restarted" }
        thread.start()
    }

    fun offer(data: ByteArray, length: Int, ptsUs: Long? = null): Boolean = buffer.offer(data, length, ptsUs)

    fun resetTimeline() {
        buffer.discardQueued()
        clock?.reset()
        resetRevision.incrementAndGet()
    }

    fun stats() = PcmPlaybackStats(buffer.queuedFrames, writtenFrames.get(), buffer.droppedFrames)

    private fun run() {
        raisePriority()
        var output: PcmOutput? = null
        try {
            if (!running.get()) return
            val sink = outputFactory()
            output = sink
            activeOutput = sink
            var appliedMute = muted()
            sink.setMuted(appliedMute)
            sink.play()
            var playing = false
            var outputFrames = 0L
            var appliedReset = resetRevision.get()
            var underruns = sink.presentation()?.underruns ?: 0
            var lastRecovery = 0L
            var recoveryTransition = false
            var outputRouteId: Int? = null
            while (running.get()) {
                val reset = resetRevision.get()
                if (reset != appliedReset) {
                    sink.reset()
                    clock?.reset()
                    outputFrames = 0
                    transition?.reset()
                    appliedReset = reset
                    underruns = sink.presentation()?.underruns ?: 0
                }
                val nextMute = muted()
                if (nextMute != appliedMute) {
                    sink.setMuted(nextMute)
                    appliedMute = nextMute
                }
                val position = sink.presentation()
                val routeId = position?.outputRouteId
                val routeChanged = routeId != null && outputRouteId != null && routeId != outputRouteId
                if (routeId != null) outputRouteId = routeId
                if (routeChanged) {
                    // Switching between already-connected outputs need not emit a device-added event.
                    buffer.discardQueued()
                    sink.reset()
                    clock?.reset()
                    outputFrames = 0
                    transition?.reset()
                    underruns = sink.presentation()?.underruns ?: 0
                    continue
                }
                val now = System.nanoTime()
                clock?.observe(position, stats(), now)
                val recover = playing && position != null && position.underruns > underruns &&
                    now - lastRecovery > 2_000_000_000L
                underruns = position?.underruns ?: underruns
                if (recover) {
                    lastRecovery = now
                    recoveryTransition = true
                }
                val packet = buffer.pollChunk(
                    chunk,
                    if (recover) (targetMillis + 20).toLong() else 20,
                    if (recover) (sampleRate * targetMillis / 1_000).coerceAtLeast(1) else 1,
                )
                val count = packet.length
                if (count == 0) continue
                transition?.apply(chunk, count, packet.discontinuity || recoveryTransition)
                recoveryTransition = false
                PcmWriter.writeFully(chunk, count, running::get) { bytes, offset, length ->
                    sink.write(bytes, offset, length).also { written ->
                        if (written > 0 && written <= length) {
                            if (written % frameSize != 0) throw IOException("Unaligned PCM output write: $written")
                            clock?.record(outputFrames, written / frameSize,
                                packet.ptsUs?.plus(offset / frameSize * 1_000_000L / sampleRate), sampleRate)
                            outputFrames += written / frameSize
                            writtenFrames.addAndGet((written / frameSize).toLong())
                        }
                        clock?.observe(sink.presentation(), stats(), System.nanoTime())
                    }
                }
                if (!playing && running.get()) {
                    playing = true
                    onPlaying()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IOException) {
            fail(e)
        } catch (e: IllegalStateException) {
            fail(e)
        } catch (e: IllegalArgumentException) {
            fail(e)
        } catch (e: UnsupportedOperationException) {
            fail(e)
        } catch (e: SecurityException) {
            fail(e)
        } catch (e: RuntimeException) {
            fail(e)
        } finally {
            activeOutput = null
            buffer.close()
            clock?.reset()
            try {
                output?.close()
            } catch (e: IOException) {
                fail(e)
            } catch (e: IllegalStateException) {
                fail(e)
            } finally {
                running.set(false)
            }
        }
    }

    private fun fail(error: Exception) {
        if (running.getAndSet(false)) onError(error)
    }

    override fun close() {
        running.set(false)
        buffer.close()
        // A blocking write is a native call that ignores interrupts, so the worker has to be
        // released through the output itself or it never reaches its own cleanup.
        activeOutput?.unblockWriter()
        thread.interrupt()
    }

    fun awaitStopped(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        if (Thread.currentThread() !== thread) thread.join(timeoutMillis)
        return !thread.isAlive
    }
}
