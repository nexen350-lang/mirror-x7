package com.scrcpy.remote.scrcpy

import kotlinx.serialization.Serializable

/**
 * What to capture when the source is a camera rather than the screen.
 *
 * scrcpy will not accept a camera id and a facing at once — the facing is a way of asking for
 * "whichever camera points that way" when the id is not known ahead of time — so [normalized]
 * keeps only one of them rather than letting the server refuse the session.
 *
 * Camera capture needs Android 12 on the device being captured. That is the device's limit, not
 * this app's, and it is reported when the camera list is asked for rather than guessed at here.
 */
@Serializable
data class CameraCapture(
    /** The device's own id for the camera, as printed by `list_cameras`. Ids are strings. */
    val id: String? = null,
    /** Used only when [id] is null: ask the device for whichever camera points this way. */
    val facing: CameraFacing? = null,
    /** `WxH`, or null to let the device pick. A size the camera does not offer is refused. */
    val size: String? = null,
    /** `W:H`, an alternative to naming an exact size. */
    val aspectRatio: String? = null,
    /** Frames per second to request; zero lets the device choose. */
    val fps: Int = 0,
    /** High-speed capture, for the high frame rates some cameras only offer in that mode. */
    val highSpeed: Boolean = false,
) {
    /** The form the server will accept: at most one of id and facing, and no blank strings. */
    fun normalized(): CameraCapture = copy(
        id = id?.takeIf { it.isNotBlank() },
        facing = if (id.isNullOrBlank()) facing else null,
        size = size?.takeIf { it.isNotBlank() },
        aspectRatio = aspectRatio?.takeIf { it.isNotBlank() },
        fps = fps.coerceAtLeast(0),
    )

    companion object {
        /** Sizes worth offering; the device's own list is preferred when it provides one. */
        val COMMON_SIZES = listOf("640x480", "1280x720", "1920x1080", "2560x1440", "3840x2160")
    }
}
