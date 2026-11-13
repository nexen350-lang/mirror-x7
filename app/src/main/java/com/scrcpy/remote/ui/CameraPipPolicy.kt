package com.scrcpy.remote.ui

/** Eligibility is checked again at the Activity boundary, not just when drawing the button. */
internal object CameraPipPolicy {
    enum class Blocked { UNAVAILABLE, NOT_CAMERA, NOT_LIVE, BUSY, NOT_FOREGROUND }

    fun blocked(
        supported: Boolean,
        camera: Boolean,
        connected: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        surfaceReady: Boolean,
        busy: Boolean,
        foreground: Boolean,
    ): Blocked? = when {
        !supported -> Blocked.UNAVAILABLE
        !camera -> Blocked.NOT_CAMERA
        !connected || videoWidth <= 0 || videoHeight <= 0 -> Blocked.NOT_LIVE
        busy -> Blocked.BUSY
        !foreground -> Blocked.NOT_FOREGROUND
        !surfaceReady -> Blocked.NOT_LIVE
        else -> null
    }

    data class AspectRatio(val numerator: Int, val denominator: Int)

    /** Android's ordinary PiP range is 1/2.39 through 2.39, including on API 26. */
    fun aspectRatio(width: Int, height: Int): AspectRatio? {
        if (width <= 0 || height <= 0) return null
        // Long cross-products avoid both overflowing unusual sizes and rounding at the limits.
        if (width.toLong() * 100 > height.toLong() * 239) return AspectRatio(239, 100)
        if (width.toLong() * 239 < height.toLong() * 100) return AspectRatio(100, 239)
        var a = width
        var b = height
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return AspectRatio(width / a, height / a)
    }
}
