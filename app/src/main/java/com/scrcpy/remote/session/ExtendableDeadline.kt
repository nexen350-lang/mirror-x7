package com.scrcpy.remote.session

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * A timeout whose end can be moved later while [run] is waiting, from any thread.
 *
 * `withTimeout` fixes its deadline when it starts. Connecting needs one that grows the moment the
 * other device asks its user for permission, because from then on the wait is a person's.
 */
class ExtendableDeadline(initialMillis: Long, private val clock: () -> Long = System::nanoTime) {
    class Expired : CancellationException("Deadline expired")

    private val endNanos = AtomicLong(clock() + initialMillis * 1_000_000)

    /** Moves the end [millis] later than now, or leaves it where it is if that is later still. */
    fun extendBy(millis: Long) {
        val proposed = clock() + millis * 1_000_000
        endNanos.accumulateAndGet(proposed) { current, next -> maxOf(current, next) }
    }

    fun remainingMillis(): Long = ((endNanos.get() - clock()) / 1_000_000).coerceAtLeast(0)

    /** Runs [block], cancelling it with [Expired] if the (possibly extended) end passes first. */
    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val work = async { block() }
        val watchdog = launch {
            while (true) {
                val remaining = remainingMillis()
                if (remaining <= 0) break
                delay(remaining.coerceAtMost(250))
            }
            expired.set(true)
            work.cancel(Expired())
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // Awaiting a cancelled child may rethrow a wrapper; report our own expiry plainly.
            if (expired.get()) throw Expired() else throw e
        } finally {
            watchdog.cancel()
        }
    }
}
