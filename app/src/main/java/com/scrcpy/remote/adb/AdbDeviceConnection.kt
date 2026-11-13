package com.scrcpy.remote.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connection manager for a single remote device.
 *
 * Wraps libadb-android, which negotiates both the legacy `adb tcpip` handshake and the
 * `A_STLS` TLS upgrade used by Android 11+ wireless debugging.
 */
class AdbDeviceConnection private constructor(
    private val identity: AdbIdentity,
    private val clientName: String,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // The ordinary timeout is for the network. Once the device shows "Allow debugging?",
        // the handshake waits on a person, who needs longer than ten seconds to reach the phone.
        setUserAuthorisationTimeout(USER_AUTHORISATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = identity.newPrivateKey()

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = clientName

    /**
     * Opens an ADB stream to an abstract unix socket, e.g. `scrcpy_0a1b2c3d`.
     *
     * Bounded for the same reason [openSync] is. The caller's deadline is only consulted between
     * attempts, so each acknowledgement wait must also have a bound. The source-patched library
     * handles early replies and lets physical disconnect interrupt a pending open.
     */
    fun openLocalAbstract(name: String, timeoutMs: Long = SOCKET_OPEN_TIMEOUT_MS): AdbStream =
        DeadlineCall.within(
            timeoutMillis = timeoutMs,
            name = "adb-socket-open",
            message = "The device did not accept the $name socket within $timeoutMs ms",
            dispose = { late: AdbStream -> runCatching { late.close() } },
        ) { openStream("localabstract:$name") }

    /**
     * Opens a `sync:` stream, bounded the same way a shell open is.
     *
     * Opening a stream waits for a device acknowledgement with no deadline of its own. The local
     * library patch removes the lost-notification race, but an unresponsive device still needs a
     * bounded wait and cancellation-owned cleanup.
     */
    fun openSync(timeoutMs: Long = SYNC_TIMEOUT_MS): AdbStream = DeadlineCall.within(
        timeoutMillis = timeoutMs,
        name = "adb-sync-open",
        message = "The device did not accept a file transfer within $timeoutMs ms",
        dispose = { late: AdbStream -> runCatching { late.close() } },
    ) { openStream("sync:") }

    /** Opens a raw `shell:` stream running [command]. */
    fun openShell(command: String): AdbStream = openStream("shell:$command")

    /**
     * Runs [command] and returns everything it printed on stdout/stderr.
     *
     * Reads chunk by chunk rather than with `readBytes()`: libadb reports the end of a shell
     * stream by throwing instead of returning -1, so reading to completion would otherwise always
     * fail and discard the output that was already received.
     *
     * Both halves are bounded by [timeoutMs]. Opening the stream waits for the device to
     * acknowledge it and has no deadline of its own, so it is run under one here; the read is
     * bounded by closing the stream from a watchdog, because `AdbStream.read` blocks
     * uninterruptibly. A device that accepts the connection and then stops answering therefore
     * fails its caller instead of hanging it.
     */
    fun runShellCommand(command: String, timeoutMs: Long = SHELL_TIMEOUT_MS): String {
        require(timeoutMs > 0) { "Timeout must be positive" }
        val startedAt = System.nanoTime()
        val stream = DeadlineCall.within(
            timeoutMillis = timeoutMs,
            name = "adb-shell-open",
            message = "The device did not answer within $timeoutMs ms",
            dispose = { late: AdbStream -> runCatching { late.close() } },
        ) { openShell(command) }

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val remainingMs = (timeoutMs - elapsedMs).coerceAtLeast(1L)
        val expired = AtomicBoolean(false)
        val watchdog = Thread({
            try {
                Thread.sleep(remainingMs)
                expired.set(true)
                runCatching { stream.close() }
            } catch (_: InterruptedException) {
                // Finished in time.
            }
        }, "adb-shell-timeout").apply { isDaemon = true; start() }

        val collected = try {
            stream.openInputStream().use { readShellOutput(it, expired::get) }
        } catch (_: AdbShellTimeoutException) {
            throw SocketTimeoutException("The device did not answer within $timeoutMs ms")
        } finally {
            watchdog.interrupt()
            runCatching { stream.close() }
        }
        return collected
    }

    companion object {
        internal const val CONNECT_TIMEOUT_SECONDS = 10L
        internal const val USER_AUTHORISATION_TIMEOUT_SECONDS = 60L
        private const val SHELL_TIMEOUT_MS = 25_000L
        private const val SYNC_TIMEOUT_MS = 25_000L

        /**
         * Bound on one socket-open attempt.
         *
         * Shorter than the others because the caller retries: the point is to return control to
         * that retry loop so its own deadline can be consulted, not to wait out the device.
         */
        private const val SOCKET_OPEN_TIMEOUT_MS = 5_000L

        fun create(context: Context): AdbDeviceConnection {
            val identity = AdbIdentity.get(context)
            val name = "ScrcpyRemote-${Build.MODEL.replace(' ', '_')}"
            return AdbDeviceConnection(identity, name)
        }
    }
}
