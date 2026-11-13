package com.scrcpy.remote.data

import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackSyncMode {
    LOW_LATENCY,
    SYNCHRONIZED,
}
