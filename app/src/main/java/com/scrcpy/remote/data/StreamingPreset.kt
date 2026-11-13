package com.scrcpy.remote.data

import androidx.annotation.StringRes
import com.scrcpy.remote.R

/**
 * Ready-made combinations of the three settings that decide how responsive mirroring feels.
 *
 * Resolution, bit rate and frame rate are already adjustable, but someone whose mirror feels
 * behind their finger has no way of knowing that lowering the resolution helps far more than
 * lowering the frame rate. Each preset moves all three together to a combination that makes
 * sense, and the sliders stay available for anything in between.
 *
 * Latency on a real link is dominated by how many bytes each frame costs: a smaller image is
 * encoded, sent and decoded sooner, so every step down the list trades sharpness for immediacy.
 */
enum class StreamingPreset(
    val maxSize: Int,
    val videoBitRateMbps: Int,
    val maxFps: Int,
    /** Stable name used in a connection link, independent of the enum constant. */
    val token: String,
    @StringRes val labelRes: Int,
) {
    /** Smallest picture, for a busy network or a phone that cannot encode quickly. */
    MOST_RESPONSIVE(960, 4, 60, "responsive", R.string.setting_preset_responsive),

    /** The default: a readable picture that still keeps up on an ordinary network. */
    BALANCED(1600, 8, 60, "balanced", R.string.setting_preset_balanced),

    /** Sharpest picture, at the cost of noticeably more delay on anything but a good link. */
    BEST_PICTURE(2560, 16, 60, "quality", R.string.setting_preset_quality),
    ;

    fun applyTo(settings: MirrorSettings): MirrorSettings =
        settings.copy(maxSize = maxSize, videoBitRateMbps = videoBitRateMbps, maxFps = maxFps)

    companion object {
        /** The preset [settings] currently matches exactly, or null when it sits between them. */
        fun of(settings: MirrorSettings): StreamingPreset? = entries.firstOrNull {
            settings.maxSize == it.maxSize &&
                settings.videoBitRateMbps == it.videoBitRateMbps &&
                settings.maxFps == it.maxFps
        }
    }
}
