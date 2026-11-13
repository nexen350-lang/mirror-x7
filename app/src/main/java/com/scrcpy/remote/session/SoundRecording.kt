package com.scrcpy.remote.session

/** Where the encoded sound of a recording goes; implemented by [SessionRecorder]. */
internal interface SoundRecording {
    /** The device's AAC configuration, given before any packet coded against it. */
    fun onAudioFormat(config: ByteArray)

    /** One packet as the device sent it; [data] is only valid during the call. */
    fun onAudioSample(data: ByteArray, length: Int, ptsUs: Long)

    /** The sound stream failed: a recording that was to have sound cannot carry on with it. */
    fun onAudioStopped()
}
