package com.scrcpy.remote.scrcpy

import java.io.IOException

/**
 * The device's own refusal to start the session.
 *
 * The server validates its options on the device — an encoder that is not installed, a display
 * that no longer exists, a codec the hardware does not provide — and when one is wrong it says so
 * and exits. All the client sees of that is its sockets closing, which is indistinguishable from
 * the network going away, so the failure was reported as an unreachable device and retried with
 * the very setting the device had just rejected. Naming it separately lets the reason the device
 * gave be shown instead, and stops the retries that could never have worked.
 */
class ScrcpyServerRefusedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
