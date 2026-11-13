package com.scrcpy.remote.session

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class VideoFrameStats(
    val receivedFps: Int = 0,
    val decodedFps: Int = 0,
    val surfaceFps: Int = 0,
    val receivedFrames: Long = 0,
    val decodedFrames: Long = 0,
    val presentedFrames: Long = 0,
    val skippedFrames: Long = 0,
    val surfaceFrames: Long = 0,
    /** Video received over the last second, in kilobits per second. */
    val receivedKbps: Int = 0,
)

internal class VideoFrameMeter(private val now: () -> Long = System::nanoTime) {
    private val received = FrameWindow()
    private val decoded = FrameWindow()
    private val surface = FrameWindow()
    private val presented = AtomicLong()
    private val skipped = AtomicLong()

    private val bytes = ArrayDeque<Pair<Long, Int>>()
    private var bytesInWindow = 0L

    fun received(size: Int = 0) {
        val at = now()
        received.record(at)
        if (size > 0) synchronized(bytes) {
            bytes.addLast(at to size); bytesInWindow += size
            trimBytes(at)
        }
    }

    private fun trimBytes(at: Long) {
        while (bytes.isNotEmpty() && at - bytes.peekFirst()!!.first > 1_000_000_000L) {
            bytesInWindow -= bytes.removeFirst().second
        }
    }

    private fun kbps(at: Long): Int = synchronized(bytes) { trimBytes(at); (bytesInWindow * 8 / 1000).toInt() }
    fun decoded() = decoded.record(now())
    fun presented() { presented.incrementAndGet() }
    fun skipped() { skipped.incrementAndGet() }
    fun rendered(nanoTime: Long) = surface.record(nanoTime)

    fun snapshot(): VideoFrameStats {
        val timestamp = now()
        val incoming = received.snapshot(timestamp)
        val output = decoded.snapshot(timestamp)
        val rendered = surface.snapshot(timestamp)
        return VideoFrameStats(incoming.first, output.first, rendered.first,
            incoming.second, output.second, presented.get(), skipped.get(), rendered.second, kbps(timestamp))
    }

    private class FrameWindow {
        private val times = ArrayDeque<Long>()
        private var total = 0L

        @Synchronized fun record(time: Long) {
            total++
            prune(time)
            // Keep diagnostic memory bounded even when a network backlog arrives in one burst.
            if (times.size == 512) times.removeFirst()
            times.addLast(time)
        }

        @Synchronized fun snapshot(time: Long): Pair<Int, Long> {
            prune(time)
            return times.count { it <= time } to total
        }

        private fun prune(time: Long) {
            while (times.isNotEmpty() && time - times.first >= 1_000_000_000L) times.removeFirst()
        }
    }
}
