package com.scrcpy.remote.scrcpy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Device -> client messages, as produced by
 * `com.genymobile.scrcpy.control.DeviceMessageWriter` (scrcpy 5.0, unchanged since 4.1).
 */
sealed class DeviceMessage {

    data class Clipboard(val text: String) : DeviceMessage()

    data class AckClipboard(val sequence: Long) : DeviceMessage()

    data class UhidOutput(val id: Int, val data: ByteArray) : DeviceMessage() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is UhidOutput && id == other.id && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * id + data.contentHashCode()
    }

    companion object {
        const val TYPE_CLIPBOARD = 0
        const val TYPE_ACK_CLIPBOARD = 1
        const val TYPE_UHID_OUTPUT = 2
    }
}

class DeviceMessageReader(input: InputStream) {

    private val dis = DataInputStream(input.buffered())

    fun read(): DeviceMessage {
        return when (val type = dis.readUnsignedByte()) {
            DeviceMessage.TYPE_CLIPBOARD -> {
                val length = dis.readInt()
                if (length < 0 || length > MAX_TEXT_LENGTH) {
                    throw IOException("Invalid clipboard length: $length")
                }
                val raw = ByteArray(length)
                dis.readFully(raw)
                DeviceMessage.Clipboard(String(raw, Charsets.UTF_8))
            }

            DeviceMessage.TYPE_ACK_CLIPBOARD -> DeviceMessage.AckClipboard(dis.readLong())

            DeviceMessage.TYPE_UHID_OUTPUT -> {
                val id = dis.readUnsignedShort()
                val size = dis.readUnsignedShort()
                val data = ByteArray(size)
                dis.readFully(data)
                DeviceMessage.UhidOutput(id, data)
            }

            else -> throw IOException("Unknown device message type: $type")
        }
    }

    private companion object {
        const val MAX_TEXT_LENGTH = (1 shl 18) - 5
    }
}
