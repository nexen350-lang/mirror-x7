package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.DeviceMessage
import com.scrcpy.remote.scrcpy.DeviceMessageReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Verifies parsing against `com.genymobile.scrcpy.control.DeviceMessageWriter` (scrcpy 5.0, unchanged since 4.1).
 */
class DeviceMessageReaderTest {

    private fun reader(block: DataOutputStream.() -> Unit): DeviceMessageReader {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return DeviceMessageReader(buffer.toByteArray().inputStream())
    }

    @Test
    fun readsClipboardMessage() {
        val text = "clipboard content"
        val message = reader {
            writeByte(DeviceMessage.TYPE_CLIPBOARD)
            val raw = text.toByteArray(Charsets.UTF_8)
            writeInt(raw.size)
            write(raw)
        }.read()

        assertEquals(DeviceMessage.Clipboard(text), message)
    }

    @Test
    fun readsAckClipboardMessage() {
        val message = reader {
            writeByte(DeviceMessage.TYPE_ACK_CLIPBOARD)
            writeLong(0x0102030405060708L)
        }.read()

        assertEquals(DeviceMessage.AckClipboard(0x0102030405060708L), message)
    }

    @Test
    fun readsUhidOutputMessage() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val message = reader {
            writeByte(DeviceMessage.TYPE_UHID_OUTPUT)
            writeShort(42)
            writeShort(payload.size)
            write(payload)
        }.read() as DeviceMessage.UhidOutput

        assertEquals(42, message.id)
        assertArrayEquals(payload, message.data)
    }

    @Test
    fun readsConsecutiveMessages() {
        val streamReader = reader {
            writeByte(DeviceMessage.TYPE_ACK_CLIPBOARD)
            writeLong(7L)
            writeByte(DeviceMessage.TYPE_CLIPBOARD)
            val raw = "second".toByteArray(Charsets.UTF_8)
            writeInt(raw.size)
            write(raw)
        }

        assertEquals(DeviceMessage.AckClipboard(7L), streamReader.read())
        assertEquals(DeviceMessage.Clipboard("second"), streamReader.read())
    }

    @Test(expected = IOException::class)
    fun rejectsUnknownMessageType() {
        reader { writeByte(0x7F) }.read()
    }

    @Test(expected = IOException::class)
    fun rejectsOversizedClipboardLength() {
        reader {
            writeByte(DeviceMessage.TYPE_CLIPBOARD)
            writeInt(Int.MAX_VALUE)
        }.read()
    }
}
