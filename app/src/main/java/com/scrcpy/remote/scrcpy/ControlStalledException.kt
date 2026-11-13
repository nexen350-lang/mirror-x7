package com.scrcpy.remote.scrcpy

import java.io.IOException

/**
 * The device stopped taking input for so long that the control queue filled up.
 *
 * Writes wait for the device to acknowledge each one, so a network that stalls stops them all;
 * reported as such so the session is recovered like any other connection that stopped answering.
 */
class ControlStalledException(message: String) : IOException(message)
