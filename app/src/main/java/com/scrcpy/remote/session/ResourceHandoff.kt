package com.scrcpy.remote.session

/** Owns a resource across a cancellable worker-to-UI handoff; an abandoned result is closed. */
class ResourceHandoff<T : AutoCloseable> : AutoCloseable {
    private var value: T? = null
    private var closed = false

    fun offer(resource: T) {
        val dispose = synchronized(this) {
            check(value == null) { "Resource already offered" }
            if (closed) true else { value = resource; false }
        }
        if (dispose) resource.close()
    }

    @Synchronized
    fun take(): T {
        check(!closed) { "Resource handoff cancelled" }
        return checkNotNull(value) { "Resource not ready" }.also { value = null }
    }

    /** Releases an already-closed resource without forgetting a replacement owner's registration. */
    @Synchronized
    fun release(resource: T) {
        if (value === resource) value = null
    }

    override fun close() {
        val dispose = synchronized(this) {
            closed = true
            value.also { value = null }
        }
        dispose?.close()
    }
}
