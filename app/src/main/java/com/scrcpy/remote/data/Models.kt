package com.scrcpy.remote.data

import com.scrcpy.remote.adb.EndpointHost
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.ScrcpyOptions
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import com.scrcpy.remote.scrcpy.VideoCodec
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 5555,
    val isFavorite: Boolean = false,
    val portraitCalibration: TouchCalibration? = null,
    val landscapeCalibration: TouchCalibration? = null,
    val mirroringSettings: DeviceMirroringSettings? = null,
    /** A palette colour the user chose for the device's avatar, or null for the automatic one. */
    val avatarColor: Int? = null,
    /** What the avatar shows in place of the device's initials, or null for the initials. */
    val avatarIcon: DeviceIcon? = null,
) {
    val address: String get() = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
}

/** A kind of device an avatar can show instead of initials. */
@Serializable
enum class DeviceIcon { PHONE, TABLET, TV, WATCH }

@Serializable
data class TouchCalibration(val xDp: Int = 0, val yDp: Int = 0)

/**
 * The form of [host] used to decide whether two endpoints are the same device.
 *
 * A link can spell a host differently from the way it was saved — `[2001:db8::1]` against
 * `2001:db8::1`, or `PHONE.local` against `phone.local` — and comparing the raw text meant a
 * link to a device already in the list connected as an anonymous one, silently dropping that
 * device's settings and touch calibration.
 *
 * Uses the same identity as port discovery, so an expanded IPv6 spelling, an IPv4-mapped address
 * or a percent-encoded zone all match, while a zone keeps its case (interface names are
 * case-sensitive). A host that parser rejects falls back to its plain lower-cased spelling, under
 * a tag of its own so that spelling can never equal the identity of a host the parser accepted.
 */
fun canonicalHost(host: String): String =
    EndpointHost.normalize(host) ?: ("raw:" + host.removeSurrounding("[", "]").trimEnd('.').lowercase())

/** Whether two endpoints name the same device, allowing for those spelling differences. */
fun sameEndpoint(host: String, port: Int, otherHost: String, otherPort: Int): Boolean =
    port == otherPort && canonicalHost(host) == canonicalHost(otherHost)

/**
 * Cuts this down to [limit] UTF-16 units without splitting a surrogate pair.
 *
 * Stopping mid-pair leaves half a character, which renders as a placeholder box wherever the
 * name is shown. Limits here are counted in UTF-16 units because that is what the stored field
 * lengths are counted in.
 */
fun String.takeWholeChars(limit: Int): String = when {
    length <= limit -> this
    this[limit - 1].isHighSurrogate() -> take(limit - 1)
    else -> take(limit)
}

/** Missing profiles deliberately inherit existing global settings, so upgrading changes no offsets. */
fun SavedDevice?.calibrationFor(landscape: Boolean, defaults: MirrorSettings): TouchCalibration =
    (if (landscape) this?.landscapeCalibration else this?.portraitCalibration)
        ?: TouchCalibration(defaults.touchOffsetXDp, defaults.touchOffsetYDp)

@Serializable
data class MirrorSettings(
    val maxSize: Int = 1600,
    val videoBitRateMbps: Int = 8,
    val maxFps: Int = 60,
    val videoCodec: VideoCodec = VideoCodec.H264,
    /** Locks the orientation the device captures in, instead of following its physical rotation. */
    val captureOrientation: CaptureOrientation = CaptureOrientation.FOLLOW_DEVICE,
    /**
     * A specific video encoder on the remote device, by name, or null to let it choose.
     *
     * Exists because some devices ship a broken default hardware encoder: mirroring then fails or
     * produces corrupt video, and naming a different encoder is the only way through.
     */
    val videoEncoder: String? = null,
    val audioEnabled: Boolean = true,
    val audioCodec: AudioCodec = AudioCodec.OPUS,
    /**
     * A specific audio encoder on the device, by name. Null lets the device choose.
     *
     * The audio counterpart to [videoEncoder]: a device whose default audio encoder is broken
     * otherwise leaves no option but turning audio off altogether.
     */
    val audioEncoder: String? = null,
    /**
     * `output` routes through REMOTE_SUBMIX, which silences the remote speaker. `playback`
     * captures what apps play and can leave it audible there as well; `mic` records the
     * remote microphone instead.
     */
    val audioSource: AudioSource = AudioSource.OUTPUT,
    /** Only meaningful for [AudioSource.PLAYBACK]; keeps the remote device's own speaker active. */
    val audioDup: Boolean = false,
    /** Keep playing remote audio while this app is in the background, behind a notification. */
    val backgroundAudio: Boolean = false,
    val stayAwake: Boolean = false,
    /**
     * scrcpy 4.0's `keep_active`: signals user activity periodically instead of changing a global
     * setting, so it also works on battery and leaves no state behind after the session.
     */
    val keepActive: Boolean = false,
    val showTouches: Boolean = false,
    val powerOffOnClose: Boolean = false,
    val turnScreenOff: Boolean = false,
    /**
     * Mirrors a new virtual display rather than the device's own screen, leaving the remote
     * device free for its own use. Null keeps mirroring the physical display; an empty string
     * creates one matching the device's size and density.
     */
    val virtualDisplay: String? = null,
    /**
     * Which of the device's existing displays to mirror. Zero is its primary screen.
     *
     * Ignored while [virtualDisplay] is set: the server refuses both at once, and creating a new
     * display already answers the question of which one to capture.
     */
    val displayId: Int = 0,
    /**
     * Package opened once the session is ready. A virtual display starts empty, so without this
     * every session begins by opening the app picker by hand.
     */
    val startApp: String? = null,
    /**
     * Adjustment applied to a touch before it is sent, in dp. Negative moves the injected point
     * up and left. Lets a user null out a residual offset between where they touch and where the
     * remote device registers the press.
     */
    val touchOffsetXDp: Int = 0,
    val touchOffsetYDp: Int = 0,
    /**
     * Claim the screen edges so edge swipes reach the remote device instead of triggering the
     * local device's own back gesture.
     */
    val captureEdgeGestures: Boolean = true,
    val syncMode: PlaybackSyncMode = PlaybackSyncMode.LOW_LATENCY,
    /** Zero preserves the codec's existing buffer; otherwise this is a duration in milliseconds. */
    val audioBufferMs: Int = 0,
    /** Only used in synchronized mode. Positive values delay video further. */
    val syncOffsetMs: Int = 0,
    val autoReconnect: Boolean = false,
    /**
     * Audio quality, in kbit/s.
     *
     * The server's own default, which this matches, is tuned for speech. Music and game audio
     * are the reason to raise it; a slow link is the reason to lower it.
     */
    val audioBitRateKbps: Int = 128,
    /**
     * Whether the device's clipboard follows this one automatically.
     *
     * On by default, matching scrcpy. Turning it off stops whatever is copied on either side
     * from crossing on its own — which matters when the remote device is not yours to read, or
     * when a password manager on this phone would otherwise be handing its clipboard over.
     * Copy and Paste still work when asked for.
     */
    val clipboardAutosync: Boolean = true,
    /**
     * Capture a camera instead of the screen.
     *
     * Null is ordinary screen mirroring. Set, the session becomes a viewfinder: the remote phone
     * points its camera somewhere and this one watches, with torch and zoom instead of touch.
     * Kept as one nullable group rather than a flag plus loose fields so "camera mode" is a single
     * thing that is either on or off, and cannot half-exist.
     */
    val camera: CameraCapture? = null,
    /**
     * Compatibility field for existing settings and backups. Historical values migrate to the
     * sole current release (see [ScrcpyServerVersion]); it is not a user-selectable setting.
     */
    val serverVersion: ScrcpyServerVersion = ScrcpyServerVersion.DEFAULT,
) {
    fun toOptions(): ScrcpyOptions = ScrcpyOptions(
        maxSize = maxSize,
        videoBitRate = videoBitRateMbps * 1_000_000,
        maxFps = maxFps,
        videoCodec = videoCodec,
        captureOrientation = captureOrientation,
        videoEncoder = videoEncoder?.takeIf { it.isNotBlank() },
        audio = audioEnabled,
        audioCodec = audioCodec,
        audioEncoder = audioEncoder?.takeIf { it.isNotBlank() },
        audioSource = audioSource,
        audioDup = audioDup,
        stayAwake = stayAwake,
        keepActive = keepActive,
        showTouches = showTouches,
        powerOffOnClose = powerOffOnClose,
        newDisplay = virtualDisplay?.let(VirtualDisplaySpec::normalize),
        displayId = displayId,
        audioBitRate = audioBitRateKbps * 1_000,
        clipboardAutosync = clipboardAutosync,
        camera = camera?.normalized(),
        server = serverVersion,
    )
}
