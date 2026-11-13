package com.scrcpy.remote.adb

/**
 * Compares mDNS service types, which the platform does not spell consistently.
 *
 * A type is given to discovery as `_adb-tls-connect._tcp.`, reported on a *found* service without
 * the trailing dot, and reported on a *resolved* one with a leading dot as well — Android's own
 * connectivity code documents the last of these. Comparing the raw strings therefore rejects the
 * very services it went looking for, and it does so only at the last step, so discovery appears to
 * work right up to the point where it produces nothing.
 *
 * Kept apart from the discovery itself so the comparison can be checked against the strings a real
 * device produces, rather than only against the ones a fake was told to produce.
 */
internal object NsdServiceTypes {
    const val CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp."
    const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."


    /** The comparable form: no leading or trailing dots, lower case. */
    fun canonical(type: String?): String =
        type.orEmpty().trim('.').lowercase()

    /** Whether [reported] names the same service type as [expected], however it is spelled. */
    fun matches(reported: String?, expected: String): Boolean {
        val left = canonical(reported)
        return left.isNotEmpty() && left == canonical(expected)
    }
}
