package com.scrcpy.remote.session

/** Coalesce only overdue/live frames; synchronized future frames still have distinct display slots. */
internal class VideoOutputBatch(
    private val presentation: (Long) -> VideoPresentation,
    private val release: (Int, VideoPresentation?) -> Unit,
) {
    private var latest = -1

    fun offer(index: Int, ptsUs: Long) {
        require(index >= 0)
        when (val target = presentation(ptsUs)) {
            VideoPresentation.Immediate -> {
                if (latest >= 0) release(latest, null)
                latest = index
            }
            is VideoPresentation.At -> {
                finish()
                release(index, target)
            }
        }
    }

    fun finish() {
        if (latest >= 0) {
            val index = latest
            latest = -1
            release(index, VideoPresentation.Immediate)
        }
    }
}
