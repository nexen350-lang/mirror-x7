package com.scrcpy.remote.scrcpy

import android.content.Context
import android.util.Log
import com.scrcpy.remote.R
import com.scrcpy.remote.adb.AdbDeviceConnection
import com.scrcpy.remote.adb.AdbStreamChannel
import com.scrcpy.remote.adb.AdbSync
import com.scrcpy.remote.adb.SyncChannel
import com.scrcpy.remote.session.CheckupStage
import com.scrcpy.remote.session.StartupResources
import io.github.muntashirakon.adb.AdbStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * A live scrcpy session: the ADB connection, the running server process and the video, audio
 * and control sockets multiplexed over it.
 */
class ScrcpySession private constructor(
    private val appContext: Context,
    private val adb: AdbDeviceConnection,
    private val shellStream: AdbStream,
    private val sockets: List<AdbStream>,
    private val audioSocket: AdbStream?,
    val deviceName: String,
    val videoReader: ScrcpyStreamReader?,
    val audioReader: ScrcpyStreamReader?,
    val controller: ScrcpyController?,
    val deviceMessageReader: DeviceMessageReader?,
    /** The bundled server this session runs; its listings go through the same one. */
    val server: ScrcpyServerVersion,
    /**
     * The device's Android API level, read only when the session asks for the capture Android 13
     * refuses ([PlaybackDuplication]); null otherwise, or when it could not be read.
     */
    val targetSdk: Int? = null,
    /** What the server has printed so far; read to explain a refusal in the server's own words. */
    private val serverLog: StringBuilder? = null,
) : AutoCloseable {

    /** The server's output so far, or empty. */
    fun serverOutput(): String = serverLog?.let { synchronized(it) { it.toString() } }.orEmpty()

    private val closed = AtomicBoolean(false)
    private val serverDeployment = ServerDeployment(server.remotePath, server.sha256)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            if (controller?.closeGracefully(CONTROL_FLUSH_TIMEOUT_MS) == false) {
                Log.w(TAG, "Control drain was not confirmed before shutdown")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Control channel could not be drained", e)
        } finally {
            try { controller?.stop() } catch (e: Throwable) { Log.w(TAG, "Control stop failed", e) }
            closeTransportResources(sockets, shellStream, Closeable { adb.disconnect() })
        }
    }

    private val audioClosed = AtomicBoolean(false)

    fun disableAudio() {
        if (!audioClosed.compareAndSet(false, true)) return
        audioSocket?.let(::closeResource)
    }

    /**
     * Asks the device for its launchable applications.
     *
     * Runs the server in its list-only mode on a separate shell, which is what `scrcpy
     * --list-apps` does. This is the only way to obtain app *names*: ADB can enumerate package
     * names, but the labels live in the device's PackageManager.
     *
     * The jar has to be deployed again first: a live session's server unlinks its own jar during
     * startup, so by the time a session exists the deployed copy is already gone.
     */
    @Throws(IOException::class)
    fun listApps(): List<DeviceApp> {
        deployServer(appContext, server, deployment = serverDeployment) { AdbStreamChannel(adb.openSync()) }
        return DeviceAppListParser.parseServerOutput(
            adb.runShellCommand(listAppsCommand(server))
        )
    }

    /**
     * Asks the device which video encoders it has.
     *
     * Only the device can answer this: the names are whatever its own MediaCodec list reports,
     * and they differ between manufacturers. Same one-shot listing mode, and the same redeploy,
     * as [listApps].
     */
    @Throws(IOException::class)
    fun listVideoEncoders(): List<DeviceEncoder> {
        deployServer(appContext, server, deployment = serverDeployment) { AdbStreamChannel(adb.openSync()) }
        return DeviceEncoderListParser.parseServerOutput(
            adb.runShellCommand(listEncodersCommand(server))
        )
    }

    /**
     * Audio encoders the device reports.
     *
     * The server prints video and audio encoders in the same output, so this runs the same
     * command as [listVideoEncoders] and keeps only the audio entries.
     */
    @Throws(IOException::class)
    fun listAudioEncoders(): List<DeviceEncoder> {
        deployServer(appContext, server, deployment = serverDeployment) { AdbStreamChannel(adb.openSync()) }
        return DeviceEncoderListParser.parseServerOutput(
            adb.runShellCommand(listEncodersCommand(server)),
            type = "audio",
        )
    }

    /**
     * Displays the device reports.
     *
     * Only the device can answer this: which displays exist, and their ids, depend on what is
     * attached to it right now. Same one-shot listing mode, and the same redeploy, as [listApps].
     */
    @Throws(IOException::class)
    fun listDisplays(): List<DeviceDisplay> {
        deployServer(appContext, server, deployment = serverDeployment) { AdbStreamChannel(adb.openSync()) }
        return DeviceDisplayListParser.parseServerOutput(
            adb.runShellCommand(listDisplaysCommand(server))
        )
    }

    /**
     * Cameras the device reports.
     *
     * Only the device can answer this, and only from Android 12: older ones refuse camera capture
     * outright, which the parser passes through as the reason rather than as an empty list.
     */
    @Throws(IOException::class)
    fun listCameras(): List<DeviceCamera> {
        deployServer(appContext, server, deployment = serverDeployment) { AdbStreamChannel(adb.openSync()) }
        return DeviceCameraListParser.parseServerOutput(
            adb.runShellCommand(listCamerasCommand(server))
        )
    }

    /**
     * Sends [size] bytes from [source] to the device's Download folder as [displayName].
     *
     * Returns the path the file was written to. The media scan afterwards is what makes it
     * appear in the device's Files and gallery apps; without it the file exists but is invisible
     * to everything that reads MediaStore.
     *
     * [onChannelOpen] registers each preflight/push channel; [onChannelClosed] releases that same
     * registration after its underlying close, including late opens after cancellation.
     */
    @Throws(IOException::class)
    fun sendFile(
        source: InputStream,
        size: Long,
        displayName: String,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onChannelOpen: (Closeable) -> Unit = {},
        onChannelClosed: (Closeable) -> Unit = {},
        onCommit: () -> Unit = {},
    ): SentFile {
        val remotePath = pushFile(
            openSync = { AdbStreamChannel(adb.openSync()) },
            source = source,
            size = size,
            displayName = displayName,
            onProgress = onProgress,
            isCancelled = isCancelled,
            onChannelOpen = onChannelOpen,
            onChannelClosed = onChannelClosed,
            onCommit = onCommit,
        )
        // Best effort: the file has already transferred, so a controller that cannot accept the
        // scan request must not turn a successful transfer into a failure. The caller is told
        // either way, because a file that exists but never appears in Files looks to the user
        // exactly like one that never arrived.
        val scanRequested = controller?.send(ControlMessage.ScanFile(remotePath)) ?: false
        return SentFile(remotePath, scanRequested)
    }

    /**
     * Where a transferred file landed, and whether the media-scan request was accepted into the
     * control queue. This does not prove the device flushed it or indexed the file.
     */
    data class SentFile(val remotePath: String, val scanRequested: Boolean)

    /**
     * Installs the app package in [source] on the device and returns what its package manager
     * said, for [com.scrcpy.remote.session.ApkInstall.parse]. See [installPackage].
     */
    @Throws(IOException::class)
    fun installApp(
        source: InputStream,
        size: Long,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onChannelOpen: (Closeable) -> Unit = {},
        onChannelClosed: (Closeable) -> Unit = {},
        onInstalling: () -> Unit = {},
    ): String = installPackage(
        openSync = { AdbStreamChannel(adb.openSync()) },
        runShell = { command, timeoutMs -> adb.runShellCommand(command, timeoutMs) },
        source = source,
        size = size,
        onProgress = onProgress,
        isCancelled = isCancelled,
        onChannelOpen = onChannelOpen,
        onChannelClosed = onChannelClosed,
        onInstalling = onInstalling,
    )

    /**
     * Installs an app bundle's [parts] together in one install session on the device and returns
     * what its package manager said about them, for [com.scrcpy.remote.session.ApkInstall.parse].
     * See [installPackages].
     */
    @Throws(IOException::class)
    fun installParts(
        parts: List<UploadPart>,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onChannelOpen: (Closeable) -> Unit = {},
        onChannelClosed: (Closeable) -> Unit = {},
        onInstalling: () -> Unit = {},
    ): String = installPackages(
        openSync = { AdbStreamChannel(adb.openSync()) },
        runShell = { command, timeoutMs -> adb.runShellCommand(command, timeoutMs) },
        parts = parts,
        onProgress = onProgress,
        isCancelled = isCancelled,
        onChannelOpen = onChannelOpen,
        onChannelClosed = onChannelClosed,
        onInstalling = onInstalling,
    )

    /** What the device says decides which of an app bundle's files it needs; see [com.scrcpy.remote.session.DeviceSpecs]. */
    @Throws(IOException::class)
    fun describeDevice(): String = adb.runShellCommand(com.scrcpy.remote.session.DeviceSpecs.COMMAND)

    /** One file of an app bundle on its way to the device: its size, and how to read it. */
    class UploadPart(val size: Long, val open: () -> InputStream)

    /**
     * Runs a [com.scrcpy.remote.session.DeviceAppPages] command, which opens one of the device's
     * own pages about an app or asks whether it is still installed, and returns what it printed.
     */
    @Throws(IOException::class)
    fun runAppCommand(command: String): String = adb.runShellCommand(command)

    companion object {
        private const val TAG = "ScrcpySession"
        private const val DEVICE_NAME_FIELD_LENGTH = 64
        private const val SOCKET_RETRY_DELAY_MS = 25L
        private const val FAST_SHA256_TIMEOUT_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 15_000L
        /** How long a failed start waits for a server that is exiting to finish saying why. */
        private const val SERVER_EXIT_GRACE_MS = 1_000L
        /** Reading the device's Android version, done only for the capture Android 13 refuses. */
        private const val SDK_QUERY_TIMEOUT_MS = 5_000L
        /** Marks the part of a failure message that came from the device, not from here. */
        const val SERVER_OUTPUT_HEADING = "Server output:"
        private const val CONTROL_FLUSH_TIMEOUT_MS = 300L

        /** Logical stream close shares the blocked writer's lock, so release socket I/O first. */
        internal fun closeTransportResources(
            sockets: List<Closeable>,
            shellStream: Closeable,
            connection: Closeable,
        ) {
            closeResource(connection)
            for (socket in sockets) closeResource(socket)
            closeResource(shellStream)
        }

        private fun closeResource(resource: Closeable) {
            try { resource.close() }
            catch (e: Throwable) {
                // Logging must never stop later resources from closing (Log is unmocked on the JVM).
                try { Log.w(TAG, "Closing session resource failed", e) } catch (_: Throwable) {}
            }
        }

        /**
         * Command that lists the device's apps and exits.
         *
         * `cleanup=false` is required: the server deletes its own jar on startup when cleanup is
         * enabled, which would silently remove the deployed server that live sessions reuse.
         */
        internal fun listAppsCommand(server: ScrcpyServerVersion): String =
            listCommand(server, "list_apps=true")

        /** Command that lists the device's encoders and exits. Same cleanup rule as above. */
        internal fun listEncodersCommand(server: ScrcpyServerVersion): String =
            listCommand(server, "list_encoders=true")

        /** Command that lists the device's displays and exits. Same cleanup rule as above. */
        internal fun listDisplaysCommand(server: ScrcpyServerVersion): String =
            listCommand(server, "list_displays=true")

        /**
         * Command that lists the device's cameras *and the sizes each one offers*, then exits.
         * Same cleanup rule as above.
         *
         * `list_camera_sizes` is a superset of `list_cameras`: it prints the same
         * `List of cameras:` header and camera lines, and adds an indented size line under each.
         * Asking for the larger listing costs one round trip instead of two, and a camera picker
         * that cannot offer sizes is the gap this closes.
         */
        internal fun listCamerasCommand(server: ScrcpyServerVersion): String =
            listCommand(server, "list_camera_sizes=true")

        /** Runs [server]'s own jar under its own version: the server refuses any other version. */
        private fun listCommand(server: ScrcpyServerVersion, listOption: String): String =
            (listOf("CLASSPATH=${ScrcpyOptions.shellQuote(server.remotePath)}", "app_process", "/",
                "com.genymobile.scrcpy.Server", server.version,
                listOption, "cleanup=false", "log_level=info")).joinToString(" ")

        /**
         * Copies an app package to [remotePath], installs it with the device's package manager and
         * returns what that printed, for [com.scrcpy.remote.session.ApkInstall.parse].
         *
         * The copy is removed afterwards whatever happened, including a cancelled or refused copy
         * that may have left part of a file behind; copies an earlier dropped connection stranded
         * are swept first. A removal that fails is reported to [onCleanupFailed] rather than
         * thrown: by then the install's own outcome is the thing worth telling the user.
         * [onInstalling] runs once the whole package is on the device, as the install starts.
         */
        internal fun installPackage(
            openSync: () -> SyncChannel,
            runShell: (String, Long) -> String,
            source: InputStream,
            size: Long,
            remotePath: String = com.scrcpy.remote.session.ApkInstall.newRemotePath(),
            onProgress: (Long) -> Unit = {},
            isCancelled: () -> Boolean = { false },
            onChannelOpen: (Closeable) -> Unit = {},
            onChannelClosed: (Closeable) -> Unit = {},
            onInstalling: () -> Unit = {},
            onCleanupFailed: (Exception) -> Unit = { error ->
                Log.w(TAG, "Could not remove the app package copied for installing", error)
            },
        ): String {
            fun checkCancelled() {
                if (isCancelled()) throw InterruptedIOException("Install cancelled")
            }
            var installTimedOut = false
            checkCancelled()
            // Best effort: an old device may have no find, and a stranded copy is only space.
            try {
                runShell(com.scrcpy.remote.session.ApkInstall.sweepCommand(), INSTALL_CLEANUP_TIMEOUT_MS)
            } catch (error: Exception) {
                onCleanupFailed(error)
            }
            var copyStarted = false
            try {
                AdbSync.push(
                    openChannel = {
                        checkCancelled()
                        copyStarted = true
                        val channel = TrackedSyncChannel(openSync(), onChannelClosed)
                        try {
                            onChannelOpen(channel)
                            checkCancelled()
                            channel
                        } catch (error: Throwable) {
                            try { channel.close() } catch (closeError: Throwable) {
                                if (closeError !== error) error.addSuppressed(closeError)
                            }
                            throw error
                        }
                    },
                    source = source,
                    size = size,
                    remotePath = remotePath,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                checkCancelled()
                onInstalling()
                return try {
                    runShell(com.scrcpy.remote.session.ApkInstall.installCommand(remotePath), INSTALL_TIMEOUT_MS)
                } catch (error: java.net.SocketTimeoutException) {
                    // pm may still be reading the file; the stranded-copy sweep removes it later.
                    installTimedOut = true
                    throw error
                }
            } finally {
                if (copyStarted && !installTimedOut) {
                    try {
                        runShell(com.scrcpy.remote.session.ApkInstall.removeCommand(remotePath), INSTALL_CLEANUP_TIMEOUT_MS)
                    } catch (error: Exception) {
                        onCleanupFailed(error)
                    }
                }
            }
        }

        /**
         * Copies an app bundle's [parts] to the device, installs them together in one session of
         * its package manager and returns what that printed about them.
         *
         * Every file is on the device before the session opens, so a dropped connection cannot
         * leave one half-written. A session that cannot be committed is abandoned; one whose
         * commit was sent is not, because it may well have finished, and the device refuses to
         * abandon a committed session anyway. The copies are removed whatever happened, as a
         * single app's copy is; [onProgress] counts the whole bundle.
         */
        internal fun installPackages(
            openSync: () -> SyncChannel,
            runShell: (String, Long) -> String,
            parts: List<UploadPart>,
            token: String = com.scrcpy.remote.session.ApkInstall.newToken(),
            onProgress: (Long) -> Unit = {},
            isCancelled: () -> Boolean = { false },
            onChannelOpen: (Closeable) -> Unit = {},
            onChannelClosed: (Closeable) -> Unit = {},
            onInstalling: () -> Unit = {},
            onCleanupFailed: (Exception) -> Unit = { error ->
                Log.w(TAG, "Could not remove the app bundle's files copied for installing", error)
            },
        ): String {
            val install = com.scrcpy.remote.session.ApkInstall
            fun checkCancelled() {
                if (isCancelled()) throw InterruptedIOException("Install cancelled")
            }
            require(parts.isNotEmpty()) { "An app has at least one file" }
            checkCancelled()
            try {
                runShell(install.sweepCommand(), INSTALL_CLEANUP_TIMEOUT_MS)
            } catch (error: Exception) {
                onCleanupFailed(error)
            }
            var copyStarted = false
            try {
                var sent = 0L
                parts.forEachIndexed { index, part ->
                    val before = sent
                    part.open().use { source ->
                        AdbSync.push(
                            openChannel = {
                                checkCancelled()
                                copyStarted = true
                                val channel = TrackedSyncChannel(openSync(), onChannelClosed)
                                try {
                                    onChannelOpen(channel)
                                    checkCancelled()
                                    channel
                                } catch (error: Throwable) {
                                    try { channel.close() } catch (closeError: Throwable) {
                                        if (closeError !== error) error.addSuppressed(closeError)
                                    }
                                    throw error
                                }
                            },
                            source = source,
                            size = part.size,
                            remotePath = install.partPath(token, index),
                            onProgress = { onProgress(before + it) },
                            isCancelled = isCancelled,
                        )
                    }
                    sent += part.size
                }
                checkCancelled()
                onInstalling()
                val created = runShell(install.createSessionCommand(sent), INSTALL_STEP_TIMEOUT_MS)
                val session = install.sessionId(created) ?: return created
                var committing = false
                try {
                    parts.forEachIndexed { index, part ->
                        val written = runShell(install.writeCommand(session, index, part.size, install.partPath(token, index)),
                            INSTALL_STEP_TIMEOUT_MS)
                        if (!install.wrote(written)) {
                            abandon(runShell, session, onCleanupFailed)
                            return written
                        }
                    }
                    committing = true
                    return runShell(install.commitCommand(session), INSTALL_TIMEOUT_MS)
                } catch (error: Exception) {
                    if (!committing) abandon(runShell, session, onCleanupFailed)
                    throw error
                }
            } finally {
                if (copyStarted) {
                    try {
                        runShell(install.removePartsCommand(token), INSTALL_CLEANUP_TIMEOUT_MS)
                    } catch (error: Exception) {
                        onCleanupFailed(error)
                    }
                }
            }
        }

        private fun abandon(runShell: (String, Long) -> String, session: Int, onCleanupFailed: (Exception) -> Unit) {
            try {
                runShell(com.scrcpy.remote.session.ApkInstall.abandonCommand(session), INSTALL_CLEANUP_TIMEOUT_MS)
            } catch (error: Exception) {
                onCleanupFailed(error)
            }
        }

        /** Opening a session or writing one file into it: seconds normally, never the minutes a commit can take. */
        private const val INSTALL_STEP_TIMEOUT_MS = 2L * 60 * 1000

        /** Installing a large app, including its compilation, can take minutes on a slow device. */
        private const val INSTALL_TIMEOUT_MS = 5L * 60 * 1000
        private const val INSTALL_CLEANUP_TIMEOUT_MS = 10_000L

        internal fun pushFile(
            openSync: () -> SyncChannel,
            source: InputStream,
            size: Long,
            displayName: String,
            onProgress: (Long) -> Unit = {},
            isCancelled: () -> Boolean = { false },
            onChannelOpen: (Closeable) -> Unit = {},
            onChannelClosed: (Closeable) -> Unit = {},
            onCommit: () -> Unit = {},
        ): String {
            fun checkCancelled() {
                if (isCancelled()) throw InterruptedIOException("Transfer cancelled")
            }

            fun openTrackedSync(): SyncChannel {
                checkCancelled()
                val channel = TrackedSyncChannel(openSync(), onChannelClosed)
                try {
                    onChannelOpen(channel)
                    checkCancelled()
                    return channel
                } catch (error: Throwable) {
                    try { channel.close() } catch (closeError: Throwable) {
                        if (closeError !== error) error.addSuppressed(closeError)
                    }
                    throw error
                }
            }

            // A name of its own, rather than the one it would replace. adbd empties the destination
            // the moment a transfer starts and removes it again if that transfer fails, so sending
            // straight to a name already in use meant a cancelled or interrupted transfer destroyed
            // the file that was there — leaving neither the old one nor the new. Sending two files
            // that happen to share a name now leaves both, which is also what a browser does.
            val remotePath = RemoteFileName.freePath(displayName) { candidate ->
                checkCancelled()
                val exists = AdbSync.exists(::openTrackedSync, candidate)
                checkCancelled()
                exists
            }
            AdbSync.push(
                openChannel = ::openTrackedSync,
                source = source,
                size = size,
                remotePath = remotePath,
                onProgress = onProgress,
                isCancelled = isCancelled,
                onCommit = onCommit,
            )
            return remotePath
        }

        /**
         * Pushes [server] to its own path on the device unless a byte-identical copy is already
         * there, verifying its registered digest before and after the transfer.
         *
         * [onPush] runs only when a transfer is actually needed.
         */
        private val bundledServerCache = ConcurrentHashMap<ScrcpyServerVersion, ByteArray>()

        @Throws(IOException::class)
        private fun loadVerifiedServerBytes(context: Context, server: ScrcpyServerVersion): ByteArray =
            bundledServerCache[server] ?: context.assets.open(server.assetName).use { it.readBytes() }.also { bytes ->
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                check(digest == server.sha256) { "Bundled scrcpy server failed integrity verification" }
                bundledServerCache.putIfAbsent(server, bytes)
            }

        internal fun parseSha256SumOutput(output: String, expectedPath: String): String? {
            val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
            val match = SHA256_OUTPUT_REGEX.matchEntire(line) ?: return null
            val digest = match.groupValues[1].lowercase(Locale.ROOT)
            val path = match.groupValues[2].trim()
            if (path.isNotEmpty() && path != expectedPath) return null
            return digest
        }

        private val SAFE_REMOTE_PATH = Regex("[A-Za-z0-9._/-]+")
        private val SHA256_OUTPUT_REGEX = Regex("^([0-9a-fA-F]{64})\\s+(.*)$")

        @Throws(IOException::class)
        private fun deployServer(
            context: Context,
            server: ScrcpyServerVersion,
            onPush: () -> Unit = {},
            deployment: ServerDeployment = ServerDeployment(server.remotePath, server.sha256),
            remoteSha256: (() -> String?)? = null,
            openSync: () -> SyncChannel,
        ): Boolean {
            val serverBytes = loadVerifiedServerBytes(context, server)
            val pushed = deployment.ensureDeployed(serverBytes, openSync, remoteSha256, onPush)
            if (!pushed) {
                Log.i(TAG, "Server already deployed, skipping push")
            }
            return pushed
        }

        /**
         * Connects to [host]:[port], deploys and starts scrcpy-server, then opens the video,
         * audio and control sockets.
         *
         * Must be called from a background thread.
         */
        @Throws(IOException::class, InterruptedException::class)
        fun start(
            context: Context,
            host: String,
            port: Int,
            options: ScrcpyOptions,
            onStatus: (String) -> Unit = {},
            startup: StartupResources = StartupResources(),
            onControlFailure: (Throwable) -> Unit = {},
            onTouchWritten: (ControlMessage.InjectTouchEvent) -> Unit = {},
            onStage: (CheckupStage) -> Unit = {},
            onUserAuthorisation: () -> Unit = {},
        ): ScrcpySession {
            require(host.isNotBlank() && port in 1..65535) { "Invalid ADB address or port" }
            val adb = AdbDeviceConnection.create(context)
            startup.trackTransport(Closeable { adb.disconnect() })
            val sockets = mutableListOf<AdbStream>()
            // Held outside the attempt so a failure anywhere after the server starts can still be
            // reported with what the server said about it.
            var serverLog: StringBuilder? = null
            var logReader: Thread? = null
            val startNs = System.nanoTime()
            try {
                startup.checkActive()
                onStatus(context.getString(R.string.status_connecting, "$host:$port"))
                // Said as soon as the device asks, not after a timeout: the user is looking at
                // this screen, and the prompt is on the other one.
                adb.setUserAuthorisationListener {
                    onUserAuthorisation()
                    onStage(CheckupStage.AUTHORIZE)
                    onStatus(context.getString(R.string.status_accept_on_device, host))
                }
                val handshake = try {
                    adb.connect(host, port)
                } finally {
                    // The library creates its connection object only once TCP has connected, so
                    // its presence is observed proof the address was reached, even when the
                    // handshake that follows fails.
                    if (adb.adbConnection != null) onStage(CheckupStage.AUTHORIZE)
                }
                if (!handshake) {
                    throw java.net.SocketTimeoutException("The device did not finish the ADB handshake.")
                }
                val afterConnectNs = System.nanoTime()

                onStage(CheckupStage.DEPLOY)
                onStatus(context.getString(R.string.status_preparing_server))
                fun sync() = AdbStreamChannel(startup.track(adb.openSync()))
                val remotePath = options.server.remotePath
                val pushed = deployServer(
                    context = context,
                    server = options.server,
                    onPush = { onStatus(context.getString(R.string.status_pushing_server)) },
                    remoteSha256 = {
                        if (!SAFE_REMOTE_PATH.matches(remotePath)) null
                        else try {
                            parseSha256SumOutput(
                                adb.runShellCommand("sha256sum $remotePath", FAST_SHA256_TIMEOUT_MS),
                                remotePath,
                            )
                        } catch (_: IOException) {
                            null
                        }
                    },
                    openSync = ::sync,
                )
                val afterDeployNs = System.nanoTime()

                startup.checkActive()
                onStage(CheckupStage.START)
                onStatus(context.getString(R.string.status_starting_server))
                val targetSdk = if (PlaybackDuplication.needsTargetSdk(options)) readTargetSdk(adb) else null
                val command = options.toShellCommand(options.server.remotePath)
                Log.i(TAG, "Server command: $command")
                val stream = startup.track(adb.openShell(command))
                val log = StringBuilder()
                serverLog = log
                logReader = startLogReader(stream.openInputStream(), log)
                val afterShellNs = System.nanoTime()

                onStatus(context.getString(R.string.status_waiting_sockets))

                // The server accepts video, audio and control (in that order) and only sends
                // the device metadata once every enabled socket is connected. Opening them all
                // before reading anything is therefore mandatory, otherwise both sides block.
                fun nextSocket(purpose: String): InputStream {
                    val socket = startup.track(awaitSocket(adb, options.socketName, log, purpose, startup, logReader))
                    sockets += socket
                    return socket.openInputStream()
                }

                val videoInput = if (options.video) nextSocket("video") else null
                val audioInput = if (options.audio) nextSocket("audio") else null
                val audioSocket = if (options.audio) sockets.last() else null
                val controlSocket = if (options.control) {
                    startup.track(awaitSocket(adb, options.socketName, log, "control", startup, logReader)).also { sockets += it }
                } else {
                    null
                }
                val controlInput = controlSocket?.openInputStream()

                val firstInput = videoInput ?: audioInput ?: controlInput
                    ?: throw IOException("At least one of video, audio or control must be enabled")

                // Forward tunnels get a dummy byte so the client can detect connection failures,
                // followed by the 64-byte NUL-padded device name, both on the first socket.
                readDummyByte(firstInput)
                val deviceName = readDeviceName(firstInput).ifBlank { host }
                val readyNs = System.nanoTime()
                Log.i(
                    TAG,
                    "Startup timing: total=${(readyNs - startNs) / 1_000_000}ms " +
                        "connect=${(afterConnectNs - startNs) / 1_000_000}ms " +
                        "deploy=${(afterDeployNs - afterConnectNs) / 1_000_000}ms(pushed=$pushed) " +
                        "shell=${(afterShellNs - afterDeployNs) / 1_000_000}ms " +
                        "sockets=${(readyNs - afterShellNs) / 1_000_000}ms",
                )

                val videoReader = videoInput?.let { ScrcpyStreamReader(it) }
                val audioReader = audioInput?.let { ScrcpyStreamReader(it) }
                val controller = controlSocket?.let {
                    ScrcpyController(it.openOutputStream(), onControlFailure, onTouchWritten)
                }
                if (controller != null) startup.track(Closeable { controller.stop() })
                val deviceMessageReader = controlInput?.let { DeviceMessageReader(it) }

                onStatus(context.getString(R.string.status_connected, deviceName))
                val result = ScrcpySession(
                    appContext = context.applicationContext,
                    adb = adb,
                    shellStream = stream,
                    sockets = sockets,
                    audioSocket = audioSocket,
                    deviceName = deviceName,
                    videoReader = videoReader,
                    audioReader = audioReader,
                    controller = controller,
                    deviceMessageReader = deviceMessageReader,
                    server = options.server,
                    targetSdk = targetSdk,
                    serverLog = log,
                )
                startup.transfer()
                return result
            } catch (e: Exception) {
                // The server explains its own refusals, and until now only a socket timeout
                // carried that explanation. A server that starts, rejects an option and exits
                // instead produced "Stream closed." — read as a network fault, so the user was
                // told to check their Wi-Fi while the device had already said, for instance, that
                // the encoder did not exist. Reconnecting then repeated the same rejected option.
                throw startupFailure(e, startup, serverLog, logReader)
            }
        }

        /**
         * Ends a failed start: releases what it held and reports [error] with the server's output.
         *
         * A server that refuses an option writes why and exits, and adbd may close its sockets
         * before relaying that output. Closing the transport at once lost the reason, so the
         * refusal read as a lost connection and was retried; the output is now given until it
         * ends, at most [graceMillis], unless the start was cancelled.
         */
        internal fun startupFailure(
            error: Exception,
            startup: StartupResources,
            serverLog: StringBuilder?,
            logReader: Thread?,
            graceMillis: Long = SERVER_EXIT_GRACE_MS,
        ): Exception {
            if (logReader != null && !startup.isCancelled && !Thread.currentThread().isInterrupted) {
                try {
                    logReader.join(graceMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            try { startup.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
            return withServerLog(error, serverLog)
        }

        /** Re-reports [error] with whatever the server said before it went away. */
        private fun withServerLog(error: Exception, serverLog: StringBuilder?): Exception {
            val log = serverLog?.let { synchronized(it) { it.toString() } }?.trim().orEmpty()
            if (log.isEmpty()) return error
            val refused = hasServerError(log)
            val originalMessage = error.message ?: error.javaClass.simpleName
            val alreadyAnnotated = originalMessage.contains(SERVER_OUTPUT_HEADING)
            if (alreadyAnnotated && (!refused || error is ScrcpyServerRefusedException)) return error
            val message = if (alreadyAnnotated) originalMessage
                else "$originalMessage\n\n$SERVER_OUTPUT_HEADING\n$log"
            // A server that complained before exiting was not a network problem, and saying so is
            // what stops the retries. Anything else keeps its own type, so a connection that
            // genuinely dropped is still recognised as transient and still retried — the log is
            // only added to what the user is told.
            return if (refused) {
                ScrcpyServerRefusedException(message, error)
            } else {
                IOException(message, error)
            }
        }

        /** Whether [log] contains the server reporting a fault, as opposed to its usual chatter. */
        private fun hasServerError(log: String): Boolean =
            log.lineSequence().any { line ->
                val text = line.substringAfter("[server]").trim()
                text.startsWith("ERROR", ignoreCase = true) ||
                    text.startsWith("Exception in thread") ||
                    text.startsWith("FATAL", ignoreCase = true)
            }

        private fun awaitSocket(
            adb: AdbDeviceConnection,
            socketName: String,
            serverLog: StringBuilder,
            purpose: String,
            startup: StartupResources,
            logReader: Thread? = null,
        ): AdbStream {
            var exitedAt = 0L
            val deadline = System.nanoTime() + SOCKET_TIMEOUT_MS * 1_000_000
            var lastError: Exception? = null
            while (System.nanoTime() < deadline) {
                startup.checkActive()
                try {
                    return adb.openLocalAbstract(socketName)
                } catch (e: IOException) {
                    lastError = e
                } catch (e: InterruptedException) {
                    throw e
                }
                val log = synchronized(serverLog) { serverLog.toString() }
                if (hasServerError(log)) {
                    throw withServerLog(IOException("The server failed before opening the $purpose socket", lastError), serverLog)
                }
                if (logReader != null && !logReader.isAlive) {
                    val now = System.nanoTime()
                    if (exitedAt == 0L) exitedAt = now
                    else if (now - exitedAt >= SERVER_EXIT_GRACE_MS * 1_000_000) {
                        throw withServerLog(EOFException("The server exited before opening the $purpose socket"), serverLog)
                    }
                }
                Thread.sleep(SOCKET_RETRY_DELAY_MS)
            }
            val log = synchronized(serverLog) { serverLog.toString() }.trim()
            val details = if (log.isEmpty()) "" else "\n\n$SERVER_OUTPUT_HEADING\n$log"
            throw java.net.SocketTimeoutException("Timed out opening the $purpose socket ($socketName)$details")
                .apply { initCause(lastError) }
        }

        internal fun readDummyByte(input: InputStream) {
            val value = input.read()
            if (value == -1) throw EOFException("Stream ended before the scrcpy handshake byte")
            if (value != 0) throw IOException("Invalid scrcpy handshake byte: $value")
        }

        /** The device's API level, or null when it cannot be read: only an explanation depends on it. */
        private fun readTargetSdk(adb: AdbDeviceConnection): Int? = try {
            PlaybackDuplication.parseSdk(adb.runShellCommand("getprop ro.build.version.sdk", SDK_QUERY_TIMEOUT_MS))
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the device's Android version", e)
            null
        }

        private fun readDeviceName(input: InputStream): String {
            val buffer = ByteArray(DEVICE_NAME_FIELD_LENGTH)
            DataInputStream(input).readFully(buffer)
            val end = buffer.indexOf(0).let { if (it < 0) buffer.size else it }
            return String(buffer, 0, end, Charsets.UTF_8)
        }

        private const val LOG_SINK_LIMIT = 8192
        private const val LOG_LINE_LIMIT = 2048

        private fun startLogReader(input: InputStream, sink: StringBuilder): Thread =
            Thread({
                try {
                    readBoundedLines(input) { line ->
                        Log.i(TAG, "[server] $line")
                        synchronized(sink) {
                            sink.appendLine(line)
                            if (sink.length > LOG_SINK_LIMIT) sink.delete(0, sink.length - LOG_SINK_LIMIT)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Server log reader finished", e)
                }
            }, "scrcpy-server-log").apply { isDaemon = true; start() }

        /**
         * Reads [input] as lines, cutting any that runs past [LOG_LINE_LIMIT].
         *
         * `forEachLine` has to hold a whole line before it can hand it over, so a device that
         * never sends a newline would grow that line until the heap gave out — and the size cap
         * on the sink below cannot help, because it is only applied once a line arrives. The
         * remote device's output is not something this app gets to trust.
         */
        private fun readBoundedLines(input: InputStream, onLine: (String) -> Unit) {
            val reader = input.reader()
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0) break
                val character = next.toChar()
                if (character == '\n') {
                    onLine(line.toString().trimEnd('\r'))
                    line.setLength(0)
                } else {
                    line.append(character)
                    if (line.length >= LOG_LINE_LIMIT) {
                        onLine(line.toString())
                        line.setLength(0)
                    }
                }
            }
            if (line.isNotEmpty()) onLine(line.toString().trimEnd('\r'))
        }

        private fun ByteArray.indexOf(value: Byte): Int {
            for (i in indices) if (this[i] == value) return i
            return -1
        }
    }
}

/** Checks and refreshes the deployed server over injectable sync channels. */
internal class ServerDeployment(
    private val remotePath: String,
    private val expectedSha256: String,
) {
    private val lock = ReentrantLock()

    fun ensureDeployed(
        serverBytes: ByteArray,
        openSync: () -> SyncChannel,
        onPush: () -> Unit = {},
    ): Boolean = ensureDeployed(serverBytes, openSync, remoteSha256 = null, onPush = onPush)

    fun ensureDeployed(
        serverBytes: ByteArray,
        openSync: () -> SyncChannel,
        remoteSha256: (() -> String?)?,
        onPush: () -> Unit = {},
    ): Boolean {
        lock.lockInterruptibly()
        try {
            val assetHash = MessageDigest.getInstance("SHA-256").digest(serverBytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(assetHash == expectedSha256) { "Bundled scrcpy server failed integrity verification" }
            val stat = AdbSync.stat(openSync, remotePath)
            val alreadyPresent = stat.exists && stat.size == serverBytes.size &&
                verifyRemoteSha256(openSync, serverBytes.size, assetHash, remoteSha256)
            if (alreadyPresent) return false
            onPush()
            AdbSync.push(openSync, serverBytes, remotePath)
            check(verifyRemoteSha256(openSync, serverBytes.size, assetHash, remoteSha256)) {
                "Deployed scrcpy server failed integrity verification"
            }
            return true
        } finally {
            lock.unlock()
        }
    }

    private fun verifyRemoteSha256(
        openSync: () -> SyncChannel,
        expectedSize: Int,
        assetHash: String,
        remoteSha256: (() -> String?)?,
    ): Boolean {
        val fastHash = remoteSha256?.invoke()?.lowercase(Locale.ROOT)
        if (fastHash != null) return fastHash == assetHash
        return AdbSync.sha256(openSync, remotePath, expectedSize) == assetHash
    }
}

private class TrackedSyncChannel(
    private val channel: SyncChannel,
    private val onClosed: (Closeable) -> Unit,
) : SyncChannel by channel {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try { channel.close() } catch (error: Throwable) { failure = error }
        try { onClosed(this) } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
