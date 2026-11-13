package com.scrcpy.remote.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrcpy.remote.R
import com.scrcpy.remote.adb.AdbDeviceConnection
import com.scrcpy.remote.adb.AdbServiceBrowser
import com.scrcpy.remote.adb.NsdPairingServiceDiscovery
import com.scrcpy.remote.adb.PairingDiscoveryState
import com.scrcpy.remote.adb.ConnectEndpointResult
import com.scrcpy.remote.adb.ConnectServiceDiscovery
import com.scrcpy.remote.adb.NsdConnectServiceDiscovery
import com.scrcpy.remote.adb.PairingServiceDiscovery
import com.scrcpy.remote.adb.QrMatchResult
import com.scrcpy.remote.adb.QrPairingCredentials
import com.scrcpy.remote.adb.QrPairingDiscovery
import java.util.UUID
import java.io.Closeable
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PersistenceIssue
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.session.ConnectionDiagnoser
import com.scrcpy.remote.session.ConnectionHistoryStore
import com.scrcpy.remote.session.ConnectionProbeChecks
import com.scrcpy.remote.session.DiagnosticReport
import com.scrcpy.remote.session.ProbeReport
import com.scrcpy.remote.session.ProbeStatus
import com.scrcpy.remote.session.ProbeStep
import com.scrcpy.remote.session.ProbeStepResult
import com.scrcpy.remote.session.DeviceConnectionHistory
import com.scrcpy.remote.session.ShareCache
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PairingState {
    data object Idle : PairingState
    data object Pairing : PairingState
    data class Success(val message: String) : PairingState
    data class Failure(val message: String) : PairingState
}

sealed interface QrPairingState {
    data object Idle : QrPairingState
    data class WaitingForScan(val payload: String, val remainingSeconds: Int) : QrPairingState
    data class Pairing(val host: String) : QrPairingState
    data class Failed(val message: String, val timedOut: Boolean = false) : QrPairingState
}

sealed interface PostPairingState {
    data object Idle : PostPairingState
    data class Searching(val host: String, val suggestedName: String?) : PostPairingState
    data class ConfirmAddAndConnect(val device: SavedDevice) : PostPairingState
    data class FallbackAddDevice(val host: String, val suggestedName: String?) : PostPairingState
}

enum class MainMutationAction { SAVE_DEVICE, DELETE_DEVICE, FAVORITE, DEVICE_SETTINGS, DEFAULTS }

/** Outcome of taking a copy of a file another app shared in. */
sealed interface ShareIngest {
    data class Ready(val path: String, val name: String) : ShareIngest
    data object Unreadable : ShareIngest
}

/**
 * Whether a restored device list lost a share's copy along with the previous process.
 *
 * True only if a copy was running when the list was saved, none is running or waiting now, and
 * no file was taken in the meantime: a rotation keeps the copy going in the same view model,
 * whereas a new process starts with nothing, and the sharing app's read grant went with the old.
 */
internal fun shareCopyWasLost(
    copyingWhenSaved: Boolean,
    copyingNow: Boolean,
    resultWaiting: Boolean,
    pendingShare: String?,
): Boolean = copyingWhenSaved && !copyingNow && !resultWaiting && pendingShare == null

data class MainMutationState(
    val requestId: Long = 0,
    val action: MainMutationAction? = null,
    val busy: Boolean = false,
    val success: Boolean? = null,
    val error: String? = null,
)

/** A device just removed, with its place in the saved order, while it can still be put back. */
data class RemovedDevice(val device: SavedDevice, val index: Int, val serial: Long, val restoreFailed: Boolean = false)

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: DeviceRepository = DeviceRepository.get(application),
    private val history: ConnectionHistoryStore = ConnectionHistoryStore.get(application),
) : AndroidViewModel(application) {

    val devices: StateFlow<List<SavedDevice>> = repository.devices

    /** Each saved device's connection history, kept on this phone only. */
    val connectionHistory: StateFlow<Map<String, DeviceConnectionHistory>> = history.history

    fun clearConnectionHistory(deviceId: String) = history.forget(listOf(deviceId))

    private val _connectionCheck = MutableStateFlow<ConnectionCheckState?>(null)

    /** The "Check connection" run for a saved device, or null when none is showing. */
    internal val connectionCheck: StateFlow<ConnectionCheckState?> = _connectionCheck.asStateFlow()
    private var checkJob: Job? = null
    private var lastChecked: SavedDevice? = null
    private var checkSerial = 0

    fun recheckConnection() { lastChecked?.let(::checkConnection) }

    /** Read-only checks in order: network, ADB authorization, server; stops at the first failure. */
    fun checkConnection(device: SavedDevice) {
        checkJob?.cancel()
        lastChecked = device
        val name = device.name
        val serial = ++checkSerial
        _connectionCheck.value = ConnectionCheckState(name, ProbeReport.initial(), running = true)
        val job = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    ConnectionProbeChecks.run(getApplication(), device.host, device.port) { progress ->
                        if (serial == checkSerial && isActive) {
                            _connectionCheck.value = ConnectionCheckState(name, progress, running = true)
                        }
                    }
                }
                if (serial != checkSerial) return@launch
                val failure = report.firstFailure
                val details = failure?.error?.let { error ->
                    DiagnosticReport.build(device.host, device.port, error,
                        ConnectionDiagnoser.diagnose(error).category, null, "")
                }
                _connectionCheck.value = ConnectionCheckState(name, report, running = false, details = details)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connection check failed", e)
                val report = ProbeReport(ProbeStep.entries.mapIndexed { i, step ->
                    ProbeStepResult(step, if (i == 0) ProbeStatus.FAILED else ProbeStatus.SKIPPED, if (i == 0) e else null)
                })
                _connectionCheck.value = ConnectionCheckState(name, report, running = false,
                    details = DiagnosticReport.build(device.host, device.port, e, ConnectionDiagnoser.diagnose(e).category, null, ""))
            }
        }
        checkJob = job
    }

    fun dismissConnectionCheck() {
        checkSerial++
        checkJob?.cancel()
        _connectionCheck.value = null
    }
    val settings: StateFlow<MirrorSettings> = repository.settings
    val errors: StateFlow<List<PersistenceIssue>> = repository.errors

    /** The user has been shown [issues]; the next list or session need not show them again. */
    fun acknowledgeIssues(issues: List<PersistenceIssue>) = repository.acknowledge(issues)

    private val _mutation = MutableStateFlow(MainMutationState())
    val mutation: StateFlow<MainMutationState> = _mutation.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()
    private var pairingJob: Job? = null
    private var pairingRequestId = 0L

    internal var pairingBrowserFactory: () -> AdbServiceBrowser = {
        NsdPairingServiceDiscovery.createBrowser(getApplication())
    }
    private val _pairingDiscoveryState = MutableStateFlow<PairingDiscoveryState>(PairingDiscoveryState.Idle)
    val pairingDiscoveryState: StateFlow<PairingDiscoveryState> = _pairingDiscoveryState.asStateFlow()
    private var pairingDiscoveryCloseable: Closeable? = null
    private var pairingDiscoverySerial = 0L

    internal var connectBrowserFactory: () -> AdbServiceBrowser = {
        NsdConnectServiceDiscovery.createBrowser(getApplication())
    }
    private val _postPairingState = MutableStateFlow<PostPairingState>(PostPairingState.Idle)
    val postPairingState: StateFlow<PostPairingState> = _postPairingState.asStateFlow()
    private var postPairingJob: Job? = null
    private var postPairingSerial = 0L

    private val _qrPairingState = MutableStateFlow<QrPairingState>(QrPairingState.Idle)
    val qrPairingState: StateFlow<QrPairingState> = _qrPairingState.asStateFlow()
    private var qrPairingJob: Job? = null
    private var qrPairingSerial = 0L

    private val _shareIngest = MutableStateFlow<ShareIngest?>(null)
    private var shareSerial = 0L

    /** Reads a shared file into the cache. Replaceable so a test can hold a copy open. */
    internal var shareCopier: suspend (android.net.Uri) -> ShareCache.Copied? =
        { ShareCache.copyIn(getApplication(), it) }

    private val _shareCopying = MutableStateFlow(false)

    /** True from the moment a share arrives until its copy has landed or failed. */
    val shareCopying: StateFlow<Boolean> = _shareCopying.asStateFlow()

    private val _shareDestination = MutableStateFlow<SavedDevice?>(null)

    /** A device picked while the shared file was still being copied, connected to once it lands. */
    val shareDestination: StateFlow<SavedDevice?> = _shareDestination.asStateFlow()

    /**
     * Keeps [device] as where the shared file goes, if the file is still being copied in.
     *
     * Picking a device meant "send it there" even before the copy landed, but connecting at once
     * carried no file, and the device list asked again when the copy finished. Returns false when
     * no copy is running, so the caller connects as usual.
     */
    fun queueShareDestination(device: SavedDevice): Boolean {
        if (!_shareCopying.value) return false
        _shareDestination.value = device
        return true
    }

    /** The device picked while the file was copying, handed over once and then forgotten. */
    fun takeShareDestination(): SavedDevice? = _shareDestination.value.also { _shareDestination.value = null }

    /** Drops the share: a copy still running lands nowhere and deletes itself, and nothing is sent. */
    fun cancelShare() {
        shareSerial++
        _shareCopying.value = false
        _shareDestination.value = null
        (_shareIngest.value as? ShareIngest.Ready)?.let { ShareCache.discard(it.path) }
        _shareIngest.value = null
    }

    /** Set once this view model is gone, so a copy that lands afterwards cleans up after itself. */
    @Volatile private var cleared = false

    /**
     * Result of copying a shared file into this app's cache.
     *
     * The copy runs here rather than in the activity so that rotating the screen, or any other
     * recreation, cannot cancel it half way and silently lose the file: a ViewModel outlives the
     * activity it belongs to, and the finished result waits here until an activity takes it.
     */
    val shareIngest: StateFlow<ShareIngest?> = _shareIngest.asStateFlow()

    /** Copies the bytes behind [uri] while the sharing app's read grant is still valid. */
    fun ingestShare(uri: android.net.Uri) {
        val serial = ++shareSerial
        // A finished copy can wait here unclaimed while the device list is stopped. This share
        // replaces it now: otherwise the returning screen adopts the old file, and a device chosen
        // before the new copy lands is sent the file the user has just replaced.
        (_shareIngest.value as? ShareIngest.Ready)?.let { ShareCache.discard(it.path) }
        _shareIngest.value = null
        // A device picked for the file this replaces was not picked for this one.
        _shareDestination.value = null
        _shareCopying.value = true
        viewModelScope.launch {
            var copied: ShareCache.Copied? = null
            var adopted = false
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    // The return to Main can be cancelled, so retain ownership inside IO first.
                    copied = shareCopier(uri)
                }
                // A slower, older share must not replace the latest choice.
                if (serial != shareSerial || cleared) return@launch
                _shareIngest.value = copied
                    ?.let { ShareIngest.Ready(it.path, it.name) }
                    ?: ShareIngest.Unreadable
                adopted = true
            } finally {
                // Set after the result, so the share never looks finished before its file exists.
                if (serial == shareSerial) _shareCopying.value = false
                if (!adopted) copied?.let {
                    withContext(NonCancellable + Dispatchers.IO) { ShareCache.discard(it.path) }
                }
            }
        }
    }

    /** Called once an activity has taken the result, so a later recreation does not re-apply it. */
    fun consumeShareIngest() {
        _shareIngest.value = null
    }

    fun saveDevice(device: SavedDevice) = mutate(MainMutationAction.SAVE_DEVICE) { saveDevice(device) }

    fun editDevice(baseline: SavedDevice, edited: SavedDevice) =
        mutate(MainMutationAction.SAVE_DEVICE) { editDevice(baseline, edited) }

    private val _recentlyRemoved = MutableStateFlow<RemovedDevice?>(null)
    private var removalSerial = 0L

    /**
     * The device removed last, until its Undo is used or let go. Held here rather than by the
     * screen, so the offer survives a rotation along with the list it belongs to.
     */
    val recentlyRemoved: StateFlow<RemovedDevice?> = _recentlyRemoved.asStateFlow()

    /** Removes the device, keeping what Undo needs: the device as saved, and its place in the order. */
    fun deleteDevice(id: String) {
        var removed: RemovedDevice? = null
        mutate(MainMutationAction.DELETE_DEVICE, onSuccess = {
            removed?.let { next ->
                // The new offer replaces the old one, whose device can no longer come back.
                _recentlyRemoved.value?.let(::removedForGood)
                _recentlyRemoved.value = next
            }
        }) {
            val index = devices.value.indexOfFirst { it.id == id }
            val device = devices.value.getOrNull(index)
            deleteDevice(id).also { deleted ->
                if (deleted && device != null) removed = RemovedDevice(device, index, ++removalSerial)
            }
        }
    }

    /**
     * Puts the removed device back. Not gated like other edits: an Undo that arrived while another
     * write was finishing would otherwise be dropped, and the device lost. The shared writer still
     * orders it after whatever came first.
     *
     * An Undo that could not be written is offered again, marked as having failed, rather than
     * gone with its message: it is the only way back to the device's settings, calibration and look.
     */
    fun undoRemoval(serial: Long) {
        val removed = _recentlyRemoved.value?.takeIf { it.serial == serial } ?: return
        _recentlyRemoved.value = null
        viewModelScope.launch {
            val restored = try {
                repository.mutate { restoreDevice(removed.device, removed.index) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Could not put a removed device back", error)
                false
            }
            if (!restored) _recentlyRemoved.value = removed.copy(serial = ++removalSerial, restoreFailed = true)
        }
    }

    /** The Undo offer for [serial] has gone without being used. */
    fun forgetRemoval(serial: Long) {
        val offer = _recentlyRemoved.value?.takeIf { it.serial == serial } ?: return
        _recentlyRemoved.value = null
        removedForGood(offer)
    }

    /**
     * A removed device's connection history goes once its Undo has: kept until then, so a device
     * put back comes back with it. Not if it was saved again meanwhile.
     */
    private fun removedForGood(offer: RemovedDevice) {
        if (repository.devices.value.none { it.id == offer.device.id }) history.forget(listOf(offer.device.id))
    }

    fun setFavorite(id: String, favorite: Boolean) = mutate(MainMutationAction.FAVORITE) { setFavorite(id, favorite) }

    fun saveDeviceSettings(
        id: String,
        baseline: DeviceMirroringSettings?,
        edited: DeviceMirroringSettings?,
    ) = mutate(MainMutationAction.DEVICE_SETTINGS) { saveDeviceSettings(id, baseline, edited) }

    fun updateSettings(baseline: MirrorSettings, edited: MirrorSettings) =
        mutate(MainMutationAction.DEFAULTS) { mergeSettings(baseline, edited) }

    fun applySettingsChanges(baseline: MirrorSettings, edited: MirrorSettings) =
        updateSettings(baseline, edited)

    fun consumeMutation(requestId: Long) {
        if (_mutation.value.requestId == requestId && !_mutation.value.busy) {
            _mutation.value = _mutation.value.copy(action = null, success = null, error = null)
        }
    }

    private fun mutate(
        action: MainMutationAction,
        onSuccess: () -> Unit = {},
        operation: DeviceRepository.() -> Boolean,
    ) {
        if (_mutation.value.busy || _mutation.value.success != null) return
        val request = MainMutationState(requestId = _mutation.value.requestId + 1, action = action, busy = true)
        _mutation.value = request
        viewModelScope.launch {
            try {
                val success = repository.mutate(operation)
                if (success) onSuccess()
                _mutation.value = request.copy(busy = false, success = success,
                    error = if (success) null else getApplication<Application>().getString(R.string.storage_save_failed))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutationFailed(request, error)
            } finally {
                // Whatever escaped, never leave this request busy: every later mutate() would bail out.
                if (_mutation.value == request) {
                    _mutation.value = request.copy(busy = false, success = false,
                        error = getApplication<Application>().getString(R.string.storage_save_failed))
                }
            }
        }
    }

    private fun mutationFailed(request: MainMutationState, error: Exception) {
        Log.w(TAG, "Configuration save failed", error)
        _mutation.value = request.copy(busy = false, success = false,
            error = getApplication<Application>().getString(R.string.storage_save_failed))
    }

    /**
     * Pairs with a device using Android 11+ wireless debugging.
     *
     * The host, port and 6-digit code come from Developer options > Wireless debugging >
     * Pair device with pairing code.
     */
    fun pair(host: String, port: Int, code: String, suggestedName: String? = null) {
        // A canceled blocking pairing call may still be unwinding on IO. Do not overlap it.
        if (pairingJob?.isCompleted == false) {
            if (pairingJob?.isCancelled == true) {
                _pairingState.value = PairingState.Failure(getApplication<Application>().getString(R.string.pair_still_finishing))
            }
            return
        }
        val invalid = SettingsValidation.endpointError(host, port)
            ?: if (code.length != 6 || code.any { it !in '0'..'9' }) getApplication<Application>().getString(R.string.pair_code_six_digits) else null
        if (invalid != null) {
            _pairingState.value = PairingState.Failure(invalid)
            return
        }
        val requestId = ++pairingRequestId
        _pairingState.value = PairingState.Pairing
        pairingJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val manager = AdbDeviceConnection.create(getApplication())
                    var pairingFailure: Exception? = null
                    try {
                        currentCoroutineContext().ensureActive()
                        // The library's own socket timeouts bound each step; this bounds the whole
                        // attempt so a stalled port cannot wedge pairing (pair() holds the manager's lock).
                        val paired = com.scrcpy.remote.adb.DeadlineCall.within(
                            timeoutMillis = PAIRING_TIMEOUT_MS,
                            name = "adb-pairing",
                            message = "Pairing did not finish within ${PAIRING_TIMEOUT_MS / 1000} s",
                            dispose = { _: Boolean -> },
                        ) { manager.pair(host, port, code) }
                        if (!paired) {
                            throw PairingRejectedException()
                        }
                        currentCoroutineContext().ensureActive()
                    } catch (e: Exception) {
                        pairingFailure = e
                        throw e
                    } finally {
                        // close() also destroys identity; disconnect() only releases transport.
                        try {
                            manager.disconnect()
                        } catch (e: IOException) {
                            val original = pairingFailure
                            if (original != null) {
                                original.addSuppressed(e)
                            } else {
                                // Pairing already succeeded; a failed transport release is not a failure.
                                Log.w(TAG, "Disconnect after successful pairing failed", e)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (requestId == pairingRequestId) {
                    _pairingState.value = PairingState.Success(
                        getApplication<Application>().getString(R.string.pair_success, host),
                    )
                    startPostPairingLookup(host, suggestedName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                if (requestId == pairingRequestId) {
                    _pairingState.value = PairingState.Failure(
                        getApplication<Application>().getString(
                            if (e is PairingRejectedException) R.string.pair_rejected else R.string.pair_failed,
                        ),
                    )
                }
            }
        }
    }

    private class PairingRejectedException : IOException("The device rejected the pairing request")

    fun startPairingDiscovery() {
        stopPairingDiscovery(resetState = false)
        val serial = ++pairingDiscoverySerial
        val discovery = PairingServiceDiscovery(pairingBrowserFactory())
        pairingDiscoveryCloseable = discovery.start(viewModelScope) { state ->
            if (serial == pairingDiscoverySerial) {
                _pairingDiscoveryState.value = state
            }
        }
    }

    fun stopPairingDiscovery(resetState: Boolean = true) {
        pairingDiscoverySerial++
        val active = pairingDiscoveryCloseable
        pairingDiscoveryCloseable = null
        active?.close()
        if (resetState) {
            _pairingDiscoveryState.value = PairingDiscoveryState.Idle
        }
    }

    private fun startPostPairingLookup(host: String, suggestedName: String?) {
        postPairingJob?.cancel()
        val serial = ++postPairingSerial
        _postPairingState.value = PostPairingState.Searching(host, suggestedName)
        postPairingJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ConnectServiceDiscovery(connectBrowserFactory()).findEndpoint(host, suggestedName)
            }
            if (serial != postPairingSerial) return@launch
            _postPairingState.value = when (result) {
                is ConnectEndpointResult.Found -> PostPairingState.ConfirmAddAndConnect(
                    SavedDevice(
                        id = UUID.randomUUID().toString(),
                        name = result.name,
                        host = result.host,
                        port = result.port,
                    ),
                )
                ConnectEndpointResult.NotFound, ConnectEndpointResult.Unavailable ->
                    PostPairingState.FallbackAddDevice(host, suggestedName)
            }
        }
    }

    fun confirmPostPairingAddAndConnect(device: SavedDevice, onSaved: (SavedDevice) -> Unit) {
        if (_mutation.value.busy) return
        val request = MainMutationState(requestId = _mutation.value.requestId + 1, action = MainMutationAction.SAVE_DEVICE, busy = true)
        _mutation.value = request
        viewModelScope.launch {
            try {
                val saved = repository.mutate { saveDevice(device) }
                _mutation.value = request.copy(
                    busy = false,
                    success = saved,
                    error = if (saved) null else getApplication<Application>().getString(R.string.storage_save_failed),
                )
                if (saved) {
                    dismissPostPairing()
                    cancelQrPairing()
        dismissPostPairing()
        resetPairingState()
                    onSaved(device)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutationFailed(request, error)
            } finally {
                if (_mutation.value == request) {
                    _mutation.value = request.copy(
                        busy = false,
                        success = false,
                        error = getApplication<Application>().getString(R.string.storage_save_failed),
                    )
                }
            }
        }
    }

    fun startQrPairing() {
        cancelQrPairing()
        val serial = ++qrPairingSerial
        val credentials = QrPairingCredentials.generate()
        _qrPairingState.value = QrPairingState.WaitingForScan(
            payload = credentials.payload,
            remainingSeconds = (QrPairingDiscovery.DEFAULT_TIMEOUT_MS / 1_000L).toInt(),
        )
        qrPairingJob = viewModelScope.launch {
            try {
                val match = withContext(Dispatchers.IO) {
                    QrPairingDiscovery(pairingBrowserFactory(), QrPairingDiscovery.DEFAULT_TIMEOUT_MS)
                        .awaitMatchingService(credentials.serviceName) { remaining ->
                            if (serial == qrPairingSerial && _qrPairingState.value is QrPairingState.WaitingForScan) {
                                _qrPairingState.value = QrPairingState.WaitingForScan(credentials.payload, remaining)
                            }
                        }
                }
                if (serial != qrPairingSerial) return@launch
                when (match) {
                    is QrMatchResult.Found -> {
                        _qrPairingState.value = QrPairingState.Pairing(match.host)
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            val manager = AdbDeviceConnection.create(getApplication())
                            var pairingFailure: Exception? = null
                            try {
                                val paired = com.scrcpy.remote.adb.DeadlineCall.within(
                                    timeoutMillis = PAIRING_TIMEOUT_MS,
                                    name = "adb-qr-pairing",
                                    message = "Pairing did not finish within ${PAIRING_TIMEOUT_MS / 1000} s",
                                    dispose = { _: Boolean -> },
                                ) { manager.pair(match.host, match.port, credentials.password) }
                                if (!paired) throw PairingRejectedException()
                            } catch (e: Exception) {
                                pairingFailure = e
                                throw e
                            } finally {
                                try {
                                    manager.disconnect()
                                } catch (e: IOException) {
                                    val original = pairingFailure
                                    if (original != null) original.addSuppressed(e)
                                    else Log.w(TAG, "Disconnect after QR pairing failed", e)
                                }
                            }
                        }
                        if (serial == qrPairingSerial) {
                            _qrPairingState.value = QrPairingState.Idle
                            startPostPairingLookup(match.host, suggestedName = null)
                        }
                    }

                    QrMatchResult.TimedOut -> {
                        _qrPairingState.value = QrPairingState.Failed(
                            getApplication<Application>().getString(R.string.pair_qr_timeout),
                            timedOut = true,
                        )
                    }

                    QrMatchResult.Unavailable -> {
                        _qrPairingState.value = QrPairingState.Failed(
                            getApplication<Application>().getString(R.string.pair_discovery_unavailable),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "QR pairing failed", e)
                if (serial == qrPairingSerial) {
                    _qrPairingState.value = QrPairingState.Failed(
                        getApplication<Application>().getString(
                            if (e is PairingRejectedException) R.string.pair_rejected else R.string.pair_failed,
                        ),
                    )
                }
            }
        }
    }

    fun cancelQrPairing() {
        qrPairingSerial++
        qrPairingJob?.cancel()
        qrPairingJob = null
        _qrPairingState.value = QrPairingState.Idle
    }

    fun dismissPostPairing() {
        postPairingSerial++
        postPairingJob?.cancel()
        postPairingJob = null
        _postPairingState.value = PostPairingState.Idle
    }

    fun resetPairingState() {
        pairingRequestId++
        pairingJob?.cancel()
        stopPairingDiscovery()
        _pairingState.value = PairingState.Idle
    }

    override fun onCleared() {
        // Anything still waiting for a destination goes with the screen that was choosing one.
        // A copy still running is handled where it lands, because the read it is blocked in
        // finishes whether or not this scope is still alive.
        cleared = true
        // An Undo still offered goes with the list it was offered in, so the removal is for good.
        _recentlyRemoved.value?.let(::removedForGood)
        _recentlyRemoved.value = null
        (_shareIngest.value as? ShareIngest.Ready)?.let { ShareCache.discard(it.path) }
        _shareIngest.value = null
        resetPairingState()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val PAIRING_TIMEOUT_MS = 30_000L
    }
}
