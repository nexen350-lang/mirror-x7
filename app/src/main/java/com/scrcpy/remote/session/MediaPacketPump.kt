package com.scrcpy.remote.session

import com.scrcpy.remote.scrcpy.MediaInputException
import com.scrcpy.remote.scrcpy.MediaMemoryBudget
import com.scrcpy.remote.scrcpy.MediaPacketLimits
import com.scrcpy.remote.scrcpy.MediaProtocolException
import com.scrcpy.remote.scrcpy.PacketSource
import com.scrcpy.remote.scrcpy.StreamPacket
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The source may block indefinitely between frames. Only the reader blocks on it; the codec
 * worker continues draining output and retains an input packet until the codec accepts it.
 */
class MediaPacketPump(
    private val source: PacketSource,
    /** Receipt time belongs to the owned packet and stays unchanged across input retries. */
    private val consume: (StreamPacket, Long) -> Boolean,
    /** Releases whatever the codec has finished. Returns whether anything came out. */
    private val drain: () -> Boolean,
    private val release: () -> Unit,
    private val onError: (Exception) -> Unit,
    private val name: String,
    private val inputTimeoutMillis: Long = 5_000,
    maxOwnedBytes: Int = MediaPacketLimits.ownedBytes(source.codecId),
    maxQueuedPackets: Int = 4,
) : Closeable {
    private sealed interface Item {
        class Packet(
            val value: StreamPacket,
            val receivedNanos: Long,
            private val reservation: MediaMemoryBudget.Reservation?,
            private val pool: PayloadBufferPool?,
            private val pooled: ByteArray?,
        ) : Item, Closeable {
            private var released = false

            /**
             * Idempotent on purpose. Releasing twice would hand the same buffer back to the pool
             * twice, and it would then be lent to two packets at once.
             */
            @Synchronized
            override fun close() {
                if (released) return
                released = true
                reservation?.close()
                if (pooled != null) pool?.recycle(pooled)
            }
        }
        data class Failed(val error: Exception) : Item
    }

    init { require(inputTimeoutMillis > 0 && maxQueuedPackets in 1..64) }

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var closed = false
    private val budget = MediaMemoryBudget(maxOwnedBytes)
    // Sized against the same limit as ownership: the pool is there to stop per-packet churn, not
    // to become a second copy of the budget.
    private val pool = PayloadBufferPool(maxOwnedBytes)
    private val queue = ArrayBlockingQueue<Item>(maxQueuedPackets)
    private val reader = Thread(::read, "$name-reader").apply { isDaemon = true }
    private val worker = Thread(::decode, "$name-decoder").apply { isDaemon = true }

    val ownedBytes: Int get() = budget.usedBytes
    val peakOwnedBytes: Int get() = budget.peakBytes

    fun start() {
        synchronized(lifecycleLock) {
            check(!closed && started.compareAndSet(false, true)) { "A media pump cannot be restarted" }
            running.set(true)
            worker.start()
            reader.start()
        }
    }

    private fun read() {
        raisePriority()
        try {
            while (running.get()) {
                val packet = source.readPacket()
                val receivedNanos = System.nanoTime()
                if (!running.get()) break
                val owned = own(packet, receivedNanos)
                var transferred = false
                try {
                    transferred = enqueue(owned)
                } finally {
                    if (!transferred) owned.close()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stop()
        } catch (e: Exception) {
            if (running.get()) {
                try {
                    enqueue(Item.Failed(e))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stop()
                }
            }
        } catch (error: Throwable) {
            val wasRunning = running.getAndSet(false)
            stop()
            if (wasRunning) onError(error as? Exception ?: Exception(error))
            if (error is OutOfMemoryError) throw error
        }
    }

    private fun own(packet: StreamPacket, receivedNanos: Long): Item.Packet {
        if (packet !is StreamPacket.Media) return Item.Packet(packet, receivedNanos, null, null, null)
        MediaPacketLimits.checkLength(packet.length, source.codecId)
        if (packet.length > packet.data.size || packet.length > budget.maxBytes) {
            throw MediaProtocolException("Media payload exceeds its buffer or ownership budget")
        }
        // Reserve before copying the reader's reusable array, not after enqueueing the copy.
        // The budget charges the payload, not the buffer: it is a backpressure limit on how much
        // stream may be in flight, and tying it to the allocator's block size would make a small
        // budget unusable. Rounding is deliberately kept to one block so the two stay close.
        val reservation = budget.reserve(packet.length)
        val buffer = try {
            pool.acquire(packet.length)
        } catch (error: Throwable) {
            reservation.close()
            throw error
        }
        try {
            // A pooled buffer may be longer than the packet; everything downstream reads
            // `length`, never `data.size`.
            System.arraycopy(packet.data, 0, buffer, 0, packet.length)
            return Item.Packet(packet.copy(data = buffer), receivedNanos, reservation, pool, buffer)
        } catch (error: Throwable) {
            reservation.close()
            pool.recycle(buffer)
            throw error
        }
    }

    private fun enqueue(item: Item): Boolean {
        while (running.get()) {
            if (queue.offer(item, 10, TimeUnit.MILLISECONDS)) {
                // Shutdown may have drained the queue immediately before this offer completed.
                if (!running.get() && queue.remove(item)) (item as? Item.Packet)?.close()
                return true
            }
        }
        return false
    }

    private fun decode() {
        raisePriority()
        var pending: Item.Packet? = null
        var pendingSince = 0L
        var quietRounds = 0
        try {
            while (running.get()) {
                val produced = drain()
                // A round that handed back a frame is not a quiet one, and the wait below is
                // chosen from this count. Resetting only afterwards meant the first frame out of
                // a burst still paid the full idle wait before the next drain could run — the
                // very delay the backoff was written to avoid.
                if (produced) quietRounds = 0
                if (pending == null) {
                    when (val item = queue.poll(
                        PumpPollPolicy.timeoutMillis(quietRounds), TimeUnit.MILLISECONDS)
                    ) {
                        is Item.Packet -> {
                            pending = item
                            pendingSince = System.nanoTime()
                            quietRounds = 0
                        }
                        is Item.Failed -> throw item.error
                        null -> {
                            // Only genuinely idle rounds count. Backing off while the codec is
                            // still handing back frames would add the idle wait to the last
                            // frames of every burst, which is exactly when they are wanted.
                            quietRounds = if (produced) 0 else quietRounds + 1
                            continue
                        }
                    }
                }
                if (!running.get()) break
                val input = checkNotNull(pending)
                if (consume(input.value, input.receivedNanos)) {
                    input.close()
                    pending = null
                } else {
                    if (System.nanoTime() - pendingSince >
                        TimeUnit.MILLISECONDS.toNanos(inputTimeoutMillis)
                    ) throw MediaInputException("Decoder made no input progress for $inputTimeoutMillis ms")
                    Thread.sleep(2)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            if (running.getAndSet(false)) {
                stop()
                onError(e as? Exception ?: Exception(e))
            }
            if (e is OutOfMemoryError) throw e
        } finally {
            pending?.close()
            stop()
            release()
        }
    }

    private fun stop() {
        running.set(false)
        budget.close()
        if (Thread.currentThread() !== reader) reader.interrupt()
        if (Thread.currentThread() !== worker) worker.interrupt()
        while (true) {
            val item = queue.poll() ?: break
            (item as? Item.Packet)?.close()
        }
        // Closing the queued items recycles their buffers into the pool; this is what lets the
        // pool go with the stream rather than outliving it.
        pool.clear()
    }

    /** Source/ADB closure is owned by the session, not a UI callback. */
    override fun close() {
        synchronized(lifecycleLock) {
            closed = true
            stop()
        }
    }

    fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        for (thread in listOf(reader, worker)) {
            if (Thread.currentThread() === thread) continue
            val remaining = deadline - System.nanoTime()
            if (remaining > 0) thread.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
        }
        return !reader.isAlive && !worker.isAlive
    }
}
