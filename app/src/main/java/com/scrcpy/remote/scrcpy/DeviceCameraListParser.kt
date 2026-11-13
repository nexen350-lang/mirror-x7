package com.scrcpy.remote.scrcpy

import java.io.IOException

/** Which way a camera points, as the device describes it. */
enum class CameraFacing(val serverValue: String) {
    FRONT("front"),
    BACK("back"),
    EXTERNAL("external"),
}

/**
 * One size a camera offers, and — for a high-speed mode — the frame rates that size supports.
 *
 * Sizes are carried as numbers rather than as the `WxH` text so a picker can sort and compare
 * them; [serverValue] is the only form the server accepts.
 */
data class CameraSize(
    val width: Int,
    val height: Int,
    val frameRates: List<Int> = emptyList(),
) {
    /** The `camera_size` value for this size. */
    val serverValue: String get() = "${width}x$height"

    /** Pixel count, for ordering largest-first without comparing two dimensions by hand. */
    val pixels: Long get() = width.toLong() * height.toLong()
}

/**
 * One camera the device reported.
 *
 * [maxZoom] is the far end of the device's own zoom range. It is carried so the viewfinder can say
 * whether zooming will do anything at all: a camera whose range is `[1, 1]` has no zoom, and
 * offering the control anyway would look broken.
 *
 * [sizes] and [highSpeedSizes] are only populated by a `list_camera_sizes` listing; the plain
 * camera listing does not print them, and an empty list there means "not asked" rather than
 * "none offered". They are kept apart because the server treats them as separate modes: a
 * high-speed size is only valid together with `camera_high_speed`, and pairing one with an
 * ordinary frame rate is refused.
 */
data class DeviceCamera(
    val id: String,
    val facing: CameraFacing?,
    val width: Int,
    val height: Int,
    val frameRates: List<Int> = emptyList(),
    val maxZoom: Float = 1f,
    val sizes: List<CameraSize> = emptyList(),
    val highSpeedSizes: List<CameraSize> = emptyList(),
) {
    val sizeKnown: Boolean get() = width > 0 && height > 0
    val canZoom: Boolean get() = maxZoom > 1f

    /** Whether the device answered with a size list, as opposed to not having been asked. */
    val sizesKnown: Boolean get() = sizes.isNotEmpty() || highSpeedSizes.isNotEmpty()
}

/**
 * Reads the camera list the scrcpy server prints for `list_cameras=true`.
 *
 * Captured from a device rather than written from documentation, because this project has twice
 * been caught by fixtures that described the format correctly and the bytes incorrectly:
 *
 * ```
 * [server] INFO: List of cameras:
 *     --camera-id=1    (back, 1856x1392, fps={15, 30}, zoom-range=[1, 10])
 * ```
 *
 * Every field after the id is optional in practice — a camera whose characteristics the device
 * will not answer for still gets a line — so each is parsed independently and a missing one leaves
 * its default rather than discarding the camera.
 *
 * Pure, so the format can be tested without a device.
 */
object DeviceCameraListParser {

    /** Header the server prints before the entries. */
    const val HEADER = "List of cameras:"

    private val ENTRY = Regex("""--camera-id=(\S+)""")
    private val FACING = Regex("""\((front|back|external)\b""", RegexOption.IGNORE_CASE)
    private val SIZE = Regex("""\b(\d+)x(\d+)\b""")
    private val FPS = Regex("""fps=\{([^}]*)\}""")
    private val ZOOM = Regex("""zoom-range=\[\s*([\d.]+)\s*,\s*([\d.]+)\s*\]""")

    /**
     * A size line beneath a camera: `        - 1280x720`, optionally `(fps={120, 240})`.
     *
     * Anchored on the leading dash so the camera's own line — which also contains a `WxH` — is
     * never mistaken for one of its sizes. `--camera-id=` cannot match: a second dash follows the
     * first, where this expects whitespace or a digit.
     */
    private val SIZE_ENTRY = Regex("""^\s*-\s*(\d+)x(\d+)""")

    /** Everything after this line, until the next camera, belongs to the high-speed mode. */
    private val HIGH_SPEED_SECTION = Regex("""High speed capture""", RegexOption.IGNORE_CASE)

    fun parse(output: String): List<DeviceCamera> {
        val cameras = mutableListOf<DeviceCamera>()
        var pending: DeviceCamera? = null
        val sizes = mutableListOf<CameraSize>()
        val highSpeedSizes = mutableListOf<CameraSize>()
        var inHighSpeed = false

        // A camera is only complete once the line after its last size has been seen, so it is held
        // back until the next camera starts or the output ends.
        fun emit() {
            pending?.let {
                cameras += it.copy(
                    sizes = sizes.toList(),
                    highSpeedSizes = highSpeedSizes.toList(),
                )
            }
            pending = null
            sizes.clear()
            highSpeedSizes.clear()
            inHighSpeed = false
        }

        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r')
            val id = ENTRY.find(line)?.groupValues?.get(1)
            if (id != null) {
                emit()
                val facing = FACING.find(line)?.groupValues?.get(1)?.lowercase()?.let { token ->
                    CameraFacing.entries.firstOrNull { it.serverValue == token }
                }
                val size = SIZE.find(line)
                val rates = FPS.find(line)?.groupValues?.get(1)
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it > 0 }
                    .orEmpty()
                // The upper bound is the useful half; the lower is 1 on every device seen.
                val zoom = ZOOM.find(line)?.groupValues?.get(2)?.toFloatOrNull() ?: 1f
                pending = DeviceCamera(
                    id = id,
                    facing = facing,
                    width = size?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    height = size?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                    frameRates = rates.distinct().sorted(),
                    maxZoom = if (zoom.isFinite() && zoom >= 1f) zoom else 1f,
                )
                continue
            }

            // Size lines only appear under a camera; one before any camera is not ours to read.
            if (pending == null) continue
            if (HIGH_SPEED_SECTION.containsMatchIn(line)) {
                inHighSpeed = true
                continue
            }
            val entry = SIZE_ENTRY.find(line) ?: continue
            val width = entry.groupValues[1].toIntOrNull() ?: continue
            val height = entry.groupValues[2].toIntOrNull() ?: continue
            if (width <= 0 || height <= 0) continue
            val entryRates = FPS.find(line)?.groupValues?.get(1)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }
                ?.distinct()
                ?.sorted()
                .orEmpty()
            val size = CameraSize(width, height, entryRates)
            // The device's own order is kept: it lists them in the order the camera reports, and
            // reordering here would hide which one the camera considers its natural size.
            if (inHighSpeed) highSpeedSizes += size else sizes += size
        }
        emit()

        // A device can repeat an id across the listing; keep the first description of each.
        return cameras.distinctBy { it.id }
    }

    /**
     * Parses server output, rejecting output that never reached the listing stage so the caller
     * reports a failure instead of a device that appears to have no cameras at all.
     *
     * A device older than Android 12 cannot capture from a camera, and the server says so rather
     * than printing an empty list; that message is passed through so the user is told why.
     */
    @Throws(IOException::class)
    fun parseServerOutput(output: String): List<DeviceCamera> {
        if (!output.contains(HEADER)) {
            val unsupported = output.lineSequence()
                .map { it.substringAfter("[server]").trim() }
                .firstOrNull { it.contains("Camera mirroring is not supported", ignoreCase = true) }
            throw IOException(
                unsupported ?: ("The device did not return its cameras: " +
                    output.trim().ifBlank { "no output" }.take(200))
            )
        }
        return parse(output)
    }
}
