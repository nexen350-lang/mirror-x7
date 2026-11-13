package com.scrcpy.remote.session

import android.media.MediaCodec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks the offered codec lists against what this device can really decode.
 *
 * The lists decide what the settings screen shows, so an entry the device cannot decode becomes a
 * session that fails every time it is retried. Video was already filtered this way; audio was not,
 * which is how FLAC came to be offered on Android 8.0, where the decoder does not exist.
 */
@RunWith(AndroidJUnit4::class)
class SupportedCodecsTest {

    @Test fun everyOfferedVideoCodecHasAWorkingDecoder() {
        for (codec in VideoCodec.supported()) {
            assertDecodes(ScrcpyCodecs.videoMimeType(codec), codec.name)
        }
    }

    @Test fun everyOfferedAudioCodecHasAWorkingDecoder() {
        for (codec in AudioCodec.supported()) {
            // Raw PCM is played directly and needs no decoder at all.
            val mime = codec.mimeType ?: continue
            assertDecodes(mime, codec.name)
        }
    }

    @Test fun theFallbacksAreAlwaysOffered() {
        // Without these the settings screen could present nothing at all to choose from.
        assertTrue(VideoCodec.H264 in VideoCodec.supported())
        assertTrue(AudioCodec.OPUS in AudioCodec.supported())
        assertTrue(AudioCodec.RAW in AudioCodec.supported())
    }

    private fun assertDecodes(mime: String, name: String) {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw AssertionError("$name is offered but $mime has no decoder on this device", e)
        }
        codec.release()
    }
}
