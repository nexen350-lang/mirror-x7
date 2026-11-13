package com.scrcpy.remote.session

import com.scrcpy.remote.scrcpy.ScrcpySession

/** Reads why the device would not send sound, from what its server printed. */
internal object AudioRefusal {
    /** Android 11 starts audio capture only in the foreground: the device must be unlocked. */
    fun lockedDevice(serverOutput: String): Boolean =
        serverOutput.contains("audio capture must be started in the foreground", ignoreCase = true) ||
            serverOutput.contains("make sure that the device is unlocked", ignoreCase = true)
}

/**
 * The server reports a refused capture on its audio socket before it finishes explaining why on
 * its output, so wait briefly for that explanation to arrive.
 */
internal fun ScrcpySession.serverOutputSoon(waitMillis: Long = 1_500): String {
    val deadline = System.nanoTime() + waitMillis * 1_000_000
    var output = serverOutput()
    while (!output.contains("Failed to start audio capture\n") && !AudioRefusal.lockedDevice(output) &&
        System.nanoTime() < deadline) {
        Thread.sleep(50)
        output = serverOutput()
    }
    // A few more lines follow the failure itself.
    if (!AudioRefusal.lockedDevice(output)) {
        Thread.sleep(150)
        output = serverOutput()
    }
    return output
}
