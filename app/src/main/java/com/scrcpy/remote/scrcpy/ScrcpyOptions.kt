package com.scrcpy.remote.scrcpy

import kotlin.random.Random

/**
 * Command line options passed to `com.genymobile.scrcpy.Server`.
 *
 * The first argument is always the version of the server being started, which it checks against its
 * own and refuses any other. Remaining arguments are `key=value` pairs; only non-default values need
 * to be sent. The official bundled scrcpy 5.0.1 server accepts these options.
 */
data class ScrcpyOptions(
    val scid: Int = newScid(),
    val logLevel: String = "info",
    val video: Boolean = true,
    val audio: Boolean = true,
    val control: Boolean = true,
    val maxSize: Int = 1600,
    val videoBitRate: Int = 8_000_000,
    val audioBitRate: Int = 128_000,
    val maxFps: Int = 60,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val audioCodec: AudioCodec = AudioCodec.OPUS,
    val audioSource: AudioSource = AudioSource.OUTPUT,
    val audioDup: Boolean = false,
    val stayAwake: Boolean = false,
    val keepActive: Boolean = false,
    val showTouches: Boolean = false,
    val powerOffOnClose: Boolean = false,
    val powerOn: Boolean = true,
    val clipboardAutosync: Boolean = true,
    val displayId: Int = 0,
    /**
     * `new_display`: mirror a freshly created virtual display instead of the device's own screen.
     * Null keeps mirroring the physical display; an empty string means "same size and density".
     */
    val newDisplay: String? = null,
    val flexDisplay: Boolean = false,
    val crop: String? = null,
    val captureOrientation: CaptureOrientation = CaptureOrientation.FOLLOW_DEVICE,
    /**
     * `video_encoder`: a specific MediaCodec encoder on the device, by name.
     *
     * Null lets the device choose. Naming one is the escape hatch for a device whose default
     * hardware encoder is broken, which otherwise makes mirroring fail outright.
     */
    val videoEncoder: String? = null,
    val videoCodecOptions: String? = null,
    /**
     * Capture a camera instead of the screen.
     *
     * Null mirrors the display, which is what this client has always done. Set, it turns the
     * remote phone into a camera and this one into its viewfinder — a different kind of session
     * rather than a different setting, which is why it carries its own group of options rather
     * than scattering nullable camera fields through this one.
     */
    val camera: CameraCapture? = null,
    /**
     * `audio_encoder`: a specific MediaCodec audio encoder on the device, by name.
     *
     * The counterpart to [videoEncoder], and needed for the same reason: a device whose default
     * audio encoder is broken otherwise leaves no option but turning audio off entirely.
     */
    val audioEncoder: String? = null,
    /**
     * The bundled server the session runs. Its version is the first argument, which the server
     * checks against its own and refuses to start on any other.
     */
    val server: ScrcpyServerVersion = ScrcpyServerVersion.DEFAULT,
) {

    /** Abstract unix socket name the server listens on, e.g. `scrcpy_1a2b3c4d`. */
    val socketName: String get() = "scrcpy_%08x".format(scid)

    /** Number of sockets the server will accept, in the order video, audio, control. */
    val socketCount: Int get() = listOf(video, audio, control).count { it }

    fun toServerArgs(): List<String> = buildList {
        require(!flexDisplay || (video && control && newDisplay != null && camera == null && crop.isNullOrBlank())) {
            "A resizable desktop requires video, control, a virtual display, and no camera or crop"
        }
        add(server.version)
        add("scid=%08x".format(scid))
        add("log_level=$logLevel")

        // We connect to the device's listening socket through an ADB stream, which is the
        // "forward" tunnel mode from scrcpy's point of view.
        add("tunnel_forward=true")

        if (!video) add("video=false")
        if (!audio) add("audio=false")
        if (!control) add("control=false")

        if (video) {
            if (videoCodec != VideoCodec.H264) add("video_codec=${videoCodec.serverValue}")
            if (videoBitRate > 0) add("video_bit_rate=$videoBitRate")
            if (maxFps > 0) add("max_fps=$maxFps")
            captureOrientation.serverValue?.let { add("capture_orientation=$it") }
            videoEncoder?.takeIf { it.isNotBlank() }?.let { add("video_encoder=$it") }
            videoCodecOptions?.takeIf { it.isNotBlank() }?.let { add("video_codec_options=$it") }
            if (camera != null) {
                // A different source, not a different display: the server chooses one capture
                // implementation, and a camera one ignores the options that name a screen — the
                // display id, a virtual display, the capture orientation.
                //
                // `max_size` and `crop` are *not* in that group. The camera capture reads both,
                // and an earlier version of this comment claimed the opposite and withheld them,
                // so a saved size limit silently did nothing and the camera streamed at full
                // resolution over whatever link the user was on.
                add("video_source=camera")
                camera.id?.takeIf { it.isNotBlank() }?.let { add("camera_id=$it") }
                camera.facing?.let { add("camera_facing=${it.serverValue}") }
                camera.size?.takeIf { it.isNotBlank() }?.let { add("camera_size=$it") }
                camera.aspectRatio?.takeIf { it.isNotBlank() }?.let { add("camera_ar=$it") }
                if (camera.fps > 0) add("camera_fps=${camera.fps}")
                if (camera.highSpeed) add("camera_high_speed=true")
            }
            if (maxSize > 0) add("max_size=$maxSize")
            crop?.takeIf { it.isNotBlank() }?.let { add("crop=$it") }
        }

        if (audio) {
            if (audioCodec != AudioCodec.OPUS) add("audio_codec=${audioCodec.serverValue}")
            if (audioSource != AudioSource.OUTPUT) add("audio_source=${audioSource.serverValue}")
            // The server only builds a playback-capture mix, so duplication is meaningless for
            // the other sources and would be rejected as an unsupported combination.
            if (audioDup && audioSource == AudioSource.PLAYBACK) add("audio_dup=true")
            if (audioBitRate > 0) add("audio_bit_rate=$audioBitRate")
            audioEncoder?.takeIf { it.isNotBlank() }?.let { add("audio_encoder=$it") }
        }

        // A camera capture ignores every option that names a screen, so sending them would be
        // asking the device to build a display nobody will ever see. Selecting a camera does not
        // clear a saved virtual display — the user may still want it for screen mirroring — so the
        // decision has to be made here, where both are known.
        if (camera == null || !video) {
            if (newDisplay != null) {
                // The server asserts that display_id and new_display are never both set, and forces
                // the display id to "none" itself once a virtual display is requested.
                add("new_display=$newDisplay")
                if (flexDisplay) add("flex_display=true")
                // Without this the device shows the soft keyboard on its own screen instead of the
                // display being mirrored, so it would be invisible here and visible to whoever is
                // holding the device — the opposite of what a virtual display is for.
                add("display_ime_policy=local")
            } else if (displayId != 0) {
                add("display_id=$displayId")
            }
        }
        if (stayAwake) add("stay_awake=true")
        if (keepActive) add("keep_active=true")
        if (showTouches) add("show_touches=true")
        if (powerOffOnClose) add("power_off_on_close=true")
        if (!powerOn) add("power_on=false")
        if (!clipboardAutosync) add("clipboard_autosync=false")
    }

    /** Full `shell:` command that launches the server on the device. */
    fun toShellCommand(serverPath: String): String =
        (listOf("CLASSPATH=${shellQuote(serverPath)}", "app_process", "/", "com.genymobile.scrcpy.Server") +
            toServerArgs().map(::shellQuote))
            .joinToString(" ")

    companion object {
        /** scid is parsed by the server with `Integer.parseInt(value, 16)` and must be >= 0. */
        fun newScid(): Int = Random.nextInt(0, Int.MAX_VALUE) and 0x7FFF_FFFF

        /**
         * Quotes a value for the remote shell.
         *
         * The arguments are assembled into a single `shell:` command line, so any value that ever
         * becomes user supplied (a crop rectangle, codec options) would otherwise be able to run
         * arbitrary commands on the device.
         */
        fun shellQuote(value: String): String =
            if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in SAFE_SHELL_CHARS }) {
                value
            } else {
                "'" + value.replace("'", "'\\''") + "'"
            }

        private const val SAFE_SHELL_CHARS = "=_-./:,+@"
    }
}
