package com.scrcpy.remote.session

import com.scrcpy.remote.scrcpy.ControlMessage

data class SessionInputState(val locked: Boolean = false, val generation: Long = 0)

/** Local interaction safety, not an ADB permission boundary. Methods are synchronized: the live typist (IO) and the main thread both reach it. */
internal class SessionInputGate(
    initiallyLocked: Boolean = false,
    private val transmit: (ControlMessage) -> Boolean,
) {
    var state = SessionInputState(locked = initiallyLocked)
        private set
    private val keys = linkedSetOf<Int>()
    private val pointers = linkedMapOf<Long, ControlMessage.InjectTouchEvent>()
    private val pointerHistory = linkedMapOf<Long, ControlMessage.InjectTouchEvent>()

    @Synchronized
    fun setLocked(locked: Boolean, width: Int, height: Int) {
        if (state.locked == locked) return
        state = SessionInputState(locked, state.generation + 1)
        if (locked) {
            releaseKeys()
            releasePointers(width, height)
        }
    }

    @Synchronized
    fun blocks(message: ControlMessage): Boolean = state.locked && !allowedWhileLocked(message)

    @Synchronized
    fun send(message: ControlMessage, generation: Long? = null): Boolean {
        if (generation != null && generation != state.generation || blocks(message)) return false
        when (message) {
            is ControlMessage.InjectKeycode -> {
                if (message.action !in 0..1) return false
                if ((message.action == 1 || message.repeat > 0) && message.keycode !in keys) return false
            }
            is ControlMessage.InjectTouchEvent -> {
                if (message.action !in 0..2) return false
                if (message.action != 0 && message.pointerId !in pointers) return false
            }
            else -> Unit
        }
        if (!transmit(message)) return false
        when (message) {
            is ControlMessage.InjectKeycode ->
                if (message.action == 0) keys += message.keycode else keys -= message.keycode
            is ControlMessage.InjectTouchEvent -> {
                pointerHistory[message.pointerId] = message
                if (message.action == 1) pointers.remove(message.pointerId)
                else pointers[message.pointerId] = message
            }
            else -> Unit
        }
        return true
    }

    @Synchronized
    fun releaseKeys() {
        val pending = keys.toList()
        keys.clear()
        pending.forEach { transmit(ControlMessage.InjectKeycode(1, it)) }
    }

    /**
     * Lifts every pointer the device may still believe is down, as touches at [width] x [height]:
     * the size the server will accept. Each is lifted at its own place on the picture, scaled from
     * the size it was pressed at, so a rotated or rescaled capture lifts it over the same part of
     * the content — where the precision pad keeps its cursor too — rather than pinned to an edge.
     */
    @Synchronized
    fun releasePointers(width: Int, height: Int, includeHistory: Boolean = false) {
        val pending = (if (includeHistory) pointerHistory else pointers).toMap()
        // No usable size (e.g. locked while not connected): keep the pointers so a later release
        // with a real size can still lift them. clear() drops them on detach.
        if (width <= 0 || height <= 0) return
        // Sending may synchronously fail and re-enter session cleanup.
        pointers.clear()
        for ((id, point) in pending) {
            transmit(ControlMessage.InjectTouchEvent(1, id, rescale(point.x, point.screenWidth, width),
                rescale(point.y, point.screenHeight, height), width, height, 0f))
        }
    }

    /** The pixel whose centre lands where [position]'s did, from a [from]- to a [to]-pixel span. */
    private fun rescale(position: Int, from: Int, to: Int): Int =
        (if (from > 0 && from != to) ((position + 0.5) * to / from).toInt() else position).coerceIn(0, to - 1)

    @Synchronized
    fun clear() {
        keys.clear()
        pointers.clear()
        pointerHistory.clear()
    }

    companion object {
        fun allowedWhileLocked(message: ControlMessage): Boolean = when (message) {
            is ControlMessage.GetClipboard -> message.copyKey == ControlMessage.COPY_KEY_NONE
            is ControlMessage.SetClipboard -> !message.paste
            is ControlMessage.ResizeDisplay, is ControlMessage.ScanFile -> true
            is ControlMessage.Empty -> message.type == ControlMessage.TYPE_RESET_VIDEO
            is ControlMessage.InjectKeycode, is ControlMessage.InjectText,
            is ControlMessage.InjectTouchEvent, is ControlMessage.InjectScrollEvent,
            is ControlMessage.BackOrScreenOn, is ControlMessage.SetDisplayPower,
            is ControlMessage.StartApp, is ControlMessage.CameraSetTorch -> false
        }
    }
}
