package com.scrcpy.remote.session

import java.util.concurrent.atomic.AtomicLong

/** Invalidates callbacks from every earlier connection before teardown or reconnect begins. */
class SessionGeneration {
    private val current = AtomicLong()
    fun next(): Long = current.incrementAndGet()
    fun accepts(token: Long): Boolean = current.get() == token

    /** The token a request started now would be bound to. */
    fun token(): Long = current.get()
}
