package com.scrcpy.remote.session

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scrcpy.remote.ui.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AudioFocusTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun transientFocusReturnsButPermanentLossRequiresExplicitResume() {
        val events = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val context = compose.activity
        val manager = context.getSystemService(AudioManager::class.java)
        lateinit var focus: SessionAudioFocus
        val otherListener = AudioManager.OnAudioFocusChangeListener {}
        fun request(type: Int) = AudioFocusRequest.Builder(type)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setOnAudioFocusChangeListener(otherListener).build()
        val transient = request(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        val permanent = request(AudioManager.AUDIOFOCUS_GAIN)
        try {
            compose.runOnIdle {
                focus = SessionAudioFocus(context, { granted, lost -> events += granted to lost }, {}, {})
                assertTrue(focus.acquire())
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(transient))
            }
            compose.waitUntil(5_000) { events.contains(false to false) }
            compose.runOnIdle { manager.abandonAudioFocusRequest(transient) }
            compose.waitUntil(5_000) { events.contains(true to false) }
            compose.runOnIdle { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(permanent)) }
            compose.waitUntil(5_000) { events.contains(false to true) }
            compose.runOnIdle { manager.abandonAudioFocusRequest(permanent) }
            val count = events.size
            compose.waitForIdle()
            assertEquals(count, events.size)
            compose.runOnIdle { assertTrue(focus.acquire()) }
        } finally {
            compose.runOnIdle {
                manager.abandonAudioFocusRequest(transient)
                manager.abandonAudioFocusRequest(permanent)
                focus.close()
            }
        }
    }

    @Test fun noisyOutputMutesOnceAndCloseUnregistersTheReceiver() {
        val context = compose.activity
        val calls = AtomicInteger()
        lateinit var focus: SessionAudioFocus
        compose.runOnIdle {
            focus = SessionAudioFocus(context, { _, _ -> }, { calls.incrementAndGet() }, {})
            assertTrue(focus.acquire())
            // AUDIO_BECOMING_NOISY is a protected OS broadcast. Exercise its event entry point.
            focus.onBecomingNoisy()
        }
        try {
            compose.waitUntil(5_000) { calls.get() == 1 }
            compose.runOnIdle {
                focus.onBecomingNoisy()
                focus.close()
                focus.onBecomingNoisy()
            }
            compose.waitForIdle()
            assertEquals(1, calls.get())
        } finally {
            compose.runOnIdle { focus.close() }
        }
    }
}
