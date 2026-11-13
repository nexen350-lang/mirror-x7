package com.scrcpy.remote.session

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scrcpy.remote.R
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Recording with sound against a real scrcpy server, through the loopback adbd of a disposable
 * emulator. Opt in with `-e qaLoopbackAdbPort <port>`.
 *
 * Only a real server shows what its sound stream carries and when, and only a real session shows
 * the reconnect a recording with sound may need.
 */
@RunWith(AndroidJUnit4::class)
class RecordWithSoundLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    @Test fun anAacSessionRecordsPictureAndSoundStartingTogether() {
        val port = livePort()
        withModel { model ->
            connect(model, port, AudioCodec.AAC)
            assertEquals(RecordingSound.WithSound, main { model.recordingSound() })
            assertTrue(main { model.startRecording(withSound = true) })
            assertEquals(true, model.recordingStatus.value?.withSound)
            SystemClock.sleep(4_000)
            val file = finish(model)
            val tracks = Tracks.read(file)
            assertEquals(setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC), tracks.times.keys)
            val picture = tracks.times.getValue(MediaFormat.MIMETYPE_VIDEO_AVC)
            val sound = tracks.times.getValue(MediaFormat.MIMETYPE_AUDIO_AAC)
            Log.i(TAG, "AAC session: ${picture.size} pictures to ${picture.last()} us, " +
                "${sound.size} sound packets to ${sound.last()} us")
            assertTrue("Both tracks start at the opening frame", picture.first() <= 1_000 && sound.first() <= 1_000)
            assertTrue("Several seconds of sound: ${sound.last()}", sound.last() > 2_500_000)
            // A still screen sends no new pictures, so the picture track can end early; the sound
            // goes on to the end and must cover it.
            assertTrue("The sound covers the picture: ${sound.last()} vs ${picture.last()}",
                sound.last() >= picture.last() - 500_000)
            assertTrue("Every sound packet decodes", Tracks.decodeAudio(file) > 0)
            main { model.consumeFinishedRecording(keep = false) }
        }
    }

    @Test fun anOpusSessionReconnectsWithAacOnlyWhenAskedAndThenRecordsWithSound() {
        val port = livePort()
        withModel { model ->
            connect(model, port, AudioCodec.OPUS)
            assertEquals(RecordingSound.NeedsAac(AudioCodec.OPUS), main { model.recordingSound() })
            assertFalse("Nothing records before the user answers", model.recording.value)
            main { model.recordWithSoundAfterReconnect() }
            await("recording with sound after the reconnect") { model.recordingStatus.value?.takeIf { it.withSound } }
            assertEquals(AudioCodec.AAC, model.activeSettings.value?.audioCodec)
            assertEquals("The switch belongs to this session", AudioCodec.AAC, model.sessionOverrides.value.audioCodec)
            SystemClock.sleep(3_000)
            val file = finish(model)
            assertEquals(setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_AUDIO_AAC), Tracks.read(file).times.keys)
            main { model.consumeFinishedRecording(keep = false) }
        }
    }

    /**
     * Playback capture hears what apps play, not only media. scrcpy 4.1's server matched media alone,
     * so a game's sound on the device never reached the mirror; 5.0 matches every usage apps play
     * with. A media beep and then a game beep play on the device: both must be in the recording, the
     * first proving the capture works at all. The model is not on screen, so its own playback stays
     * muted and is not captured back.
     */
    @Test fun playbackCaptureHearsAGameAsWellAsMedia() {
        val port = livePort()
        assumeTrue("Playback capture needs Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        try {
            withModel { model ->
                connect(model, port, AudioCodec.AAC, AudioSource.PLAYBACK)
                // Device audio hears games too, so the test means nothing unless playback capture ran.
                assertEquals(AudioSource.PLAYBACK, model.activeSettings.value?.audioSource)
                assertTrue(main { model.startRecording(withSound = true) })
                await("the recording opens") { model.recordingStatus.value }
                SystemClock.sleep(1_500)
                instrumentation.runOnMainSync { beep(AudioAttributes.USAGE_MEDIA) }
                SystemClock.sleep(2_000)
                instrumentation.runOnMainSync { beep(AudioAttributes.USAGE_GAME) }
                SystemClock.sleep(2_000)
                val file = finish(model)
                val envelope = Tracks.envelope(file)
                val beeps = Tracks.beeps(envelope)
                Log.i(TAG, "playback capture: loudest ${envelope.maxOfOrNull { it.second }}; beeps at us $beeps")
                assertEquals("The media beep and the game beep were both captured: $beeps", 2, beeps.size)
                assertTrue("The game beep came about 2 s after the media beep: $beeps", (beeps[1] - beeps[0]) in 1_500_000..2_600_000)
                main { model.consumeFinishedRecording(keep = false) }
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }
    }

    /**
     * Without Keep audio playing on the device, playback capture takes every sound apps play: 5.0's
     * rule names alarms and notifications too, and Android limits only a capture that also plays
     * them. So they come here, and stop playing on the device, as the setting's help says.
     */
    @Test fun playbackCaptureBringsAlarmsAndNotificationsHereToo() {
        val port = livePort()
        assumeTrue("Playback capture needs Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        withLoudStreams(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM, AudioManager.STREAM_NOTIFICATION) {
            withModel { model ->
                connect(model, port, AudioCodec.AAC, AudioSource.PLAYBACK)
                assertEquals(false, model.activeSettings.value?.audioDup)
                val beeps = recordBeeps(model, AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ALARM,
                    AudioAttributes.USAGE_NOTIFICATION)
                assertEquals("The media, alarm and notification beeps were all captured: $beeps", 3, beeps.size)
            }
        }
    }

    /**
     * Keep audio playing on the device shares media and games from Android 14. Android lets a
     * capture that also plays take only media, games, voice calls and unknown sounds, whatever its
     * rule names, so an alarm stays on the device alone.
     *
     * Told apart by pitch rather than counted: on one device this capture also takes the test's own
     * playback of the mirror, before its volume, so each sound it captures comes back again every few
     * hundred milliseconds. Those echoes repeat only tones already captured, never the alarm's.
     */
    @Test fun keepingSoundOnTheDeviceSharesMediaAndGamesButNotAlarms() {
        val port = livePort()
        assumeTrue("Android 13 refuses this capture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        withLoudStreams(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM) {
            withModel { model ->
                connect(model, port, AudioCodec.AAC, AudioSource.PLAYBACK, dup = true)
                assertEquals(true, model.activeSettings.value?.audioDup)
                val levels = recordTones(model, AudioAttributes.USAGE_MEDIA to MEDIA_HZ,
                    AudioAttributes.USAGE_GAME to GAME_HZ, AudioAttributes.USAGE_ALARM to ALARM_HZ)
                assertTrue("The media tone was captured: $levels", levels.getValue(MEDIA_HZ) > 0.2)
                assertTrue("The game tone was captured: $levels", levels.getValue(GAME_HZ) > 0.2)
                assertTrue("The alarm tone stayed on the device: $levels", levels.getValue(ALARM_HZ) < 0.05)
            }
        }
    }

    /**
     * Android 13 refuses the capture that keeps sound playing on the device, which the 4.1 server
     * never asked it for. The session says why rather than only that sound failed, and hearing the
     * sound here instead, for this session, brings it back.
     */
    @Test fun android13SaysWhyThereIsNoSoundAndHearingItHereBringsItBack() {
        val port = livePort()
        assumeTrue("Only Android 13 refuses this capture", Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU)
        withLoudStreams(AudioManager.STREAM_MUSIC) {
            withModel { model ->
                main { model.connect(HOST, port, settings(AudioCodec.AAC, AudioSource.PLAYBACK, dup = true)) }
                await("connected") { model.state.value as? MirrorUiState.Connected }
                val refused = await("the sound refused") { model.audioState.value.takeIf { it.error != null } }
                assertEquals(app.getString(R.string.audio_dup_refused), refused.error)
                assertTrue(refused.dupRefused)
                main { model.hearSoundHere() }
                await("connected again") { model.state.value as? MirrorUiState.Connected }
                await("sound playing") { model.audioState.value.takeIf { it.playing } }
                assertEquals(false, model.activeSettings.value?.audioDup)
                val beeps = recordBeeps(model, AudioAttributes.USAGE_MEDIA)
                assertEquals("The media beep came here: $beeps", 1, beeps.size)
            }
        }
    }

    @Test fun withSoundOffARecordingIsThePictureOnly() {
        val port = livePort()
        withModel { model ->
            main { model.connect(HOST, port, MirrorSettings(maxSize = 640, audioEnabled = false, autoReconnect = false)) }
            await("connected") { model.state.value as? MirrorUiState.Connected }
            assertEquals(RecordingSound.PictureOnly(soundOff = true), main { model.recordingSound() })
            assertTrue(main { model.startRecording() })
            SystemClock.sleep(2_000)
            val file = finish(model)
            assertEquals(setOf(MediaFormat.MIMETYPE_VIDEO_AVC), Tracks.read(file).times.keys)
            main { model.consumeFinishedRecording(keep = false) }
        }
    }

    /**
     * The whole path, measured: the screen turns white and a beep plays at the same moment, and
     * the recording is decoded to see how far apart they ended up. Opt in with `-e qaFlashBeep 1`.
     *
     * Reports what it measures rather than asserting a tight bound: an emulator's audio stack is
     * not a phone's, so the number describes this rig and is no substitute for a physical
     * flash-and-beep measurement.
     */
    @Test fun aFlashAndABeepAtTheSameMomentEndUpCloseInTheRecording() {
        val port = livePort()
        assumeTrue("Opt in with qaFlashBeep", InstrumentationRegistry.getArguments().getString("qaFlashBeep") != null)
        val white = mutableStateOf(false)
        ActivityScenario.launch(com.scrcpy.remote.ui.MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    Box(Modifier.fillMaxSize().background(if (white.value) Color.White else Color.Black))
                }
            }
            val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            try {
                withModel { model ->
                    connect(model, port, AudioCodec.AAC)
                    assertTrue(main { model.startRecording(withSound = true) })
                    await("the recording opens") { model.recordingStatus.value }
                    SystemClock.sleep(2_000)
                    val events = listOf(0L, 2_000L, 4_000L, 6_000L, 60_000L)
                    val begin = SystemClock.elapsedRealtime()
                    for (at in events) {
                        SystemClock.sleep((begin + at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        instrumentation.runOnMainSync {
                            white.value = true
                            beep()
                        }
                        SystemClock.sleep(400)
                        instrumentation.runOnMainSync { white.value = false }
                    }
                    SystemClock.sleep(1_500)
                    val file = finish(model)
                    val flashes = Tracks.flashes(file)
                    val envelope = Tracks.envelope(file)
                    val loudest = envelope.maxOfOrNull { it.second } ?: 0
                    Log.i(TAG, "sound envelope: ${envelope.size} ms to ${envelope.lastOrNull()?.first} us; " +
                        "loudest $loudest; loudest moments ms: " +
                        envelope.sortedByDescending { it.second }.take(12).map { it.first / 1_000 to it.second })
                    val beeps = Tracks.beeps(envelope)
                    Log.i(TAG, "flashes us=$flashes beeps us=$beeps")
                    assertEquals("Every flash was recorded", events.size, flashes.size)
                    assertEquals("Every beep was recorded", events.size, beeps.size)
                    val offsets = flashes.zip(beeps) { flash, sound -> (sound - flash) / 1_000 }
                    val drift = offsets.last() - offsets.first()
                    val report = "sound minus picture ms: $offsets; drift over ${events.last() / 1_000} s: $drift ms"
                    Log.i(TAG, report)
                    assertTrue("Implausible: $report", offsets.all { abs(it) < 1_000 })
                    main { model.consumeFinishedRecording(keep = false) }
                }
            } finally {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            }
        }
    }

    private fun beep(usage: Int = AudioAttributes.USAGE_MEDIA) {
        play(usage, ShortArray(4_800) { (sin(2 * Math.PI * 1_000 * it / 48_000.0) * 26_000).toInt().toShort() })
    }

    /** 200 ms of [frequency] Hz, faded in and out so its edges add no click at other pitches. */
    private fun tone(usage: Int, frequency: Int) {
        val count = 9_600
        val fade = 480.0
        play(usage, ShortArray(count) {
            val gain = minOf(1.0, it / fade, (count - 1 - it) / fade)
            (sin(2 * Math.PI * frequency * it / 48_000.0) * 26_000 * gain).toInt().toShort()
        })
    }

    private fun play(usage: Int, samples: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        track.write(samples, 0, samples.size)
        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) { t.release() }
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }

    private fun connect(
        model: MirrorViewModel,
        port: Int,
        codec: AudioCodec,
        source: AudioSource = AudioSource.OUTPUT,
        dup: Boolean = false,
        server: ScrcpyServerVersion = ScrcpyServerVersion.DEFAULT,
    ) {
        main { model.connect(HOST, port, settings(codec, source, dup, server)) }
        await("connected") { model.state.value as? MirrorUiState.Connected }
        await("sound playing") { model.audioState.value.takeIf { it.playing } }
        assertNull(model.audioState.value.error)
    }

    private fun settings(
        codec: AudioCodec,
        source: AudioSource,
        dup: Boolean = false,
        server: ScrcpyServerVersion = ScrcpyServerVersion.DEFAULT,
    ) = MirrorSettings(maxSize = 640, audioEnabled = true, audioCodec = codec, audioSource = source,
        audioDup = dup, backgroundAudio = false, autoReconnect = false, serverVersion = server)

    /** Runs [block] with [streams] at full volume, so each beep is as loud as the others. */
    private fun withLoudStreams(vararg streams: Int, block: () -> Unit) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumes = streams.associateWith { audio.getStreamVolume(it) }
        try {
            for (stream in streams) audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0)
            block()
        } finally {
            for ((stream, volume) in volumes) audio.setStreamVolume(stream, volume, 0)
        }
    }

    /** Records with sound while a beep plays for each of [usages], 2 s apart, and finds them in it. */
    private fun recordBeeps(model: MirrorViewModel, vararg usages: Int): List<Long> {
        assertTrue(main { model.startRecording(withSound = true) })
        await("the recording opens") { model.recordingStatus.value }
        SystemClock.sleep(1_500)
        for (usage in usages) {
            instrumentation.runOnMainSync { beep(usage) }
            SystemClock.sleep(2_000)
        }
        val file = finish(model)
        val envelope = Tracks.envelope(file)
        val beeps = Tracks.beeps(envelope)
        Log.i(TAG, "usages ${usages.toList()}: loudest ${envelope.maxOfOrNull { it.second }}; beeps at us $beeps")
        main { model.consumeFinishedRecording(keep = false) }
        return beeps
    }

    /** Records with sound while a tone plays for each usage, 2 s apart, and measures each pitch in it. */
    private fun recordTones(model: MirrorViewModel, vararg tones: Pair<Int, Int>): Map<Int, Double> {
        assertTrue(main { model.startRecording(withSound = true) })
        await("the recording opens") { model.recordingStatus.value }
        SystemClock.sleep(1_500)
        for ((usage, frequency) in tones) {
            instrumentation.runOnMainSync { tone(usage, frequency) }
            SystemClock.sleep(2_000)
        }
        val file = finish(model)
        val levels = Tracks.toneLevels(file, tones.map { it.second })
        Log.i(TAG, "tones ${tones.toList()}: levels $levels")
        main { model.consumeFinishedRecording(keep = false) }
        return levels
    }

    private fun finish(model: MirrorViewModel): File {
        main { model.stopRecording() }
        return await("the recording is offered") { model.finishedRecording.value }
    }

    private fun livePort(): Int {
        val text = InstrumentationRegistry.getArguments().getString("qaLoopbackAdbPort")
        assumeTrue("Opt in with qaLoopbackAdbPort on a disposable emulator with loopback adbd enabled", text != null)
        return requireNotNull(text).toInt().also { require(it in 1..65535) }
    }

    private fun withModel(block: (MirrorViewModel) -> Unit) {
        val store = ViewModelStore()
        lateinit var model: MirrorViewModel
        instrumentation.runOnMainSync {
            model = MirrorViewModel(app)
            store.put("record-sound-live-test", model)
        }
        try {
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> await(description: String, read: () -> T?): T {
        val until = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < until) {
            read()?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("$description: timed out")
    }

    /** Reads a recording back the way a player does. */
    private object Tracks {
        class Read(val times: Map<String, List<Long>>)

        fun read(file: File): Read {
            val count = MediaExtractor().run {
                try { setDataSource(file.absolutePath); trackCount } finally { release() }
            }
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            return Read((0 until count).associate { index ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    extractor.selectTrack(index)
                    val times = mutableListOf<Long>()
                    while (extractor.readSampleData(buffer, 0) >= 0) {
                        times += extractor.sampleTime
                        extractor.advance()
                    }
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)!! to times
                } finally {
                    extractor.release()
                }
            })
        }

        /** Whether the sound decodes, and how many decoded frames it gives. */
        fun decodeAudio(file: File): Long {
            var frames = 0L
            decode(file, "audio/") { info, _, format ->
                frames += info.size / 2 / format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            return frames
        }

        /** The loudest sample in each millisecond of sound, by time in microseconds. */
        fun envelope(file: File): List<Pair<Long, Int>> {
            val out = ArrayList<Pair<Long, Int>>()
            var windowStart = Long.MIN_VALUE
            var peak = 0
            decode(file, "audio/") { info, buffer, format ->
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                for (frame in 0 until info.size / 2 / channels) {
                    val time = info.presentationTimeUs + frame * 1_000_000L / rate
                    val window = time / 1_000 * 1_000
                    if (window != windowStart) {
                        if (windowStart != Long.MIN_VALUE) out += windowStart to peak
                        windowStart = window
                        peak = 0
                    }
                    peak = maxOf(peak, abs(shorts.get(frame * channels).toInt()))
                }
            }
            if (windowStart != Long.MIN_VALUE) out += windowStart to peak
            return out
        }

        /** When each beep starts: at least half the loudest level, after 300 ms below it. */
        fun beeps(envelope: List<Pair<Long, Int>>): List<Long> {
            val threshold = maxOf(200, (envelope.maxOfOrNull { it.second } ?: 0) / 2)
            val onsets = mutableListOf<Long>()
            var lastLoud = Long.MIN_VALUE / 2
            for ((time, peak) in envelope) {
                if (peak >= threshold) {
                    if (time - lastLoud > 300_000) onsets += time
                    lastLoud = time
                }
            }
            return onsets
        }

        /**
         * The strongest level of each of [frequencies] Hz in any 40 ms of the sound, as a fraction
         * of full scale: a Goertzel filter over Hann-windowed blocks, half a block apart.
         */
        fun toneLevels(file: File, frequencies: List<Int>): Map<Int, Double> {
            var samples = ShortArray(1 shl 16)
            var count = 0
            var rate = 48_000
            decode(file, "audio/") { info, buffer, format ->
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                for (frame in 0 until info.size / 2 / channels) {
                    if (count == samples.size) samples = samples.copyOf(count * 2)
                    samples[count++] = shorts.get(frame * channels)
                }
            }
            val size = rate / 25
            val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
            val windowSum = window.sum()
            return frequencies.associateWith { frequency ->
                val coefficient = 2 * cos(2 * PI * frequency / rate)
                var strongest = 0.0
                var start = 0
                while (start + size <= count) {
                    var previous = 0.0
                    var beforePrevious = 0.0
                    for (i in 0 until size) {
                        val next = samples[start + i] * window[i] + coefficient * previous - beforePrevious
                        beforePrevious = previous
                        previous = next
                    }
                    val power = previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious
                    // A sine of amplitude A gives a magnitude of A times half the window's sum at its pitch.
                    strongest = maxOf(strongest, 2 * sqrt(maxOf(power, 0.0)) / windowSum / 32_768.0)
                    start += size / 2
                }
                strongest
            }
        }

        /** When the picture turns white: the first bright frame after a dark one. */
        fun flashes(file: File): List<Long> {
            val onsets = mutableListOf<Long>()
            var bright = false
            decode(file, "video/") { info, _, _ ->
                val level = info.offset
                if (level > 128 && !bright) onsets += info.presentationTimeUs
                bright = level > 128
            }
            return onsets
        }

        /**
         * Decodes the first track of [prefix]; for video the callback's info.offset carries the
         * mean brightness of the picture's centre, read from the decoded image.
         */
        private fun decode(file: File, prefix: String, onOutput: (MediaCodec.BufferInfo, ByteBuffer, MediaFormat) -> Unit) {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix)
            }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            try {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!inputDone) {
                        val input = codec.dequeueInputBuffer(10_000)
                        if (input >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(input)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val output = codec.dequeueOutputBuffer(info, 10_000)
                    if (output < 0) continue
                    if (info.size > 0) {
                        if (prefix == "video/") {
                            val image = codec.getOutputImage(output)!!
                            val plane = image.planes[0]
                            var sum = 0L
                            var n = 0
                            for (y in image.height / 3 until image.height * 2 / 3 step 4) {
                                for (x in image.width / 3 until image.width * 2 / 3 step 4) {
                                    sum += plane.buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                                    n++
                                }
                            }
                            image.close()
                            val brightness = MediaCodec.BufferInfo().apply {
                                set((sum / n).toInt(), info.size, info.presentationTimeUs, info.flags)
                            }
                            onOutput(brightness, ByteBuffer.allocate(0), codec.outputFormat)
                        } else {
                            val buffer = codec.getOutputBuffer(output)!!
                            buffer.position(info.offset).limit(info.offset + info.size)
                            onOutput(info, buffer.slice(), codec.outputFormat)
                        }
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
                extractor.release()
            }
        }
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val TAG = "RecordWithSoundLive"
        // Pitches on whole 25 Hz analysis bins, far enough apart that one never registers as another.
        const val MEDIA_HZ = 700
        const val GAME_HZ = 1_100
        const val ALARM_HZ = 1_700
    }
}
