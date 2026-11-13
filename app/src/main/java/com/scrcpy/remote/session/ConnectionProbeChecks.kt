package com.scrcpy.remote.session

import android.content.Context
import com.scrcpy.remote.adb.AdbDeviceConnection
import com.scrcpy.remote.adb.AdbStreamChannel
import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.scrcpy.ScrcpyServerVersion
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** The real, read-only checks behind "Check connection". Blocking: run off the main thread. */
object ConnectionProbeChecks {
    private const val TCP_TIMEOUT_MS = 3_000

    fun run(context: Context, host: String, port: Int, onUpdate: (ProbeReport) -> Unit): ProbeReport {
        require(host.isNotBlank() && port in 1..65535) { "Invalid ADB address or port" }
        val app = context.applicationContext
        var adb: AdbDeviceConnection? = null
        try {
            return ConnectionProbe(
                network = {
                    Socket().use { it.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS) }
                },
                authorization = {
                    val connection = AdbDeviceConnection.create(app).also { adb = it }
                    if (!connection.connect(host, port)) {
                        throw SocketTimeoutException("The device did not finish the ADB handshake.")
                    }
                },
                server = {
                    val server = ScrcpyServerVersion.DEFAULT
                    val bytes = app.assets.open(server.assetName).use { it.readBytes() }
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(hash == server.sha256) { "Bundled scrcpy server failed integrity verification" }
                    // Read-only: a missing or different copy is fine, it is pushed on connect.
                    // What matters is that the device's sync service answers.
                    val connection = checkNotNull(adb)
                    AdbSync.stat({ AdbStreamChannel(connection.openSync()) }, server.remotePath)
                },
            ).run(onUpdate)
        } finally {
            runCatching { adb?.disconnect() }
        }
    }
}
