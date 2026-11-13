package com.scrcpy.remote.session

import com.scrcpy.remote.data.VirtualDisplaySpec
import kotlin.math.max
import kotlin.math.min

internal data class DesktopSize(val width: Int, val height: Int) {
    init {
        require(width in VirtualDisplaySpec.SIZE_RANGE && height in VirtualDisplaySpec.SIZE_RANGE)
        require(width % ALIGNMENT == 0 && height % ALIGNMENT == 0)
    }

    companion object {
        private const val ALIGNMENT = 8

        fun fit(width: Int, height: Int, maxSize: Int): DesktopSize {
            require(width > 0 && height > 0)
            require(maxSize in VirtualDisplaySpec.SIZE_RANGE)
            val scale = min(1.0, maxSize.toDouble() / max(width, height))
            fun side(pixels: Int): Int =
                max(VirtualDisplaySpec.SIZE_RANGE.first, (pixels * scale).toInt() / ALIGNMENT * ALIGNMENT)
            return DesktopSize(side(width), side(height))
        }
    }
}

internal data class DesktopViewport(val width: Int, val height: Int, val densityDpi: Int) {
    init {
        require(width > 0 && height > 0 && densityDpi > 0)
    }

    fun display(maxSize: Int): SessionDisplay.Desktop {
        val size = DesktopSize.fit(width, height, maxSize)
        val scale = max(size.width, size.height).toDouble() / max(width, height)
        val density = (densityDpi * scale).toInt().coerceIn(VirtualDisplaySpec.DPI_RANGE)
        return SessionDisplay.Desktop(size, density)
    }
}

internal sealed interface SessionDisplay {
    data class Physical(val id: Int) : SessionDisplay {
        init { require(id in 0..4096) }
    }

    data class Desktop(val size: DesktopSize, val densityDpi: Int) : SessionDisplay {
        init { require(densityDpi in VirtualDisplaySpec.DPI_RANGE) }
        val spec: String get() = "${size.width}x${size.height}/$densityDpi"
    }
}

internal fun canChooseSessionDisplay(id: Int, virtualDisplayActive: Boolean): Boolean =
    id in 0..4096 && (!virtualDisplayActive || id == 0)

/**
 * One resize may be in flight; later window sizes replace the desired size, not the request.
 * Only video metadata acknowledges a resize. A flushed control message is not an acknowledgement.
 */
internal class DesktopResizeState {
    var active: Boolean = false
        private set
    var pending: DesktopSize? = null
        private set
    private var ready = false
    private var desired: DesktopSize? = null
    private var applied: DesktopSize? = null
    private var actualWidth = 0
    private var actualHeight = 0

    val canRecord: Boolean
        get() = !active || (ready && pending == null && desired == applied)

    fun start(initial: DesktopSize) {
        stop()
        active = true
        desired = initial
        applied = initial
    }

    fun stop() {
        active = false
        ready = false
        pending = null
        desired = null
        applied = null
        actualWidth = 0
        actualHeight = 0
    }

    fun updateViewport(size: DesktopSize) {
        if (active) desired = size
    }

    fun onCapture(width: Int, height: Int, clientResized: Boolean) {
        if (!active) return
        require(width > 0 && height > 0)
        actualWidth = width
        actualHeight = height
        if (!ready) {
            ready = true
        } else if (pending != null && (clientResized ||
                (pending?.width == width && pending?.height == height))) {
            // A simultaneous device-property reset may suppress the clientResized flag.
            applied = pending
            pending = null
        }
    }

    fun nextRequest(recording: Boolean, visible: Boolean): DesktopSize? {
        if (!active || !ready || recording || !visible || pending != null) return null
        val size = desired ?: return null
        if (size == applied) return null
        if (size.width == actualWidth && size.height == actualHeight) {
            applied = size
            return null
        }
        pending = size
        return size
    }
}
