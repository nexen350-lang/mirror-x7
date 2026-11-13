package com.scrcpy.remote.ui.screenshot

internal class PixelCopyLifecycle(private val recycle: () -> Unit) {
    private var copyFinished = false
    private var released = false
    private var recycled = false

    fun finishCopy() = update(finished = true)

    fun release() = update(finished = false)

    private fun update(finished: Boolean) {
        val shouldRecycle = synchronized(this) {
            if (finished) copyFinished = true else released = true
            if (copyFinished && released && !recycled) {
                recycled = true
                true
            } else {
                false
            }
        }
        // Cancelling a coroutine cannot cancel PixelCopy's native write into the bitmap.
        if (shouldRecycle) recycle()
    }
}
