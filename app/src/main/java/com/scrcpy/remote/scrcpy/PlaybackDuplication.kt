package com.scrcpy.remote.scrcpy

/** Android 13 refuses playback duplication; hear sound here instead, or use Android 14+. */
internal object PlaybackDuplication {
    const val ANDROID_13 = 33

    /** Whether starting with [options] asks for that capture: the one case the version is read for. */
    fun needsTargetSdk(options: ScrcpyOptions): Boolean =
        options.audio && options.audioSource == AudioSource.PLAYBACK && options.audioDup

    /**
     * Whether a device on [targetSdk] refuses the sound asked for with [audioSource] and [audioDup]
     * with the bundled server.
     */
    fun refused(
        targetSdk: Int?,
        audioSource: AudioSource,
        audioDup: Boolean,
    ): Boolean =
        audioSource == AudioSource.PLAYBACK && audioDup && targetSdk == ANDROID_13

    /** The API level `getprop ro.build.version.sdk` printed, or null for anything else. */
    fun parseSdk(output: String): Int? = output.trim().toIntOrNull()?.takeIf { it > 0 }
}