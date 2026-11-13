package com.scrcpy.remote.session

import android.os.Process

/**
 * Asks the scheduler to treat this thread as carrying live media.
 *
 * A stream arriving from the network has to be read, decoded and played within the few tens of
 * milliseconds of slack the audio buffer holds, but these threads were created at the ordinary
 * priority every background thread gets, while Android raises the app's own drawing threads well
 * above it. Under load the scheduler had no reason to prefer them, and one missed turn is enough
 * to empty the audio buffer — after which playback recovers by waiting for more sound than it
 * lost, so a momentary stall becomes a lasting gap between the picture and the sound.
 *
 * Failure is ignored on purpose: the priority is an optimisation, and a device that refuses it
 * should still mirror.
 */
internal fun raisePriority(priority: Int = Process.THREAD_PRIORITY_AUDIO) {
    // Nothing is logged on failure: this runs where the platform may be a stub that throws, and a
    // log call there would throw in turn and take the thread down with it.
    runCatching { Process.setThreadPriority(priority) }
}
