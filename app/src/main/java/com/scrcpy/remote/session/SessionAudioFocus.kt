package com.scrcpy.remote.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Main-thread policy: transient focus loss mutes; permanent loss/noisy routes need user resume. */
internal class SessionAudioFocus(
    context: Context,
    private val onFocus: (granted: Boolean, permanent: Boolean) -> Unit,
    private val onNoisy: () -> Unit,
    private val onRouteChanged: () -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var registered = false
    private var requested = false
    private var granted = false
    private var closed = false
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (requested && !closed) {
            granted = change == AudioManager.AUDIOFOCUS_GAIN
            val permanent = change == AudioManager.AUDIOFOCUS_LOSS
            if (permanent) abandon()
            onFocus(granted, permanent)
        }
    }
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(true)
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(listener, main)
        .build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onBecomingNoisy()
        }
    }
    private var initialDevices = true
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (initialDevices) { initialDevices = false; return }
            if (requested && added.any { it.isSink }) onRouteChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (requested && removed.any { it.isSink }) onRouteChanged()
        }
    }

    fun acquire(): Boolean {
        check(!closed) { "Audio focus controller was closed" }
        if (requested) return granted
        if (!registered) {
            ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            manager.registerAudioDeviceCallback(devices, main)
            registered = true
        }
        requested = true
        granted = try {
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: SecurityException) {
            Log.w("SessionAudioFocus", "Audio focus denied", e)
            false
        }
        if (!granted) requested = false
        return granted
    }

    fun abandon() {
        if (requested) manager.abandonAudioFocusRequest(request)
        requested = false
        granted = false
    }

    fun onBecomingNoisy() {
        if (requested && !closed) {
            abandon()
            onNoisy()
        }
    }

    override fun close() {
        abandon()
        closed = true
        if (registered) {
            context.unregisterReceiver(noisy)
            manager.unregisterAudioDeviceCallback(devices)
            registered = false
        }
    }
}
