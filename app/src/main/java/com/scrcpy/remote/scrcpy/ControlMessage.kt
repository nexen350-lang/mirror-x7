package com.scrcpy.remote.scrcpy

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Client -> device messages, serialized exactly as
 * `com.genymobile.scrcpy.control.ControlMessageReader` expects them (scrcpy 5.0, unchanged since 4.1).
 *
 * All integers are big-endian (Java `DataOutputStream` order).
 */
sealed class ControlMessage {

    abstract fun writeTo(out: DataOutputStream)

    fun toByteArray(): ByteArray {
        val buffer = ByteArrayOutputStream(64)
        DataOutputStream(buffer).use { writeTo(it) }
        return buffer.toByteArray()
    }

    /** type: 1, action: 1, keycode: 4, repeat: 4, metaState: 4 */
    data class InjectKeycode(
        val action: Int,
        val keycode: Int,
        val repeat: Int = 0,
        val metaState: Int = 0,
    ) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_INJECT_KEYCODE)
            out.writeByte(action)
            out.writeInt(keycode)
            out.writeInt(repeat)
            out.writeInt(metaState)
        }
    }

    /** type: 1, length: 4, text */
    data class InjectText(val text: String) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            val raw = Utf8Text.encode(text, INJECT_TEXT_MAX_LENGTH)
            out.writeByte(TYPE_INJECT_TEXT)
            out.writeInt(raw.size)
            out.write(raw)
        }
    }

    /**
     * type: 1, action: 1, pointerId: 8, x: 4, y: 4, screenWidth: 2, screenHeight: 2,
     * pressure: 2, actionButton: 4, buttons: 4 (32 bytes)
     */
    data class InjectTouchEvent(
        val action: Int,
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val pressure: Float,
        val actionButton: Int = 0,
        val buttons: Int = 0,
    ) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_INJECT_TOUCH_EVENT)
            out.writeByte(action)
            out.writeLong(pointerId)
            writePosition(out, x, y, screenWidth, screenHeight)
            out.writeShort(floatToU16Fixed(pressure))
            out.writeInt(actionButton)
            out.writeInt(buttons)
        }
    }

    /** type: 1, position: 12, hScroll: 2, vScroll: 2, buttons: 4 (21 bytes) */
    data class InjectScrollEvent(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val hScroll: Float,
        val vScroll: Float,
        val buttons: Int = 0,
    ) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_INJECT_SCROLL_EVENT)
            writePosition(out, x, y, screenWidth, screenHeight)
            // The server multiplies the decoded value by 16, so the wire range is [-16, 16].
            out.writeShort(floatToI16Fixed(hScroll / 16f))
            out.writeShort(floatToI16Fixed(vScroll / 16f))
            out.writeInt(buttons)
        }
    }

    /** type: 1, action: 1 */
    data class BackOrScreenOn(val action: Int) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_BACK_OR_SCREEN_ON)
            out.writeByte(action)
        }
    }

    /** type: 1, copyKey: 1 */
    data class GetClipboard(val copyKey: Int) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_GET_CLIPBOARD)
            out.writeByte(copyKey)
        }
    }

    /** type: 1, sequence: 8, paste: 1, length: 4, text */
    data class SetClipboard(
        val sequence: Long,
        val text: String,
        val paste: Boolean,
    ) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            val raw = Utf8Text.encode(text, CLIPBOARD_TEXT_MAX_LENGTH)
            out.writeByte(TYPE_SET_CLIPBOARD)
            out.writeLong(sequence)
            out.writeByte(if (paste) 1 else 0)
            out.writeInt(raw.size)
            out.write(raw)
        }
    }

    /** type: 1, on: 1 */
    data class SetDisplayPower(val on: Boolean) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_SET_DISPLAY_POWER)
            out.writeByte(if (on) 1 else 0)
        }
    }

    /**
     * type: 1, nameLength: 1, name
     *
     * The server reads a leading `+` as "force-stop the app first" and a leading `?` as "search by
     * display name" rather than by package. Use [startApp] so those prefixes are always deliberate.
     */
    data class StartApp(val name: String) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            val raw = Utf8Text.encode(name, 255)
            out.writeByte(TYPE_START_APP)
            out.writeByte(raw.size)
            out.write(raw)
        }
    }

    /** type: 1, width: 2, height: 2; dimensions are positive unsigned 16-bit values. */
    data class ResizeDisplay(val width: Int, val height: Int) : ControlMessage() {
        init {
            require(width in 1..65535 && height in 1..65535) {
                "Display dimensions must be between 1 and 65535: ${width}x$height"
            }
        }

        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_RESIZE_DISPLAY)
            out.writeShort(width)
            out.writeShort(height)
        }
    }

    /** type: 1, on: 1 */
    data class CameraSetTorch(val on: Boolean) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            out.writeByte(TYPE_CAMERA_SET_TORCH)
            out.writeByte(if (on) 1 else 0)
        }
    }

    /** type: 1, length: 4, path */
    data class ScanFile(val path: String) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) {
            val raw = Utf8Text.encode(path)
            out.writeByte(TYPE_SCAN_FILE)
            out.writeInt(raw.size)
            out.write(raw)
        }
    }

    /** Messages carrying no payload beyond their type. */
    data class Empty(val type: Int) : ControlMessage() {
        override fun writeTo(out: DataOutputStream) = out.writeByte(type)
    }

    companion object {
        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TEXT = 1
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_INJECT_SCROLL_EVENT = 3
        const val TYPE_BACK_OR_SCREEN_ON = 4
        const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        const val TYPE_EXPAND_SETTINGS_PANEL = 6
        const val TYPE_COLLAPSE_PANELS = 7
        const val TYPE_GET_CLIPBOARD = 8
        const val TYPE_SET_CLIPBOARD = 9
        const val TYPE_SET_DISPLAY_POWER = 10
        const val TYPE_ROTATE_DEVICE = 11
        const val TYPE_UHID_CREATE = 12
        const val TYPE_UHID_INPUT = 13
        const val TYPE_UHID_DESTROY = 14
        const val TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 15
        const val TYPE_START_APP = 16
        const val TYPE_RESET_VIDEO = 17
        const val TYPE_CAMERA_SET_TORCH = 18
        const val TYPE_CAMERA_ZOOM_IN = 19
        const val TYPE_CAMERA_ZOOM_OUT = 20
        const val TYPE_RESIZE_DISPLAY = 21
        const val TYPE_SCAN_FILE = 22

        const val COPY_KEY_NONE = 0
        const val COPY_KEY_COPY = 1
        const val COPY_KEY_CUT = 2

        const val SEQUENCE_INVALID = 0L

        /** Mirrors `ControlMessageReader.INJECT_TEXT_MAX_LENGTH` in the scrcpy server. */
        const val INJECT_TEXT_MAX_LENGTH = 300

        /** Mirrors `ControlMessageReader.CLIPBOARD_TEXT_MAX_LENGTH` in the scrcpy server. */
        const val CLIPBOARD_TEXT_MAX_LENGTH = (1 shl 18) - 14

        /**
         * Splits valid Unicode into messages of at most 300 UTF-8 bytes; empty input sends nothing.
         * This only guarantees wire encoding: Android key injection cannot type arbitrary Unicode.
         * Prefer [SetClipboard] with paste enabled when the target supports clipboard paste.
         */
        fun textMessages(text: String): List<InjectText> =
            Utf8Text.chunks(text, INJECT_TEXT_MAX_LENGTH).map(::InjectText)

        /** Largest code-point-aligned clipboard prefix within the protocol's UTF-8 byte limit. */
        fun truncateClipboard(text: String): String =
            Utf8Text.truncate(text, CLIPBOARD_TEXT_MAX_LENGTH)

        fun expandNotificationPanel() = Empty(TYPE_EXPAND_NOTIFICATION_PANEL)
        fun expandSettingsPanel() = Empty(TYPE_EXPAND_SETTINGS_PANEL)
        fun collapsePanels() = Empty(TYPE_COLLAPSE_PANELS)
        fun rotateDevice() = Empty(TYPE_ROTATE_DEVICE)
        fun openHardKeyboardSettings() = Empty(TYPE_OPEN_HARD_KEYBOARD_SETTINGS)
        fun resetVideo() = Empty(TYPE_RESET_VIDEO)

        /**
         * Steps the camera's zoom in or out.
         *
         * The device owns the zoom level and its limits; these ask for a step rather than naming a
         * factor, so a camera already at its limit simply stays there. Meaningless outside a
         * camera session, which is why the controls that send them only exist there.
         */
        fun cameraZoomIn() = Empty(TYPE_CAMERA_ZOOM_IN)
        fun cameraZoomOut() = Empty(TYPE_CAMERA_ZOOM_OUT)

        /**
         * Launches [packageName], optionally force-stopping it first so a frozen or misbehaving
         * app restarts cleanly.
         *
         * Package names cannot contain `+` or `?`, so prefixing one can never be ambiguous.
         */
        fun startApp(packageName: String, forceStop: Boolean = false): StartApp =
            StartApp(if (forceStop) "+$packageName" else packageName)

        private fun writePosition(out: DataOutputStream, x: Int, y: Int, width: Int, height: Int) {
            out.writeInt(x)
            out.writeInt(y)
            out.writeShort(width)
            out.writeShort(height)
        }

        /** Encodes [value] in [0, 1] the way `Binary.u16FixedPointToFloat()` decodes it. */
        fun floatToU16Fixed(value: Float): Int {
            val scaled = (value.coerceIn(0f, 1f) * 65536f).toLong()
            return scaled.coerceAtMost(0xFFFF).toInt()
        }

        /** Encodes [value] in [-1, 1] the way `Binary.i16FixedPointToFloat()` decodes it. */
        fun floatToI16Fixed(value: Float): Int {
            val scaled = (value.coerceIn(-1f, 1f) * 32768f).toLong()
            return scaled.coerceIn(-0x8000L, 0x7FFFL).toInt()
        }
    }
}
