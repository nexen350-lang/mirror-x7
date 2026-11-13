package com.scrcpy.remote.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Accepted writes belong to the application, not an Activity or its awaiting coroutine. */
internal object PreferenceWriter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<() -> Unit>(Channel.UNLIMITED)
    private val lock = Any()
    private var pending = 0
    private val _writing = MutableStateFlow(false)
    val writing = _writing.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                try {
                    request()
                } finally {
                    synchronized(lock) {
                        pending--
                        _writing.value = pending != 0
                    }
                }
            }
        }
    }

    /**
     * Runs [operation] on the writer thread without a caller to await it.
     *
     * For work that must not sit on the startup path but still has to stay ordered with every
     * other write, such as upgrading a stored schema.
     */
    fun enqueue(operation: () -> Unit) {
        synchronized(lock) {
            pending++
            _writing.value = true
            check(requests.trySend {
                // Swallowed for the same reason submit() catches Throwable: this is the single
                // coroutine serving every write, and letting anything escape would hang the rest.
                runCatching(operation)
            }.isSuccess)
        }
    }

    suspend fun <T> submit(operation: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(lock) {
            pending++
            _writing.value = true
            check(requests.trySend {
                try {
                    result.complete(operation())
                } catch (error: Throwable) {
                    // Throwable, not Exception: an Error here — an OutOfMemoryError while
                    // encoding a large snapshot is the realistic one — would otherwise leave the
                    // deferred uncompleted, so the caller awaits forever and its screen stays
                    // busy with no way out.
                    //
                    // Deliberately not rethrown. This runs on the one coroutine that serves every
                    // write, so letting anything escape would end that coroutine and hang every
                    // later write instead of just this one.
                    result.completeExceptionally(error)
                }
            }.isSuccess)
        }
        return result.await()
    }
}
