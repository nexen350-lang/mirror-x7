package com.scrcpy.remote.data

import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.EncoderName
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

object SettingsValidation {
    val MAX_SIZE_RANGE = 640..2560
    val BIT_RATE_RANGE = 1..30
    val FPS_RANGE = 15..120
    val TOUCH_OFFSET_RANGE = -120..120
    val AUDIO_BUFFER_RANGE = 20..250
    /** What the server and the codecs will accept; Opus and AAC are both sensible across it. */
    val AUDIO_BIT_RATE_RANGE = 16..320
    val SYNC_OFFSET_RANGE = -200..200

    /**
     * Android display ids are small non-negative integers. Bounded rather than unlimited so a
     * restored backup cannot name a display the device will never have.
     */
    val DISPLAY_ID_RANGE = 0..4096

    /** The avatar palette's colours, by index. The palette itself lives with the interface. */
    val AVATAR_COLORS = 0..7

    private val cameraSizeSeparator = Pattern.compile("x")
    private val cameraAspectRatioSeparator = Pattern.compile(":")

    fun settingsErrors(settings: MirrorSettings): Map<String, String> = buildMap {
        fun check(field: String, value: Int, range: IntRange) {
            if (value !in range) put(field, "Must be between ${range.first} and ${range.last}")
        }
        check("maxSize", settings.maxSize, MAX_SIZE_RANGE)
        check("videoBitRateMbps", settings.videoBitRateMbps, BIT_RATE_RANGE)
        check("maxFps", settings.maxFps, FPS_RANGE)
        check("touchOffsetXDp", settings.touchOffsetXDp, TOUCH_OFFSET_RANGE)
        check("touchOffsetYDp", settings.touchOffsetYDp, TOUCH_OFFSET_RANGE)
        if (settings.audioBufferMs != 0 && settings.audioBufferMs !in AUDIO_BUFFER_RANGE) {
            put("audioBufferMs", "Must be automatic (0) or between ${AUDIO_BUFFER_RANGE.first} and ${AUDIO_BUFFER_RANGE.last}")
        }
        check("syncOffsetMs", settings.syncOffsetMs, SYNC_OFFSET_RANGE)
        check("audioBitRateKbps", settings.audioBitRateKbps, AUDIO_BIT_RATE_RANGE)
        settings.virtualDisplay?.let { spec ->
            VirtualDisplaySpec.error(spec)?.let { put("virtualDisplay", it) }
        }
        if (settings.displayId !in DISPLAY_ID_RANGE) {
            put("displayId", "Choose a display the device reported")
        }
        settings.startApp?.let { app ->
            if (!AppPackage.isSendable(app)) {
                put("startApp", "Enter a package name such as com.example.app")
            }
        }
        settings.videoEncoder?.let { encoder ->
            if (!EncoderName.isUsable(encoder)) {
                put("videoEncoder", "Enter an encoder name such as c2.android.avc.encoder")
            }
        }
        settings.audioEncoder?.let { encoder ->
            if (!EncoderName.isUsable(encoder)) {
                put("audioEncoder", "Enter an encoder name such as c2.android.opus.encoder")
            }
        }
        settings.camera?.let { camera ->
            cameraError(camera)?.let { put("camera", it) }
        }
    }

    /** Match the server's Java split/numeric parsers, not the virtual-display UI's size limits. */
    internal fun cameraError(camera: CameraCapture): String? {
        val normalized = camera.normalized()
        normalized.size?.let { size ->
            val valid = try {
                val dimensions = cameraSizeSeparator.split(size)
                dimensions.size == 2 && dimensions.all { Integer.parseInt(it) > 0 }
            } catch (_: NumberFormatException) {
                false
            }
            if (!valid) return "Camera size must contain positive integer dimensions, such as 1280x720"
        }
        normalized.aspectRatio?.let { aspectRatio ->
            if (aspectRatio == "sensor") return@let
            val valid = try {
                val parts = cameraAspectRatioSeparator.split(aspectRatio)
                when {
                    parts.size == 2 -> parts.all { Integer.parseInt(it) > 0 }
                    // Match the server comparison: zero, NaN, and positive infinity are parser-valid.
                    parts.isNotEmpty() -> !(java.lang.Float.parseFloat(parts[0]) < 0f)
                    else -> false
                }
            } catch (_: NumberFormatException) {
                false
            }
            if (!valid) return "Camera aspect ratio must be sensor, a nonnegative number, or a ratio such as 16:9"
        }
        return null
    }

    fun endpointError(host: String, port: Int): String? {
        if (port !in 1..65535) return "Port must be between 1 and 65535"
        if (host.isBlank() || host != host.trim() || host.length > 253) {
            return "Enter a host name or IP address without whitespace"
        }
        val literal = if (host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.lastIndex)
        } else host
        if (':' in literal) {
            if (!literal.matches(Regex("[0-9a-fA-F:.]+(%[A-Za-z0-9_.-]+)?"))) {
                return "Invalid IPv6 address"
            }
            return try {
                if (URI("http://[$literal]").host == null) "Invalid IPv6 address" else null
            } catch (_: URISyntaxException) {
                "Invalid IPv6 address"
            }
        }
        if (host.all { it in '0'..'9' || it == '.' }) {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any {
                    it.isEmpty() || it.length > 3 || (it.length > 1 && it.startsWith('0')) ||
                        it.toIntOrNull() !in 0..255
                }) return "Invalid IPv4 address"
            return null
        }
        val label = Regex("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")
        if (host.removeSuffix(".").split('.').any { !it.matches(label) }) {
            return "Invalid host name"
        }
        return null
    }

    fun deviceError(device: SavedDevice): String? = when {
        device.id.isBlank() || device.id.length > 256 -> "Invalid device identifier"
        device.name.isBlank() || device.name.length > 100 -> "Device name must contain 1 to 100 characters"
        DeviceNameRules.hasInvisibleOrSpoofing(device.name) -> "Device name contains invisible or control characters"
        device.avatarColor?.let { it !in AVATAR_COLORS } == true -> "Avatar color must be one of the palette's colors"
        listOfNotNull(device.portraitCalibration, device.landscapeCalibration).any {
            it.xDp !in TOUCH_OFFSET_RANGE || it.yDp !in TOUCH_OFFSET_RANGE
        } -> "Calibration offsets must be between ${TOUCH_OFFSET_RANGE.first} and ${TOUCH_OFFSET_RANGE.last}"
        device.mirroringSettings?.let { settingsErrors(it.applyTo(MirrorSettings())).isNotEmpty() } == true ->
            "Device mirroring settings are outside the supported ranges"
        else -> endpointError(device.host, device.port)
    }
}
