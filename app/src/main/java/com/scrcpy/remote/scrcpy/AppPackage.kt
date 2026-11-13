package com.scrcpy.remote.scrcpy

/**
 * The application identifiers that can be carried by a `START_APP` control message.
 *
 * The message encodes its name behind a single length byte, so an over-long value cannot be sent
 * at all: serializing it throws on the control writer, which aborts the whole control transport
 * and ends the session. Android permits package names right up against that limit, and the
 * force-stop prefix costs one more byte, so callers have to check before sending rather than
 * discover it when the session dies.
 */
object AppPackage {
    /** `START_APP` writes the name length as one unsigned byte. */
    const val MAX_BYTES = 255

    private val PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

    /** Whether [packageName] is a plausible package that also fits in the control message. */
    fun isSendable(packageName: String, forceStop: Boolean = false): Boolean {
        if (!isPackageName(packageName)) return false
        val prefix = if (forceStop) 1 else 0
        return packageName.toByteArray(Charsets.UTF_8).size + prefix <= MAX_BYTES
    }

    /** Whether [name] has the form of a package name, which also keeps it inert in a shell command. */
    fun isPackageName(name: String): Boolean = PATTERN.matches(name)
}
