package com.scrcpy.remote

import com.scrcpy.remote.scrcpy.ControlMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Verifies the wire format against `ControlMessageReader` from scrcpy 5.0, unchanged since 4.1.
 *
 * Expected byte sequences mirror scrcpy's own `test_control_msg_serialize.c`.
 */
class ControlMessageSerializationTest {

    @Test
    fun injectKeycode() {
        val bytes = ControlMessage.InjectKeycode(
            action = 0,
            keycode = 66,
            repeat = 5,
            metaState = 0x11,
        ).toByteArray()

        assertArrayEquals(
            byteArrayOf(
                0, // TYPE_INJECT_KEYCODE
                0, // AKEY_EVENT_ACTION_DOWN
                0x00, 0x00, 0x00, 0x42, // keycode
                0x00, 0x00, 0x00, 0x05, // repeat
                0x00, 0x00, 0x00, 0x11, // metaState
            ),
            bytes,
        )
        assertEquals(14, bytes.size)
    }

    @Test
    fun getClipboardCarriesTheCopyKeyThatMakesSelectionWork() {
        // The copy key is what asks the device to copy or cut the current selection before it
        // answers. Sending NONE only ever returned whatever had last been copied on the device,
        // so selecting text over there and pressing Copy here did nothing at all.
        for ((copyKey, expected) in mapOf(
            ControlMessage.COPY_KEY_NONE to 0.toByte(),
            ControlMessage.COPY_KEY_COPY to 1.toByte(),
            ControlMessage.COPY_KEY_CUT to 2.toByte(),
        )) {
            val bytes = ControlMessage.GetClipboard(copyKey).toByteArray()
            assertArrayEquals(byteArrayOf(8, expected), bytes)
        }
    }

    @Test
    fun injectText() {
        val bytes = ControlMessage.InjectText("hello, world!").toByteArray()
        assertEquals(1 + 4 + 13, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(13, bytes[4].toInt())
        assertEquals("hello, world!", String(bytes, 5, 13, Charsets.UTF_8))
    }

    @Test
    fun textChunksRespectUtf8BytesForKoreanAndEmoji() {
        val text = "한글".repeat(101) + "😀".repeat(151) + "hello"
        val messages = ControlMessage.textMessages(text)
        assertEquals(text, messages.joinToString("") { it.text })
        assertTrue(messages.isNotEmpty())
        for (message in messages) {
            val bytes = message.toByteArray()
            val length = ByteBuffer.wrap(bytes).getInt(1)
            assertTrue(length in 1..ControlMessage.INJECT_TEXT_MAX_LENGTH)
            assertEquals(length + 5, bytes.size)
            assertEquals(message.text, decodeStrict(bytes, 5, length))
        }
        assertEquals(300, ControlMessage.textMessages("한".repeat(100)).single().toByteArray().size - 5)
        assertEquals(300, ControlMessage.textMessages("😀".repeat(75)).single().toByteArray().size - 5)
    }

    @Test
    fun emojiStraddlingTextBoundaryMovesIntactToNextMessage() {
        val text = "a".repeat(299) + "😀" + "한"
        val messages = ControlMessage.textMessages(text)
        assertEquals(listOf("a".repeat(299), "😀한"), messages.map { it.text })
        assertEquals(text, messages.joinToString("") { it.text })
        assertEquals(7, messages.last().toByteArray().size - 5)
        assertEquals(emptyList<ControlMessage.InjectText>(), ControlMessage.textMessages(""))
    }

    @Test
    fun serializersEnforceUtf8LimitsBeforeWritingAnyHeader() {
        val maxClipboard = ControlMessage.CLIPBOARD_TEXT_MAX_LENGTH
        val valid = listOf(
            ControlMessage.InjectText("a".repeat(300)),
            ControlMessage.InjectText("한".repeat(100)),
            ControlMessage.InjectText("😀".repeat(75)),
            ControlMessage.SetClipboard(1, "a".repeat(maxClipboard), true),
        )
        valid.forEach { it.toByteArray() }
        val invalid = listOf(
            ControlMessage.InjectText("a".repeat(301)),
            ControlMessage.InjectText("한".repeat(101)),
            ControlMessage.InjectText("😀".repeat(76)),
            ControlMessage.SetClipboard(1, "a".repeat(maxClipboard + 1), true),
            ControlMessage.SetClipboard(1, "한".repeat(maxClipboard / 3 + 1), true),
        )
        invalid.forEach { message ->
            val bytes = ByteArrayOutputStream()
            expectInvalid { message.writeTo(DataOutputStream(bytes)) }
            assertEquals(0, bytes.size())
        }
    }

    @Test
    fun hugeClipboardTruncatesAtCodePointBoundaryAndExactByteLimit() {
        val max = ControlMessage.CLIPBOARD_TEXT_MAX_LENGTH
        val text = "한😀".repeat(100_000)
        val truncated = ControlMessage.truncateClipboard(text)
        assertEquals("한😀".repeat(max / 7), truncated)
        assertTrue(text.startsWith(truncated))
        val bytes = ControlMessage.SetClipboard(42, truncated, false).toByteArray()
        val length = ByteBuffer.wrap(bytes).getInt(10)
        assertTrue(length <= max)
        assertTrue(max - length < 3)
        assertEquals(truncated, decodeStrict(bytes, 14, length))

        val exact = "a".repeat(max - 4) + "😀"
        assertEquals(exact, ControlMessage.truncateClipboard(exact + "more"))
        assertEquals(max + 14, ControlMessage.SetClipboard(1, exact, false).toByteArray().size)
        val straddling = "a".repeat(max - 1) + "😀"
        assertEquals("a".repeat(max - 1), ControlMessage.truncateClipboard(straddling))
        assertEquals("", ControlMessage.truncateClipboard(""))
    }

    @Test
    fun loneSurrogatesAreExplicitlyRejectedIncludingTruncatedSuffix() {
        val malformed = listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800", "\uDC00\uD800")
        for (text in malformed) {
            expectInvalid { ControlMessage.textMessages(text) }
            expectInvalid { ControlMessage.truncateClipboard(text) }
            expectInvalid { ControlMessage.InjectText(text).toByteArray() }
            expectInvalid { ControlMessage.SetClipboard(1, text, false).toByteArray() }
            expectInvalid { ControlMessage.StartApp(text).toByteArray() }
            expectInvalid { ControlMessage.ScanFile(text).toByteArray() }
        }
        expectInvalid {
            ControlMessage.truncateClipboard("a".repeat(ControlMessage.CLIPBOARD_TEXT_MAX_LENGTH) + "\uD800")
        }
    }

    private fun decodeStrict(bytes: ByteArray, offset: Int, length: Int): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid control message input to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected before any message header is emitted.
        }
    }

    @Test
    fun injectTouchEvent() {
        val bytes = ControlMessage.InjectTouchEvent(
            action = 0,
            pointerId = 0x1234567887654321L,
            x = 100,
            y = 200,
            screenWidth = 1080,
            screenHeight = 1920,
            pressure = 1f,
            actionButton = 1,
            buttons = 1,
        ).toByteArray()

        assertArrayEquals(
            byteArrayOf(
                2, // TYPE_INJECT_TOUCH_EVENT
                0, // AMOTION_EVENT_ACTION_DOWN
                0x12, 0x34, 0x56, 0x78, 0x87.toByte(), 0x65, 0x43, 0x21, // pointer id
                0x00, 0x00, 0x00, 0x64, // x
                0x00, 0x00, 0x00, 0xC8.toByte(), // y
                0x04, 0x38, // screen width
                0x07, 0x80.toByte(), // screen height
                0xFF.toByte(), 0xFF.toByte(), // pressure == 1.0
                0x00, 0x00, 0x00, 0x01, // action button
                0x00, 0x00, 0x00, 0x01, // buttons
            ),
            bytes,
        )
        assertEquals(32, bytes.size)
    }

    @Test
    fun injectScrollEvent() {
        val bytes = ControlMessage.InjectScrollEvent(
            x = 260,
            y = 1026,
            screenWidth = 1080,
            screenHeight = 1920,
            hScroll = 1f,
            vScroll = -1f,
            buttons = 1,
        ).toByteArray()

        assertEquals(21, bytes.size)
        assertEquals(3, bytes[0].toInt())
        // 1/16 and -1/16 encoded as 16-bit fixed point.
        assertEquals(0x08, bytes[13].toInt())
        assertEquals(0x00, bytes[14].toInt())
        assertEquals(0xF8, bytes[15].toInt() and 0xFF)
        assertEquals(0x00, bytes[16].toInt())
    }

    @Test
    fun setClipboard() {
        val bytes = ControlMessage.SetClipboard(
            sequence = 0x0102030405060708L,
            text = "hello",
            paste = true,
        ).toByteArray()

        assertEquals(1 + 8 + 1 + 4 + 5, bytes.size)
        assertEquals(9, bytes[0].toInt())
        assertEquals(1, bytes[9].toInt()) // paste flag
        assertEquals(5, bytes[13].toInt())
    }

    @Test
    fun resizeDisplayIsFiveBytesWithBigEndianUnsignedDimensions() {
        assertArrayEquals(
            byteArrayOf(21, 0x05, 0x00, 0x03, 0xC0.toByte()),
            ControlMessage.ResizeDisplay(1280, 960).toByteArray(),
        )
        for (width in listOf(1, 65535)) {
            for (height in listOf(1, 65535)) {
                val bytes = ControlMessage.ResizeDisplay(width, height).toByteArray()
                assertEquals(5, bytes.size)
                assertEquals(21, bytes[0].toInt())
                val wire = ByteBuffer.wrap(bytes)
                assertEquals(width, wire.getShort(1).toInt() and 0xFFFF)
                assertEquals(height, wire.getShort(3).toInt() and 0xFFFF)
            }
        }
    }

    @Test
    fun invalidResizeDimensionsAreRejectedBeforeWritingAnyHeader() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
            for ((width, height) in listOf(invalid to 960, 1280 to invalid)) {
                val bytes = ByteArrayOutputStream()
                expectInvalid {
                    ControlMessage.ResizeDisplay(width, height).writeTo(DataOutputStream(bytes))
                }
                assertEquals("Rejected resize ${width}x$height wrote a partial message", 0, bytes.size())
            }
        }
    }

    @Test
    fun emptyMessagesAreASingleByte() {
        assertArrayEquals(byteArrayOf(5), ControlMessage.expandNotificationPanel().toByteArray())
        assertArrayEquals(byteArrayOf(7), ControlMessage.collapsePanels().toByteArray())
        assertArrayEquals(byteArrayOf(11), ControlMessage.rotateDevice().toByteArray())
        assertArrayEquals(byteArrayOf(17), ControlMessage.resetVideo().toByteArray())
    }

    @Test
    fun fixedPointConversionsMatchServerDecoding() {
        // Binary.u16FixedPointToFloat(): 0xffff -> 1f, otherwise value / 2^16
        assertEquals(0xFFFF, ControlMessage.floatToU16Fixed(1f))
        assertEquals(0x8000, ControlMessage.floatToU16Fixed(0.5f))
        assertEquals(0, ControlMessage.floatToU16Fixed(0f))

        // Binary.i16FixedPointToFloat(): value / 2^15
        assertEquals(0x4000, ControlMessage.floatToI16Fixed(0.5f))
        assertEquals(-0x4000, ControlMessage.floatToI16Fixed(-0.5f))
        assertEquals(0x7FFF, ControlMessage.floatToI16Fixed(1f))
    }
}
