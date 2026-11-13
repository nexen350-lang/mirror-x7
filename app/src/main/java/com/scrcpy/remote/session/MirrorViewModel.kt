package com.scrcpy.remote.session

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.sameEndpoint
import com.scrcpy.remote.adb.ConnectionPortResult
import com.scrcpy.remote.adb.NsdConnectionPortDiscovery
import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.ControlMessage
import com.scrcpy.remote.scrcpy.DeviceApp
import com.scrcpy.remote.scrcpy.CameraCapture
import com.scrcpy.remote.scrcpy.DeviceCamera
import com.scrcpy.remote.scrcpy.DeviceDisplay
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.DeviceMessage
import com.scrcpy.remote.scrcpy.PlaybackDuplication
import com.scrcpy.remote.scrcpy.ScrcpyCodecs
import com.scrcpy.remote.scrcpy.ScrcpySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import java.net.SocketException
import java.net.SocketTimeoutException

sealed interface MirrorUiState {
    data class Connecting(val status: String) : MirrorUiState
    data class Connected(
        val deviceName: String,
        val videoWidth: Int,
        val videoHeight: Int,
        val captureGeneration: Long = 0,
        val sessionGeneration: Long = 0,
    ) : MirrorUiState
    /**
     * @param guided whether [message] already tells the user what to do. When it does, the screen
     * must not append a generic "check the address and network" hint that repeats or contradicts
     * more specific advice.
     */
    data class Failed(
        val message: String,
        val guided: Boolean = false,
        val details: String? = null,
    ) : MirrorUiState
    data object Disconnected : MirrorUiState
}

data class AudioState(
    val playing: Boolean = false,
    val muted: Boolean = false,
    val error: String? = null,
    /**
     * The device refused to send sound it keeps playing ([PlaybackDuplication]). Retrying asks for
     * the same capture again, so the screen offers to hear the sound here instead.
     */
    val dupRefused: Boolean = false,
)

/**
 * Why the process must be kept alive, and therefore which foreground service type honestly
 * describes it.
 *
 * Android freezes a backgrounded process within seconds, which drops the ADB connection. A
 * mirroring session is user-initiated ongoing work, so it holds the process up for as long as it
 * is connected — otherwise glancing at a notification ends the session.
 */
enum class SessionHold {
    /** Nothing to keep alive. */
    NONE,

    /** A session is connected. Keeping that connection alive is continuous data transfer. */
    CONNECTED,

    /** Remote audio keeps playing while hidden. */
    AUDIO,

    /** A file is being chosen or sent. */
    TRANSFER,

    /** A file is being chosen or sent while remote audio keeps playing in the background. */
    TRANSFER_WITH_AUDIO;

    /** Whether this hold covers a file transfer, which the notification is titled after. */
    val transfer: Boolean get() = this == TRANSFER || this == TRANSFER_WITH_AUDIO

    /**
     * The foreground service type that honestly describes this reason.
     *
     * Play reviews each declared type against what the app actually does, so only real playback
     * claims media playback. A connected session is what Play's policy calls continuous data
     * transfer to an external device over a network connection, so it is `connectedDevice`; a file
     * push, like any upload, is data sync. A transfer during background playback is both, and
     * declaring only the transfer understated the audio that was still playing.
     *
     * `connectedDevice` also escapes Android 15's six-hour daily cap on data sync, which ended the
     * background hold of long sessions. Android 14 and newer start it only for an app holding one
     * of its prerequisite permissions; the manifest declares CHANGE_NETWORK_STATE for that, and a
     * test pins it, because a start without one throws and once crashed the app on every connect.
     * No session state reaches [NONE], and a start without a stated reason is treated as connected.
     *
     * Lives on the enum so the service and its test cannot drift apart.
     */
    // Inlined constants; the only caller, MirrorSessionService, uses them on Android 10+ alone.
    @android.annotation.SuppressLint("InlinedApi")
    fun foregroundServiceType(): Int = when (this) {
        AUDIO -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        TRANSFER_WITH_AUDIO -> AUDIO.foregroundServiceType() or TRANSFER.foregroundServiceType()
        TRANSFER -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        NONE, CONNECTED -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    }

    /**
     * What the notification says is kept running when there is no saved device name to show.
     *
     * Derived from the reason rather than fixed: one sentence promising playing audio was shown for
     * a silent session and for a transfer that had not even connected yet.
     */
    @StringRes
    fun notificationText(): Int = when (this) {
        AUDIO -> R.string.background_notification_text
        TRANSFER, TRANSFER_WITH_AUDIO -> R.string.transfer_notification_text
        NONE, CONNECTED -> R.string.background_notification_connected
    }
    /**
     * As [notificationText], naming the saved device, as `%1$s`. A transfer is held before its
     * connection is made, and that attempt can fail, so it does not claim to be connected.
     */
    @StringRes
    fun namedNotificationText(): Int = when (this) {
        TRANSFER, TRANSFER_WITH_AUDIO -> R.string.transfer_notification_device
        NONE, CONNECTED, AUDIO -> R.string.background_notification_device
    }

    companion object {
        /** The single hold for the current reasons; a transfer is named first, but never hides audio. */
        fun from(connected: Boolean, backgroundAudio: Boolean, transfer: Boolean): SessionHold = when {
            transfer && backgroundAudio -> TRANSFER_WITH_AUDIO
            transfer -> TRANSFER
            backgroundAudio -> AUDIO
            connected -> CONNECTED
            else -> NONE
        }
    }
}


/**
 * Applications the device reported, kept separate from the session state because listing is an
 * explicit, comparatively slow request rather than part of connecting.
 */
data class DeviceAppsState(
    val loading: Boolean = false,
    val apps: List<DeviceApp> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * A file transfer to the device, kept separate from the session state because it is a discrete
 * user-initiated operation that can fail on its own without affecting mirroring.
 */
data class FileTransferState(
    val running: Boolean = false,
    val name: String = "",
    val sentBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Set just before DONE is sent: from then on the device may have the file. */
    val committing: Boolean = false,
    /** The file is an app the device installs, rather than a file copied into Download. */
    val install: Boolean = false,
    /** Set once the device's package manager has the app: from then on it may be installed. */
    val installing: Boolean = false,
) {
    /** Null while the size is unknown, so the UI can show indeterminate progress instead of 0%. */
    val fraction: Float? get() =
        if (running && totalBytes > 0) (sentBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null
}

/**
 * Encoders the device reported, kept separate from the session state because listing is an
 * explicit, comparatively slow request rather than part of connecting.
 */
/**
 * Displays the device reported, kept separate from the session state for the same reason as the
 * encoder lists: listing is an explicit request rather than part of connecting.
 */
data class DeviceDisplaysState(
    val loading: Boolean = false,
    val displays: List<DeviceDisplay> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

/**
 * Cameras the device reported, kept separate from the session state for the same reason as the
 * displays: listing is an explicit request rather than part of connecting.
 */
data class DeviceCamerasState(
    val loading: Boolean = false,
    val cameras: List<DeviceCamera> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
)

data class DeviceEncodersState(
    val loading: Boolean = false,
    val encoders: List<DeviceEncoder> = emptyList(),
    val error: String? = null,    val loaded: Boolean = false,
)

data class TouchDiagnostics(
    val decodedWidth: Int = 0,
    val decodedHeight: Int = 0,
    val lastRawX: Float = 0f,
    val lastRawY: Float = 0f,
    val lastDeviceX: Int = 0,
    val lastDeviceY: Int = 0,
    val lastScreenWidth: Int = 0,
    val lastScreenHeight: Int = 0,
    val captureGeneration: Long = 0,
    val written: Boolean = false,
) {
    fun sizeMismatch(videoWidth: Int, videoHeight: Int): Boolean =
        decodedWidth > 0 && decodedHeight > 0 && (decodedWidth != videoWidth || decodedHeight != videoHeight)
}

/** Mutable session ownership is confined to Main; all worker callbacks carry a generation. */
@MainThread
class MirrorViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
) : AndroidViewModel(application) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<MirrorUiState>(
        MirrorUiState.Connecting(application.getString(R.string.session_starting)))
    val state = _state.asStateFlow()
    private val _diagnostics = kotlinx.coroutines.flow.MutableStateFlow(TouchDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()
    private val _audioState = kotlinx.coroutines.flow.MutableStateFlow(AudioState())
    val audioState = _audioState.asStateFlow()
    private val _notice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val input = SessionInputGate(savedState[STATE_INPUT_LOCKED] ?: false) {
        session?.controller?.send(it) ?: false
    }
    private val _inputState = kotlinx.coroutines.flow.MutableStateFlow(input.state)
    val inputState = _inputState.asStateFlow()

    fun setInputLocked(locked: Boolean) {
        val current = _state.value as? MirrorUiState.Connected
        input.setLocked(locked, current?.videoWidth ?: 0, current?.videoHeight ?: 0)
        savedState[STATE_INPUT_LOCKED] = locked
        _inputState.value = input.state
        pendingSamples.clear()
        if (locked) resetLiveTyping()
    }
    private val _activeSettings = kotlinx.coroutines.flow.MutableStateFlow<MirrorSettings?>(null)
    val activeSettings = _activeSettings.asStateFlow()
    private val _mediaTiming = kotlinx.coroutines.flow.MutableStateFlow(MediaTimingDiagnostics())
    val mediaTiming = _mediaTiming.asStateFlow()
    private val _videoFrames = kotlinx.coroutines.flow.MutableStateFlow(VideoFrameStats())
    val videoFrames = _videoFrames.asStateFlow()

    /** How long frames are taking from being queued to reaching the screen. */
    private val _videoLatency = kotlinx.coroutines.flow.MutableStateFlow(LatencyStats())
    val videoLatency = _videoLatency.asStateFlow()
    private val _diagnosticsEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val recentQuality = ConnectionQualityHistory()
    private val _qualityHistory = kotlinx.coroutines.flow.MutableStateFlow(ConnectionQualitySnapshot())
    val qualityHistory = _qualityHistory.asStateFlow()

    /**
     * Whether anything is watching the live frame and timing numbers.
     *
     * They are sampled on a timer, so sampling while nothing is looking would be wasted work on
     * the very path where latency matters. The diagnostics overlay used to be the only watcher,
     * which meant the connection details panel showed zeros unless that overlay happened to be
     * switched on as well.
     */
    private val _statsWatched = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** The compact on-screen stats line is showing; it reads the same samples. */
    private val _statsChip = kotlinx.coroutines.flow.MutableStateFlow(false)
    val statsChip = _statsChip.asStateFlow()
    fun setStatsChip(shown: Boolean) { _statsChip.value = shown }
    private var detailsOpen = false

    /** Called by a screen that is showing the live numbers, so they are kept up to date. */
    fun setStatsWatched(watched: Boolean) {
        detailsOpen = watched
        updateStatsWatched()
    }

    private fun updateStatsWatched() {
        val watched = detailsOpen && visible
        _qualityHistory.value = recentQuality.setWatching(watched, SystemClock.elapsedRealtime())
        _statsWatched.value = watched
    }
    private val _backgroundAudio = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _deviceScreenOff = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val _apps = kotlinx.coroutines.flow.MutableStateFlow(DeviceAppsState())

    /**
     * Apps whose uninstall question this session opened and that the list still shows. The device
     * is answered whenever the user gets to it, so a list loaded in between can still show them.
     */
    private val pendingRemovals = linkedSetOf<String>()
    private var removalCheck: Job? = null
    private val _encoders = kotlinx.coroutines.flow.MutableStateFlow(DeviceEncodersState())

    /** Video encoders reported by the device, loaded on demand. */
    val encoders = _encoders.asStateFlow()
    private val _audioEncoders = kotlinx.coroutines.flow.MutableStateFlow(DeviceEncodersState())

    /** Audio encoders reported by the device, loaded on demand and independently of video. */
    val audioEncoders = _audioEncoders.asStateFlow()
    private val _cameras = kotlinx.coroutines.flow.MutableStateFlow(DeviceCamerasState())
    val cameras = _cameras.asStateFlow()

    /** Whether the remote camera's light is on, as far as this client has been told. */
    private val _cameraTorch = kotlinx.coroutines.flow.MutableStateFlow(false)
    val cameraTorch = _cameraTorch.asStateFlow()
    private val _displays = kotlinx.coroutines.flow.MutableStateFlow(DeviceDisplaysState())

    /** Displays the device reported, loaded on demand. */
    val displays = _displays.asStateFlow()
    private val _fileTransfer = kotlinx.coroutines.flow.MutableStateFlow(FileTransferState())

    /** Progress of a file being sent to the device, if one is in flight. */
    val fileTransfer = _fileTransfer.asStateFlow()

    private val _appOffer = kotlinx.coroutines.flow.MutableStateFlow<AppOffer?>(null)

    /** An app package waiting for the user to choose what happens to it. */
    val appOffer = _appOffer.asStateFlow()
    private val _installOutcome = kotlinx.coroutines.flow.MutableStateFlow<AppInstallOutcome?>(null)

    /** How the last install ended, until the user closes it. */
    val installOutcome = _installOutcome.asStateFlow()
    private var appOfferJob: Job? = null

    /** Which offer is current. Read by a copy running on another thread, to stop when replaced. */
    @Volatile private var appOfferSerial = 0L

    /** The offered package on this phone, once it has been read. */
    private var appOfferFile: java.io.File? = null

    /** A copy the offer made of a picked document, deleted when the offer is done with. */
    private var appOfferCopy: java.io.File? = null

    /** The offered file read as an app bundle, when it is one rather than a single app. */
    private var appOfferBundle: AppBundle? = null

    /** What the offer's source needs when the offer ends, such as deleting a share's copy. */
    private var appOfferFinished: (() -> Unit)? = null

    private val _recording = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Whether the session's video is being written to a file. */
    val recording = _recording.asStateFlow()

    private val _recordingStatus = kotlinx.coroutines.flow.MutableStateFlow<RecordingStatus?>(null)

    /** When the running recording started and whether it takes sound; null when not recording. */
    val recordingStatus = _recordingStatus.asStateFlow()

    /** Set while a reconnect asked for by "Reconnect and record" is under way. */
    private var recordWhenSoundPlays = false
    private val keyFramePacer = KeyFramePacer(RECORDING_KEY_FRAME_SPACING_MS)
    private var keyFrameRequest: Job? = null

    private val desktopResize = DesktopResizeState()
    private var desktopViewport: DesktopViewport? = null
    private var desktopResizeJob: Job? = null
    private var desktopResizeTimeout: Job? = null
    private val _desktopResizing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val desktopResizing = _desktopResizing.asStateFlow()

    private val _finishedRecording = kotlinx.coroutines.flow.MutableStateFlow(restoreFinishedRecording())
    // Restored with the offer: an app it was shared with before the process went may still be
    // reading it, so Discard has to leave it for the cache expiry here too.
    private var sharedFinishedRecording: java.io.File? = _finishedRecording.value
        ?.takeIf { savedState.get<Boolean>(STATE_FINISHED_RECORDING_SHARED) == true }

    /**
     * A finished recording waiting to be kept or thrown away.
     *
     * Published rather than returned so a recording that ends on its own — because the session
     * dropped, or the device changed capture size — reaches the user the same way one they
     * stopped by hand does, instead of quietly disappearing.
     */
    val finishedRecording = _finishedRecording.asStateFlow()

    private var recorder: SessionRecorder? = null

    /** Guards against a duplicate document result starting a second copy of the same recording. */
    private val _recordingSaveInProgress = kotlinx.coroutines.flow.MutableStateFlow(false)
    val recordingSaveInProgress = _recordingSaveInProgress.asStateFlow()
    private var cleared = false

    private val _sessionOverrides = kotlinx.coroutines.flow.MutableStateFlow(SessionOverrides.EMPTY)

    /**
     * Encoder choices made for this session and not saved anywhere.
     *
     * Kept here rather than in the profile because a session can be running against an address
     * nobody saved, which is exactly when the encoder list used to be shown greyed out. Published
     * because the screen has to account for them when deciding whether the saved profile differs
     * from what is running: without that, choosing a session encoder raised a "settings differ"
     * banner that reconnecting could never clear, because reconnecting reapplies the override.
     */
    internal val sessionOverrides = _sessionOverrides.asStateFlow()

    private var overrides: SessionOverrides
        get() = _sessionOverrides.value
        set(value) { _sessionOverrides.value = value }

    private val _transferHoldsProcess = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Whether the process must stay alive for a file transfer.
     *
     * Covers the document picker as well as the transfer itself: the picker is another app's
     * activity, so choosing a file backgrounds this one, and Android freezes a backgrounded
     * process within seconds — which would drop the very session the file is being sent over.
     */
    val transferHoldsProcess = _transferHoldsProcess.asStateFlow()

    /** Called before the picker is launched, while this app is still in the foreground. */
    fun onFilePickerOpening() {
        if (session != null) _transferHoldsProcess.value = true
    }

    /** Called when the picker returns without a file, so the hold is not left in place. */
    fun onFilePickerCancelled() {
        if (!_fileTransfer.value.running) _transferHoldsProcess.value = false
    }

    /** Applications reported by the device, loaded on demand. */
    val apps = _apps.asStateFlow()

    /** Whether the mirrored device's own display is currently blanked while mirroring continues. */
    val deviceScreenOff = _deviceScreenOff.asStateFlow()

    /**
     * True while the session is allowed to keep running with the screen hidden. The Activity
     * mirrors this into a foreground service; without one the process would be frozen or killed
     * and the connection lost.
     */
    val backgroundAudio = _backgroundAudio.asStateFlow()

    /**
     * The single reason the foreground service should be running, if any.
     *
     * Derived rather than set from several places so the service can never be started for one
     * reason and stopped for another while the first still needs it.
     */
    val sessionHold: StateFlow<SessionHold> =
        combine(_state, _backgroundAudio, _transferHoldsProcess) { state, audio, transfer ->
            SessionHold.from(connected = state is MirrorUiState.Connected, backgroundAudio = audio, transfer = transfer)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionHold.NONE)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cleanupJob: Job? = null
    private var transferCleanupJob: Job? = null
    private var fileTransferJob: Job? = null

    /**
     * Cancels the content-provider calls a transfer makes before it starts pushing bytes.
     *
     * Those are blocking IPC: cancelling the coroutine leaves them waiting until the provider
     * answers, and the transfer slot stays reserved until they return, refusing every later
     * transfer in the meantime.
     */
    private var fileTransferSignal: android.os.CancellationSignal? = null

    /** Installed before the worker starts, so cancellation also owns a channel opened late. */
    private var fileTransferChannel: java.io.Closeable? = null
    private var fileTransferSource: java.io.Closeable? = null
    /**
     * Which transfer owns the single slot.
     *
     * Read from the transfer worker's IO thread to decide whether its progress still belongs to
     * the current transfer, and written from the main thread when a session is torn down, so the
     * worker has to see the new value rather than a cached one: acting on a stale serial put a
     * finished transfer's "running" back and left the slot occupied for good.
     */
    @Volatile private var fileTransferSerial = 0L
    private var pendingShare: Pair<String, String?>? = null
    private var connectJob: Job? = null
    private val generations = SessionGeneration()
    @Volatile private var session: ScrcpySession? = null
    private var video: VideoDecoder? = null
    private var audio: AudioPlayer? = null
    private var deviceMessages: Thread? = null
    private var surface: Surface? = null
    private var visible = false
    private var userMuted = false
    private var focusGranted = false
    private var timingJob: Job? = null
    private var mediaClock: AudioPresentationClock? = null
    private val audioFocus = SessionAudioFocus(application,
        onFocus = { granted, permanent ->
            focusGranted = granted
            if (permanent) {
                userMuted = true
                _notice.value = application.getString(R.string.audio_focus_resume)
            }
            audio?.resetTimeline()
            applyAudioMute()
        },
        onNoisy = {
            userMuted = true
            focusGranted = false
            audio?.resetTimeline()
            applyAudioMute()
            _notice.value = application.getString(R.string.audio_route_resume)
        },
        onRouteChanged = { audio?.resetTimeline() },
    )
    private var request: ConnectionRequest? = null
    private var recovery = ConnectionRecoveryPolicy.State()
    private var recoveryJob: Job? = null
    private var stableJob: Job? = null
    /** A recording that finished while a save was copying the current one; applied afterwards. */
    private var pendingFinishedRecording: java.io.File? = null

    /**
     * What was on screen before a manual retry replaced it with "Connecting".
     *
     * A manual retry deliberately clears the recorded failure, so cancelling its port discovery
     * leaves nothing that could ever finish the spinner it put up. Keeping the previous state
     * means backgrounding can put it straight back.
     */
    private var stateBeforeRetry: MirrorUiState? = null
    private var recoveryFailure: Throwable? = null
    private var recoveryToken = 0L
    private var failureMessage = ""
    private var failureGuided = false
    private var failureDetails: String? = null

    /** The current attempt's progress, or null once it has ended or before any attempt. */
    private var checkupTracker: ConnectionCheckupTracker? = null

    /** The checkup of the attempt behind the failure on screen, restored with that failure. */
    private var failedCheckup: ConnectionCheckup? = null
    private val _checkup = kotlinx.coroutines.flow.MutableStateFlow<ConnectionCheckup?>(null)

    /**
     * Which steps the current or last failed connection attempt got through, for the connecting
     * and failure screens. Observed from that attempt only; see [ConnectionCheckup].
     */
    val checkup: StateFlow<ConnectionCheckup?> = _checkup.asStateFlow()

    private fun publishCheckup(tracker: ConnectionCheckupTracker) {
        if (checkupTracker === tracker) _checkup.value = tracker.snapshot()
    }

    /** What a saved device's earlier attempts say about the failure on screen, restored with it. */
    private var failedHistory: FailureHistory? = null
    private val _failureHistory = kotlinx.coroutines.flow.MutableStateFlow<FailureHistory?>(null)

    /** For the failure screen: null while connecting, for an unsaved address, or with no failure. */
    val failureHistory: StateFlow<FailureHistory?> = _failureHistory.asStateFlow()
    private val connectionHistory by lazy { ConnectionHistoryStore.get(getApplication()) }

    private val _failureReport = kotlinx.coroutines.flow.MutableStateFlow<FailureReport?>(null)

    /** Diagnosis of the failure on screen (see [ConnectionDiagnoser]); null while there is none. */
    val failureReport: StateFlow<FailureReport?> = _failureReport.asStateFlow()

    /**
     * Adds a failed attempt to the saved device's history. Only an attempt that reached a step: a
     * setting rejected before connecting, or one the user cancelled, is not a connection problem.
     */
    private fun recordProblem(checkup: ConnectionCheckup?, kind: ConnectionRecoveryPolicy.FailureKind): FailureHistory? {
        val deviceId = savedHistoryDeviceId() ?: return null
        val stage = checkup?.failedStage ?: return null
        if (kind == ConnectionRecoveryPolicy.FailureKind.CANCELLED) return null
        return FailureHistory.of(connectionHistory.recordFailure(deviceId, stage, kind, checkup.endpoint))
    }

    /**
     * The saved device this attempt adds to the history of, while it is still saved. An attempt or
     * session can outlast its device being removed for good, and its history with it; adding to
     * that history then would leave a record of a device that is no longer on the list.
     */
    private fun savedHistoryDeviceId(): String? = request?.deviceId?.takeIf { id ->
        DeviceRepository.get(getApplication()).getDevices().any { it.id == id }
    }
    private var establishedSession = false
    private var discoveryAllowed = false
    private var retryPort: Int? = null
    private var automaticAttempt = false
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var monitoringNetwork = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A network came back while a retry is waiting out its backoff: try now, since the
            // likeliest reason the last attempt failed has just gone away.
            viewModelScope.launch { retryRecoveryNow() }
        }

        override fun onLost(network: Network) {
            // Which session was live is captured here, on the callback thread, rather than after
            // the hop to the main thread. A delivery that arrives late would otherwise be applied
            // to whatever session happens to be current by then, so the loss of an old network
            // could take down the replacement that recovery had just established on a new one.
            val lost = session
            viewModelScope.launch {
                if (_activeSettings.value?.autoReconnect == true && session != null && session === lost) {
                    fail(SocketException("network is unreachable"))
                }
            }
        }
    }
    private val clipboardSequence = java.util.concurrent.atomic.AtomicLong(1L)
    private val pendingSamples = mutableMapOf<Long, Pair<ControlMessage.InjectTouchEvent, TouchDiagnostics>>()
    /**
     * Diagnostics are sampled only while the overlay is on screen. Publishing timing and frame
     * counters while it is hidden would invalidate the whole mirror screen twice a second for
     * values nothing reads.
     */
    var diagnosticsEnabled: Boolean
        get() = _diagnosticsEnabled.value
        set(value) { _diagnosticsEnabled.value = value }

    /**
     * Only a live session that actually carries audio may claim a media-playback service, so the
     * setting alone is not enough.
     */
    private val backgroundSessionEnabled: Boolean
        get() = _activeSettings.value?.backgroundAudio == true &&
            session != null && _audioState.value.playing

    val isAudioMuted: Boolean get() = userMuted

    /**
     * Connects to [host]:[port], replacing a session already running somewhere else.
     *
     * Called again whenever the screen is rebuilt, which includes a relaunch carrying a different
     * destination. Returning early whenever anything was live — as this used to — meant such a
     * relaunch kept the previous session and only re-labelled the screen, so the app mirrored one
     * device while claiming to show another. A repeat of the endpoint this view model already
     * owns is ignored whether that request is live, connecting, waiting to retry or failed:
     * rebuilding the screen (a font, locale or keyboard change) is not a new request, and dialling
     * again there reset the retry allowance and any rediscovered port. An explicit request for the
     * same device arrives through [disconnect] first, which clears the owned request.
     */
    fun connect(
        host: String,
        port: Int,
        settings: MirrorSettings,
        allowPortDiscovery: Boolean = false,
        /** The saved device being connected, whose connection history this adds to; null for an unsaved address. */
        historyDeviceId: String? = null,
        /** The link that opened the session asked for options of its own; see [sessionChoicesInEffect]. */
        linkOptions: Boolean = false,
        settingsProvider: () -> MirrorSettings = { settings },
    ) {
        val current = request
        if (current != null) {
            if (sameEndpoint(current.host, current.port, host, port)) return
            // A different destination: tear the old one down first, so the connection, the
            // foreground service and the screen all describe the same device.
            disconnect()
        } else if (session != null || connectJob?.isActive == true) {
            return
        }
        request = ConnectionRequest(host, port, deviceId = historyDeviceId, linkOptions = linkOptions, settingsProvider = settingsProvider)
        discoveryAllowed = allowPortDiscovery
        recovery = ConnectionRecoveryPolicy.State()
        establishedSession = false
        retryPort = null
        beginConnection()
    }

    /**
     * @param staleAgainst the settings the session choices last ran as, when they are not the last
     * attempt's: a choice is only stale against settings it actually ran with.
     */
    private fun beginConnection(enableAudio: Boolean = false, automatic: Boolean = false, staleAgainst: MirrorSettings? = null) {
        val connection = request ?: return
        stateBeforeRetry = null
        // A settings problem found below is not a step of any connection, so it has no checkup.
        checkupTracker = null
        val host = connection.host
        val port = retryPort ?: connection.port
        val profile = try { connection.snapshot(enableAudio) }
        catch (e: IllegalArgumentException) { fail(e); return }
        // A codec change since the last attempt invalidates an encoder chosen for the old one,
        // which the device would refuse outright.
        overrides = overrides.clearStaleEncoders(staleAgainst ?: _activeSettings.value, profile)
        if (overrides.display is SessionDisplay.Desktop) {
            desktopViewport?.let { overrides = overrides.copy(display = it.display(profile.maxSize)) }
        }
        val settings = overrides.applyTo(profile)
        val desktop = (overrides.display as? SessionDisplay.Desktop)?.takeIf { settings.camera == null }
        val previous = connectJob
        previous?.cancel()
        automaticAttempt = automatic
        val token = generations.next()
        detachSession()
        val tracker = ConnectionCheckupTracker(endpointLabel(), sessionChoicesInEffect(), SystemClock::elapsedRealtime)
        checkupTracker = tracker
        _checkup.value = tracker.snapshot()
        _failureHistory.value = null
        desktop?.let {
            desktopResize.start(it.size)
            _desktopResizing.value = true
        }
        val cleanup = cleanupJob
        _state.value = MirrorUiState.Connecting(getApplication<Application>().getString(R.string.status_connecting, "$host:$port"))
        _diagnostics.value = TouchDiagnostics()
        _videoFrames.value = VideoFrameStats()
        _videoLatency.value = LatencyStats()
        _audioState.value = AudioState(muted = userMuted)
        _activeSettings.value = settings
        if (settings.autoReconnect && !monitoringNetwork) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            monitoringNetwork = true
        } else if (!settings.autoReconnect && monitoringNetwork) {
            connectivity.unregisterNetworkCallback(networkCallback)
            monitoringNetwork = false
        }
        connectJob = viewModelScope.launch {
            val handoff = ResourceHandoff<ScrcpySession>()
            val startup = StartupResources()
            try {
                previous?.join()
                cleanup?.join()
                // One deadline for the whole start, extended once the device shows its "Allow
                // debugging?" prompt: from then on the wait is a person's, not the network's.
                val startupDeadline = ExtendableDeadline(STARTUP_TIMEOUT_MS)
                startupDeadline.run {
                    awaitStartup(handoff, startup) {
                        ScrcpySession.start(
                            getApplication(), host, port, settings.toOptions().copy(flexDisplay = desktop != null),
                            onStatus = { status -> post(token) { _state.value = MirrorUiState.Connecting(status) } },
                            // Recorded on the startup thread before anything else can happen, so a
                            // failure that follows is attributed to the step that was running.
                            onStage = { stage ->
                                tracker.advance(stage)
                                // Handshake done (any prompt answered): the remaining steps get
                                // a fresh budget instead of whatever the prompt wait left.
                                if (stage == CheckupStage.DEPLOY) startupDeadline.extendBy(STARTUP_TIMEOUT_MS)
                                post(token) { publishCheckup(tracker) }
                            },
                            onUserAuthorisation = { startupDeadline.extendBy(USER_AUTHORISATION_EXTRA_MS) },
                            startup = startup,
                            onControlFailure = { error -> post(token) { fail(error) } },
                            onTouchWritten = { message ->
                                // Checked on the writer thread. Only the diagnostics overlay ever
                                // reads this, and posting unconditionally woke the main thread
                                // once per written touch — up to several hundred times a second
                                // with two fingers — for a handler that immediately found nothing
                                // was being watched and returned.
                                if (_diagnosticsEnabled.value) post(token) { recordWrittenTouch(message) }
                            },
                        )
                    }
                }
                val acquired = handoff.take()
                if (!generations.accepts(token)) {
                    withContext(NonCancellable + Dispatchers.IO) { closeLogged(acquired) }
                    return@launch
                }
                session = acquired
                tracker.advance(CheckupStage.MEDIA)
                publishCheckup(tracker)
                startStreams(acquired, settings, token)
            } catch (e: ExtendableDeadline.Expired) {
                if (generations.accepts(token)) fail(SocketTimeoutException(getApplication<Application>().getString(R.string.session_timeout)))
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                if (generations.accepts(token)) fail(SocketTimeoutException(getApplication<Application>().getString(R.string.session_timeout)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generations.accepts(token)) fail(e)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    closeLogged(handoff)
                    closeLogged(startup)
                }
            }
        }
    }

    private suspend fun awaitStartup(
        handoff: ResourceHandoff<ScrcpySession>,
        startup: StartupResources,
        create: () -> ScrcpySession,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val worker = Thread({
            try {
                handoff.offer(create())
                continuation.resume(Unit)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
                else Log.d(TAG, "Cancelled startup finished", e)
            }
        }, "scrcpy-startup").apply { isDaemon = true }
        continuation.invokeOnCancellation {
            worker.interrupt()
            cleanupScope.launch { closeLogged(startup); closeLogged(handoff) }
        }
        worker.start()
    }

    private fun post(token: Long, action: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (generations.accepts(token)) action()
        }
    }

    private fun startStreams(active: ScrcpySession, settings: MirrorSettings, token: Long) {
        val reader = checkNotNull(active.videoReader)
        val clock = AudioPresentationClock(settings.syncMode == PlaybackSyncMode.SYNCHRONIZED, settings.syncOffsetMs)
        mediaClock = clock
        _mediaTiming.value = clock.diagnostics()
        _videoFrames.value = VideoFrameStats()
        _videoLatency.value = LatencyStats()
        startDiagnosticSampling(clock, token)
        video = VideoDecoder(
            reader,
            onVideoSizeChanged = { width, height, capture ->
                post(token) {
                    // Releases use the latest dimensions; old-size releases may have been ignored.
                    releasePointers(width, height, includeHistory = true)
                    // Re-checked because that send can fail synchronously: the failure runs
                    // `fail` -> `detachSession` on this very thread, retiring this generation.
                    // Publishing Connected afterwards would show a live mirror for a session that
                    // no longer exists and discard the failure that drives reconnection.
                    if (!generations.accepts(token)) return@post
                    pendingSamples.clear()
                    _diagnostics.value = TouchDiagnostics(captureGeneration = capture)
                    _state.value = MirrorUiState.Connected(active.deviceName, width, height, capture, token)
                    establishedSession = true
                    automaticAttempt = false
                    recoveryFailure = null
                    // Refill the retry budget only once the session has proven stable, so a
                    // connect-then-instant-drop loop still backs off and ends.
                    stableJob?.cancel()
                    stableJob = viewModelScope.launch {
                        delay(ConnectionRecoveryPolicy.STABLE_CONNECTION_MILLIS)
                        if (generations.accepts(token)) {
                            recovery = ConnectionRecoveryPolicy.afterConnectedFor(
                                recovery, ConnectionRecoveryPolicy.STABLE_CONNECTION_MILLIS)
                        }
                    }
                }
            },
            onError = { post(token) { fail(it) } },
            onDecodedFormat = { decoded ->
                post(token) {
                    val current = _state.value as? MirrorUiState.Connected
                    if (current?.captureGeneration == decoded.captureGeneration) {
                        _diagnostics.value = _diagnostics.value.copy(
                            decodedWidth = decoded.displayWidth, decodedHeight = decoded.displayHeight)
                    }
                }
            },
            onCaptureSession = { width, height, capture, clientResized ->
                post(token) {
                    val current = _state.value as? MirrorUiState.Connected
                    if (current?.captureGeneration == capture) {
                        desktopResize.onCapture(width, height, clientResized)
                        if (desktopResize.pending == null) {
                            desktopResizeTimeout?.cancel()
                            desktopResizeTimeout = null
                        }
                        _desktopResizing.value = !desktopResize.canRecord
                        scheduleDesktopResize()
                    }
                }
            },
            clock = clock,
            onFirstFrame = {
                post(token) {
                    // The first decoded frame is what "video started" means; until then a
                    // failure belongs to starting the media, not to a running session.
                    // Recorded before the checkup says video started, so anyone who sees that also
                    // finds it in the saved device's history.
                    savedHistoryDeviceId()?.let(connectionHistory::recordConnected)
                    // A picture from the new choice is the proof it works: nothing to go back to.
                    clearRevertibleChoice()
                    checkupTracker?.let {
                        it.advance(CheckupStage.SESSION)
                        publishCheckup(it)
                    }
                }
            },
        ).also { it.start(if (visible) surface else null) }
        active.audioReader?.let { audioReader ->
            // scrcpy uses 0 for a disabled stream and 1 for an initialization error.
            if (audioReader.codecId == ScrcpyCodecs.CODEC_DISABLED || audioReader.codecId == 1) {
                val refused = PlaybackDuplication.refused(active.targetSdk, settings.audioSource, settings.audioDup)
                val app = getApplication<Application>()
                _audioState.value = AudioState(
                    error = when {
                        refused -> app.getString(R.string.audio_dup_refused)
                        // Android 11 only lets a capture start while the device is unlocked, and the
                        // server says so; the generic message left users looking for a fault.
                        AudioRefusal.lockedDevice(active.serverOutput()) ->
                            app.getString(R.string.audio_needs_unlocked_device)
                        // Call audio is reserved for system apps on most devices: say so, rather
                        // than the generic message that suggests something else went wrong.
                        settings.audioSource.privileged -> app.getString(
                            R.string.audio_source_privileged_refused, app.getString(settings.audioSource.labelRes))
                        else -> app.getString(R.string.audio_disabled_target)
                    },
                    dupRefused = refused,
                )
                // The server explains a refusal on its output a moment after the audio socket
                // says it, so look again once the explanation has had time to arrive.
                if (!refused && !settings.audioSource.privileged) viewModelScope.launch(Dispatchers.IO) {
                    val output = active.serverOutputSoon()
                    if (AudioRefusal.lockedDevice(output)) post(token) {
                        if (session === active) _audioState.value = _audioState.value.copy(
                            error = app.getString(R.string.audio_needs_unlocked_device))
                    }
                }
                abandonDeferredRecording()
                cleanupScope.launch { active.disableAudio() }
            } else {
                audio = AudioPlayer(
                    audioReader,
                    onError = { error ->
                        post(token) {
                            _audioState.value = AudioState(error = error.message ?: error.javaClass.simpleName)
                            audio?.stop()
                            audio = null
                            abandonDeferredRecording()
                            // The session carries no audio any more. Without this the service
                            // keeps advertising a media-playback hold for silence and other apps
                            // stay ducked behind focus this session no longer uses.
                            audioFocus.abandon()
                            focusGranted = false
                            applyAudioMute()
                            cleanupScope.launch { active.disableAudio() }
                        }
                    },
                    onPlaying = { post(token) {
                        _audioState.value = AudioState(playing = true)
                        acquireAudioFocus()
                        applyAudioMute()
                        startDeferredRecording()
                    } },
                    clock = clock,
                    bufferMillis = settings.audioBufferMs,
                ).also { it.setMuted(true); it.start() }
            }
        } ?: abandonDeferredRecording()
        active.deviceMessageReader?.let { messages ->
            deviceMessages = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        when (val message = messages.read()) {
                            is DeviceMessage.Clipboard -> post(token) { receiveClipboard(message.text) }
                            is DeviceMessage.AckClipboard -> clipboardAcks.trySend(message.sequence)
                            is DeviceMessage.UhidOutput -> Unit
                        }
                    }
                } catch (e: Exception) {
                    post(token) { fail(e) }
                }
            }, "scrcpy-device-messages").apply { isDaemon = true; start() }
        }
        // Both of these reach the device as control messages a camera capture will not accept,
        // and neither means anything when the source is a lens: there is no screen being shown to
        // blank, and an app launched on the device would not appear in the stream.
        if (settings.camera == null) {
            _deviceScreenOff.value = settings.turnScreenOff && send(ControlMessage.SetDisplayPower(false))
            settings.startApp?.let { packageName ->
                // After the streams exist, so the launch lands on the display this session captures.
                if (!launchApp(packageName)) {
                    _notice.value = getApplication<Application>().getString(R.string.start_app_failed, packageName)
                }
            }
        } else {
            _deviceScreenOff.value = false
        }
        sendQueuedShare()
    }

    private fun startDiagnosticSampling(clock: AudioPresentationClock, token: Long) {
        timingJob?.cancel()
        timingJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(_diagnosticsEnabled, _statsWatched, _statsChip) { overlay, panel, chip ->
                overlay || panel || chip
            }.collectLatest { enabled ->
                if (!enabled) return@collectLatest
                while (isActive && generations.accepts(token) && mediaClock === clock) {
                    sampleDiagnostics(clock, token)
                    delay(ConnectionQualityHistory.SAMPLE_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun sampleDiagnostics(clock: AudioPresentationClock, token: Long) {
        if (!generations.accepts(token) || mediaClock !== clock ||
            (!_diagnosticsEnabled.value && !_statsWatched.value && !_statsChip.value)) return
        val timing = clock.diagnostics()
        val frames = video?.frameStats()
        val latency = video?.latencyStats()
        _mediaTiming.value = timing
        _videoFrames.value = frames ?: VideoFrameStats()
        _videoLatency.value = latency ?: LatencyStats()
        if (_statsWatched.value && visible) {
            val audio = _audioState.value
            _qualityHistory.value = recentQuality.sample(
                SystemClock.elapsedRealtime(), frames, latency, timing,
                audioAvailable = _activeSettings.value?.audioEnabled == true &&
                    audio.playing && audio.error == null,
            )
        }
    }

    fun setSurface(value: Surface) {
        surface = value
        if (visible) {
            video?.setSurface(value)
            send(ControlMessage.resetVideo())
        }
    }

    fun releaseSurface() { surface = null; video?.setSurface(null) }

    fun onVisibilityChanged(value: Boolean) {
        visible = value
        updateStatsWatched()
        val keepAudio = backgroundSessionEnabled
        if (!visible) {
            // A key held as the app goes away would otherwise stay held on the device: its
            // release is delivered to whatever has focus next, not to us. Sent through `send`
            // directly, which has no visibility guard of its own.
            releaseHeldKeys()
            recoveryJob?.cancel()
            recoveryJob = null
            // Cancelling a manual retry's discovery leaves its "Connecting" behind with no work
            // that could ever finish it, because a retry clears the failure it would resume from.
            // Putting back whatever it replaced restores the Retry button, or the live session it
            // was about to reconnect. Only while that "Connecting" is still what is on screen:
            // a failure during the same window has already said something truer, and restoring
            // over it would claim a session that is no longer there.
            stateBeforeRetry?.let {
                if (_state.value is MirrorUiState.Connecting) _state.value = it
                stateBeforeRetry = null
            }
            if (automaticAttempt && session == null) {
                connectJob?.cancel()
                recoveryToken = generations.next()
                automaticAttempt = false
                checkupTracker = null
            }
            if (recoveryFailure != null && session == null) {
                _state.value = MirrorUiState.Failed(failureMessage, failureGuided, failureDetails)
                _checkup.value = failedCheckup
                _failureHistory.value = failedHistory
            }
            if (!keepAudio) {
                audioFocus.abandon()
                focusGranted = false
            }
        } else if (_audioState.value.playing) {
            acquireAudioFocus()
            // A background session never stopped writing, so its timeline is still valid; resetting
            // it would drop the already-buffered audio and re-introduce a gap on every return.
            if (!keepAudio) audio?.resetTimeline()
        }
        if (visible && session == null && connectJob?.isActive != true) scheduleRecovery()
        applyAudioMute()
        if (visible) {
            video?.setSurface(surface)
            if (surface != null) send(ControlMessage.resetVideo())
            scheduleDesktopResize()
        } else {
            desktopResizeJob?.cancel()
            desktopResizeJob = null
            val current = _state.value as? MirrorUiState.Connected
            if (current != null) releasePointers(current.videoWidth, current.videoHeight)
            video?.setSurface(null)
        }
    }

    fun send(message: ControlMessage): Boolean {
        if (input.blocks(message)) {
            reportInputLocked()
            return false
        }
        return input.send(message)
    }

    private fun reportInputLocked() {
        // The persistent unlock button explains the mode. Per-key snackbars would cover it.
        Log.i(TAG, getApplication<Application>().getString(R.string.input_lock_blocked))
    }
    fun sendKey(keycode: Int, metaState: Int = 0) {
        send(ControlMessage.InjectKeycode(ACTION_DOWN, keycode, 0, metaState))
        send(ControlMessage.InjectKeycode(ACTION_UP, keycode, 0, metaState))
    }

    /**
     * Forwards one half of a physical key press, preserving its repeat count and modifiers.
     *
     * Unlike [sendKey] this does not synthesise the matching release: a hardware keyboard reports
     * its own down and up, and inventing an extra release would end a held key early.
     *
     * @return whether the event was actually delivered, so the caller can fall back to local
     * handling instead of silently swallowing keys when no session is connected.
     */
    fun sendKeyEvent(action: Int, keycode: Int, repeat: Int, metaState: Int): Boolean {
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        // The description says when the clip was copied without reading the clip itself, which
        // Android 12 and newer would announce as a paste.
        return sendKeyEvent(action, keycode, repeat, metaState, { clipboard.primaryClipDescription?.timestamp ?: 0L }) {
            clipboard.primaryClip
        }
    }

    /** [sendKeyEvent] with the clip supplied, for the same reason as [pushClipboard]'s. */
    @androidx.annotation.VisibleForTesting
    internal fun sendKeyEvent(
        action: Int,
        keycode: Int,
        repeat: Int,
        metaState: Int,
        phoneClipAt: () -> Long,
        readClip: () -> ClipData?,
    ): Boolean {
        if (!visible || _state.value !is MirrorUiState.Connected) return false
        // A camera capture accepts no injected events, so a hardware key must fall back to local
        // handling rather than being swallowed into a message the device will refuse.
        if (cameraSession) return false
        if (ClipboardKeys.syncsBeforeForwarding(action, keycode, repeat, metaState,
                _activeSettings.value?.clipboardAutosync == true, input.state.locked) &&
            ClipboardKeys.phoneClipIsNewer(phoneClipAt(), deviceClipAtMillis)) {
            // Queued ahead of the key, so the device pastes this phone's clipboard. A clip that is
            // only a URI is read in the background and may arrive just after it. A newer copy the
            // device sent while the mirror was hidden stays, and is what the key pastes.
            pushClipboard(paste = false, readClip)
        }
        val sent = send(ControlMessage.InjectKeycode(action, keycode, repeat, metaState))
        return sent
    }

    /**
     * Releases every key the device still believes is held.
     *
     * Sent with no modifiers and no repeat: this is a cleanup, and the meta state that went with
     * the original press is no longer meaningful.
     */
    fun releaseHeldKeys() {
        input.releaseKeys()
    }

    /** Whether a mouse Back/Forward button press would actually be forwarded by [sendMouseButton]. */
    val forwardsMouseButtons: Boolean
        get() = MouseButtonPolicy.consumesKey(_state.value is MirrorUiState.Connected, cameraSession, visible)

    /**
     * The on-screen draft field has taken or given up the keyboard.
     *
     * While it holds focus, keystrokes compose local text instead of reaching the device, so any
     * key still held has to be released now — its own release will never arrive.
     */
    fun onLocalTypingChanged(typing: Boolean) {
        if (typing) releaseHeldKeys()
    }
    /**
     * Live keyboard: applies the edits that bring the device's text to what the local field shows,
     * as the user types. ASCII travels as text injection, keeping the device's own IME out of the
     * way; other scripts are pasted, the only path that carries every character intact.
     *
     * @return whether every edit was queued; false keeps the caller's record of what was sent.
     */
    fun applyLiveEdits(edits: List<LiveTyping.Edit>): Boolean {
        if (edits.isEmpty()) return true
        if (input.state.locked) { reportInputLocked(); return false }
        if (!canQueueLiveInput()) return false
        edits.forEach { liveEdits.trySend(it) }
        ensureLiveTypist()
        return true
    }

    /** Acknowledgements of clipboard sequences, read by the live typist. */
    private val clipboardAcks = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val liveEdits = kotlinx.coroutines.channels.Channel<LiveTyping.Edit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var liveTypist: kotlinx.coroutines.Job? = null

    /** Live edits only make sense against a connected session; otherwise they would replay later. */
    internal fun canQueueLiveInput(): Boolean =
        _state.value is MirrorUiState.Connected && session != null

    /** Stops the typist and drops queued edits and stale acks so nothing replays elsewhere. */
    internal fun resetLiveTyping() {
        liveTypist?.cancel()
        liveTypist = null
        drainChannel(liveEdits)
        drainChannel(clipboardAcks)
    }

    /**
     * Applies live edits one at a time, in order. A paste is only finished once the device has
     * taken the clipboard and its app has read it, so nothing that follows (another paste, a
     * Delete, an arrow) is sent before then; otherwise the next clip replaces this one before it
     * is pasted, and keys overtake text. Inserts queued meanwhile are joined into one paste.
     */
    private fun ensureLiveTypist() {
        if (liveTypist?.isActive == true) return
        liveTypist = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                // Everything queued so far, merged, so per-keystroke round trips collapse into one.
                val batch = mutableListOf(liveEdits.receive())
                kotlinx.coroutines.delay(LIVE_BATCH_MS)
                while (true) batch.add(liveEdits.tryReceive().getOrNull() ?: break)
                for (edit in LiveTyping.coalesce(batch)) {
                    val ok = when (edit) {
                        is LiveTyping.Edit.Move -> repeatKey(
                            if (edit.by < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
                            kotlin.math.abs(edit.by))
                        is LiveTyping.Edit.Delete -> repeatKey(KeyEvent.KEYCODE_DEL, edit.count)
                        is LiveTyping.Edit.Insert -> pasteAndSettle(edit.text)
                        is LiveTyping.Edit.Key ->
                            send(ControlMessage.InjectKeycode(ACTION_DOWN, edit.keycode, 0, edit.metaState)) &&
                                send(ControlMessage.InjectKeycode(ACTION_UP, edit.keycode, 0, edit.metaState))
                    }
                    if (!ok) viewModelScope.launch(Dispatchers.Main.immediate) {
                        _notice.value = getApplication<Application>().getString(R.string.input_text_not_queued)
                    }
                }
            }
        }
    }

    private suspend fun pasteAndSettle(text: String): Boolean {
        val bounded = boundedClipboard(text) ?: return false
        val sequence = clipboardSequence.getAndIncrement()
        while (clipboardAcks.tryReceive().isSuccess) Unit
        if (!send(ControlMessage.SetClipboard(sequence, bounded, true))) return false
        // The ack says the clipboard is set and the paste injected; the app still reads the clip
        // asynchronously, so give it a moment before the clipboard may change again.
        kotlinx.coroutines.withTimeoutOrNull(LIVE_ACK_TIMEOUT_MS) {
            while (clipboardAcks.receive() < sequence) Unit
        }
        kotlinx.coroutines.delay(LIVE_PASTE_SETTLE_MS)
        return true
    }

    /**
     * A key from the keyboard panel, queued behind any live edits still on their way so it acts on
     * the text the user sees, not on a version the device has not caught up with.
     */
    fun sendRemoteKey(keycode: Int, metaState: Int) {
        if (input.state.locked) { reportInputLocked(); return }
        if (!canQueueLiveInput()) return
        liveEdits.trySend(LiveTyping.Edit.Key(keycode, metaState))
        ensureLiveTypist()
    }

    private fun repeatKey(keycode: Int, times: Int): Boolean {
        repeat(times) {
            if (!send(ControlMessage.InjectKeycode(ACTION_DOWN, keycode))) return false
            if (!send(ControlMessage.InjectKeycode(ACTION_UP, keycode))) return false
        }
        return true
    }

    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return false
        if (input.state.locked) { reportInputLocked(); return false }
        // Clipboard paste commits IME-composed Unicode; key injection cannot represent every script.
        val bounded = boundedClipboard(text) ?: return false
        val accepted = send(ControlMessage.SetClipboard(clipboardSequence.getAndIncrement(), bounded, true))
        if (!accepted) _notice.value = getApplication<Application>().getString(R.string.input_text_not_queued)
        return accepted
    }

    fun sendTouch(action: Int, pointerId: Long, x: Int, y: Int, pressure: Float,
                  screenWidth: Int, screenHeight: Int, rawX: Float = 0f, rawY: Float = 0f,
                  sessionGeneration: Long? = null, captureGeneration: Long? = null,
                  inputGeneration: Long? = null) {
        val current = _state.value as? MirrorUiState.Connected ?: return
        if (!visible || screenWidth != current.videoWidth || screenHeight != current.videoHeight) return
        if (sessionGeneration != null && sessionGeneration != current.sessionGeneration) return
        if (captureGeneration != null && captureGeneration != current.captureGeneration) return
        // A camera capture has no screen to touch, and the server will not inject events for one.
        // Sending anyway spends bandwidth on messages the device is obliged to refuse, and leaves
        // this client tracking pointers the device never accepted — so they stop here, where the
        // held-pointer bookkeeping below would otherwise start believing in them.
        if (cameraSession) return
        val message = ControlMessage.InjectTouchEvent(action, pointerId, x, y, screenWidth, screenHeight, pressure)
        if (!input.send(message, inputGeneration)) return
        if (diagnosticsEnabled) {
            val sample = _diagnostics.value.copy(
                lastRawX = rawX, lastRawY = rawY, lastDeviceX = x, lastDeviceY = y,
                lastScreenWidth = screenWidth, lastScreenHeight = screenHeight,
                captureGeneration = current.captureGeneration, written = false)
            pendingSamples[pointerId] = message to sample
            _diagnostics.value = sample
        }
    }

    /**
     * Forwards a wheel or trackpad scroll. Unlike touches this carries no pointer, so nothing is
     * tracked for release; a stale scroll can only be a no-op, never a stuck pointer.
     */
    fun sendScroll(x: Int, y: Int, horizontal: Float, vertical: Float,
                   screenWidth: Int, screenHeight: Int,
                   sessionGeneration: Long? = null, captureGeneration: Long? = null,
                   inputGeneration: Long? = null) {
        val current = _state.value as? MirrorUiState.Connected ?: return
        if (!visible || screenWidth != current.videoWidth || screenHeight != current.videoHeight) return
        if (sessionGeneration != null && sessionGeneration != current.sessionGeneration) return
        if (captureGeneration != null && captureGeneration != current.captureGeneration) return
        if (cameraSession) return
        input.send(ControlMessage.InjectScrollEvent(x, y, screenWidth, screenHeight, horizontal, vertical),
            inputGeneration)
    }

    fun sendMouseButton(keycode: Int, sessionGeneration: Long, captureGeneration: Long, inputGeneration: Long) {
        val current = _state.value as? MirrorUiState.Connected ?: return
        if (!visible || cameraSession || current.sessionGeneration != sessionGeneration ||
            current.captureGeneration != captureGeneration) return
        input.send(ControlMessage.InjectKeycode(ACTION_DOWN, keycode), inputGeneration)
        input.send(ControlMessage.InjectKeycode(ACTION_UP, keycode), inputGeneration)
    }

    private fun recordWrittenTouch(message: ControlMessage.InjectTouchEvent) {
        val pending = pendingSamples[message.pointerId] ?: return
        val current = _state.value as? MirrorUiState.Connected ?: return
        if (pending.first === message && pending.second.captureGeneration == current.captureGeneration) {
            _diagnostics.value = pending.second.copy(
                written = true, decodedWidth = _diagnostics.value.decodedWidth,
                decodedHeight = _diagnostics.value.decodedHeight)
            pendingSamples.remove(message.pointerId)
        }
    }

    /**
     * Tells the device to lift every pointer it may still believe is down, at [width] x [height].
     *
     * @param includeHistory also re-release pointers already released at an earlier capture size.
     * The server drops a touch whose screen size does not match the current capture, so a release
     * sent just before a rotation can be discarded and leave that finger pressed. The history is
     * therefore kept rather than consumed: every later capture change repeats the release at the
     * newest size, and a repeat for a finger that did lift is ignored by the server. It is bounded
     * by the handful of pointer ids the platform reuses, and cleared with the session.
     */
    private fun releasePointers(width: Int, height: Int, includeHistory: Boolean = false) {
        input.releasePointers(width, height, includeHistory)
    }

    /**
     * When the device last sent its clipboard, whether or not it was put on this phone's: a copy
     * made on the device while the mirror was hidden is newer than this phone's clip. Kept across
     * reconnections to the same device, which still holds that copy.
     */
    private var deviceClipAtMillis: Long? = null

    private fun receiveClipboard(text: String) {
        deviceClipAtMillis = System.currentTimeMillis()
        if (!visible) return
        getApplication<Application>().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("scrcpy", text))
    }

    /**
     * Blanks or restores the mirrored device's own display while the stream keeps running.
     *
     * scrcpy applies `--turn-screen-off` once at startup; exposing it live lets a user darken the
     * other device after connecting, which is the common reason to want it at all.
     */
    fun toggleDeviceScreen(): Boolean {
        val turnOff = !_deviceScreenOff.value
        if (!send(ControlMessage.SetDisplayPower(!turnOff))) return _deviceScreenOff.value
        _deviceScreenOff.value = turnOff
        return turnOff
    }

    /**
     * Loads the device's launchable apps once per session.
     *
     * The listing runs the server in its list-only mode on the device, which takes seconds on a
     * device with many apps, so it is explicit rather than done while connecting. A failure is
     * reported in place instead of ending the session: the mirror itself is unaffected. A list
     * already loaded is kept, but asked about apps whose uninstall question is still unsettled.
     */
    fun loadApps(force: Boolean = false) {
        val current = _apps.value
        if (current.loading) return
        if (current.loaded && !force) {
            confirmRemovals()
            return
        }
        val active = session
        if (active == null) {
            // Reachable: the dialog outlives a session that drops while it is open. Reporting the
            // real reason beats showing an empty list as though the device had no apps.
            _apps.value = DeviceAppsState(
                error = getApplication<Application>().getString(R.string.apps_needs_session)
            )
            return
        }
        val token = generations.token()
        _apps.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { active.listApps() } }
            // A superseded request must not touch the state: the session it belonged to has
            // already been torn down, and whatever is there now belongs to the current one.
            if (!generations.accepts(token)) return@launch
            _apps.value = result.fold(
                onSuccess = { apps ->
                    // Settled once the device lists an app no more. Still listed, its question may
                    // be unanswered yet, so it is asked about again on the next opening.
                    pendingRemovals.retainAll { pending -> apps.any { it.packageName == pending } }
                    DeviceAppsState(apps = apps, loaded = true)
                },
                onFailure = { error ->
                    Log.w(TAG, "Listing device apps failed", error)
                    val message = getApplication<Application>().getString(R.string.apps_list_failed)
                    // A list already shown survives a failed refresh.
                    if (current.loaded) current.copy(loading = false, error = message)
                    else DeviceAppsState(error = message, loaded = false)
                },
            )
        }
    }

    /**
     * Takes off the loaded list any app whose uninstall question this session opened and that the
     * device no longer has. One quick package query each, rather than listing every app again.
     */
    private fun confirmRemovals() {
        val active = session ?: return
        if (pendingRemovals.isEmpty() || removalCheck?.isActive == true) return
        val asked = pendingRemovals.toList()
        val token = generations.token()
        removalCheck = viewModelScope.launch {
            val removed = asked.filter { packageName ->
                val command = DeviceAppPages.installedCheckCommand(packageName) ?: return@filter false
                val output = try {
                    withContext(Dispatchers.IO) { active.runAppCommand(command) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not ask whether $packageName is still installed", e)
                    return@filter false
                }
                DeviceAppPages.stillInstalled(output, packageName) == false
            }
            if (!generations.accepts(token) || removed.isEmpty()) return@launch
            pendingRemovals.removeAll(removed.toSet())
            // A list reloading meanwhile, or failed, is replaced by its own answer.
            val state = _apps.value
            if (state.loaded && !state.loading) {
                _apps.value = state.copy(apps = state.apps.filterNot { it.packageName in removed })
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun appRemovalCheckRunning(): Boolean = removalCheck?.isActive == true

    /**
     * Asks the device which video encoders it has.
     *
     * Separate from [loadApps] rather than generalised with it: the two share a shape but not a
     * lifetime, and folding them together would make one list's failure clear the other.
     */
    fun loadEncoders(force: Boolean = false) =
        loadEncoderList(_encoders, force) { it.listVideoEncoders() }

    /** Audio encoders reported by the device, loaded on demand. */
    fun loadAudioEncoders(force: Boolean = false) =
        loadEncoderList(_audioEncoders, force) { it.listAudioEncoders() }

    /**
     * Cameras the device reports, loaded on demand.
     *
     * The device's own refusal is passed through rather than replaced by a generic message: the
     * usual reason for failure here is that the device predates Android 12, and saying so saves
     * the user hunting for a fault that is not theirs.
     */
    fun loadCameras(force: Boolean = false) {
        val current = _cameras.value
        if (current.loading || (current.loaded && !force)) return
        val active = session
        if (active == null) {
            _cameras.value = DeviceCamerasState(
                error = getApplication<Application>().getString(R.string.apps_needs_session)
            )
            return
        }
        val token = generations.token()
        _cameras.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { active.listCameras() } }
            if (!generations.accepts(token)) return@launch
            _cameras.value = result.fold(
                onSuccess = { DeviceCamerasState(cameras = it, loaded = true) },
                onFailure = { error ->
                    Log.w(TAG, "Listing device cameras failed", error)
                    val message = cameraListFailureMessage(error)
                        ?: getApplication<Application>().getString(R.string.cameras_list_failed)
                    if (current.loaded) current.copy(loading = false, error = message)
                    else DeviceCamerasState(error = message, loaded = false)
                },
            )
        }
    }

    /** Whether this session is watching a camera rather than a screen. */

    private val cameraSession: Boolean get() = _activeSettings.value?.camera != null



    /** Turns the remote camera's light on or off; meaningless outside a camera session. */
    fun setCameraTorch(on: Boolean) {
        if (send(ControlMessage.CameraSetTorch(on))) _cameraTorch.value = on
    }

    /** Steps the remote camera's zoom. The device owns the limits, so this only asks. */
    fun zoomCamera(inward: Boolean) {
        send(if (inward) ControlMessage.cameraZoomIn() else ControlMessage.cameraZoomOut())
    }

    /** Displays the device reports, loaded on demand. */
    fun loadDisplays(force: Boolean = false) {        val current = _displays.value
        if (current.loading || (current.loaded && !force)) return
        val active = session
        if (active == null) {
            _displays.value = DeviceDisplaysState(
                error = getApplication<Application>().getString(R.string.apps_needs_session)
            )
            return
        }
        val token = generations.token()
        _displays.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { active.listDisplays() } }
            // As in loadApps: a superseded result belongs to a session that is already gone.
            if (!generations.accepts(token)) return@launch
            _displays.value = result.fold(
                onSuccess = { DeviceDisplaysState(displays = it, loaded = true) },
                onFailure = { error ->
                    Log.w(TAG, "Listing device displays failed", error)
                    val message = getApplication<Application>().getString(R.string.displays_list_failed)
                    if (current.loaded) current.copy(loading = false, error = message)
                    else DeviceDisplaysState(error = message, loaded = false)
                },
            )
        }
    }

    /**
     * Shared by both encoder lists. Unlike [loadApps] these two really are the same operation on
     * different state, so the only thing that differs is which list the device is asked for.
     */
    private fun loadEncoderList(
        state: kotlinx.coroutines.flow.MutableStateFlow<DeviceEncodersState>,
        force: Boolean,
        fetch: (ScrcpySession) -> List<DeviceEncoder>,
    ) {
        val current = state.value
        if (current.loading || (current.loaded && !force)) return
        val active = session
        if (active == null) {
            state.value = DeviceEncodersState(
                error = getApplication<Application>().getString(R.string.apps_needs_session)
            )
            return
        }
        val token = generations.token()
        state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetch(active) } }
            // As in loadApps: a superseded result belongs to a session that is already gone.
            if (!generations.accepts(token)) return@launch
            state.value = result.fold(
                onSuccess = { DeviceEncodersState(encoders = it, loaded = true) },
                onFailure = { error ->
                    Log.w(TAG, "Listing device encoders failed", error)
                    val message = getApplication<Application>().getString(R.string.encoders_list_failed)
                    if (current.loaded) current.copy(loading = false, error = message)
                    else DeviceEncodersState(error = message, loaded = false)
                },
            )
        }
    }

    /**
     * Sends the document at [uri] to the device's Download folder.
     *
     * One transfer at a time: the sync channel is opened on the same ADB connection the session
     * uses, and a second concurrent push would interleave with the first on that connection.
     *
     * An app package is asked about instead of sent, unless [offerInstall] is false because the
     * user already chose to copy it: installing it is usually what sending one to a phone means.
     */
    fun sendFile(
        uri: android.net.Uri,
        displayName: String? = null,
        offerInstall: Boolean = true,
        onFinished: () -> Unit = {},
    ) {
        if (_fileTransfer.value.running) return
        val context = getApplication<Application>()
        val active = session
        if (active == null) {
            _transferHoldsProcess.value = false
            _notice.value = context.getString(R.string.send_file_needs_session)
            onFinished()
            return
        }
        val token = generations.token()
        val serial = ++fileTransferSerial
        // The slot is reserved before anything blocking happens, so reading the file's metadata
        // can move off the main thread without opening a window for a second transfer.
        _fileTransfer.value = FileTransferState(running = true, name = displayName.orEmpty())
        val signal = android.os.CancellationSignal()
        fileTransferSignal = signal
        val channels = ResourceHandoff<java.io.Closeable>()
        fileTransferChannel = java.io.Closeable { channels.close() }
        val sources = ResourceHandoff<java.io.Closeable>()
        fileTransferSource = java.io.Closeable { sources.close() }
        fileTransferJob = viewModelScope.launch {
            // Every exit releases the slot, cancellation included. `running` is the only thing
            // stopping a second push from opening on the same ADB connection, so a worker that
            // died without clearing it refused every later transfer for the rest of the session
            // — and cancelling while a slow provider answered describe() did exactly that.
            var finished = onFinished
            try {
                // describe() queries a content provider and opens a descriptor. For a cloud-backed
                // document that can take seconds, which on the main thread would freeze the mirror.
                val described = withContext(Dispatchers.IO) {
                    runCatching { FileTransfers.describe(context.contentResolver, uri, signal) }
                        .onFailure { Log.w(TAG, "Could not read the chosen file", it) }
                        .getOrNull()
                }
                val details = described?.let { if (displayName.isNullOrBlank()) it else it.copy(name = displayName) }
                if (details == null) {
                    if (owns(serial) && generations.accepts(token)) {
                        _notice.value = context.getString(R.string.send_file_unreadable)
                    }
                    return@launch
                }
                if (details.size > FileTransfers.MAX_BYTES) {
                    if (owns(serial) && generations.accepts(token)) {
                        _notice.value = context.getString(
                            R.string.send_file_too_large, FileTransfers.formatSize(FileTransfers.MAX_BYTES))
                    }
                    return@launch
                }
                if (offerInstall && (ApkInstall.looksLikeApk(details.name, details.mimeType) ||
                        AppBundle.looksLikeBundle(details.name, details.mimeType))) {
                    // The question owns the file from here: a share's copy is kept for whichever
                    // answer comes, and deleted only once there is one.
                    if (owns(serial) && generations.accepts(token)) {
                        val local = uri.takeIf { it.scheme == android.content.ContentResolver.SCHEME_FILE }
                            ?.path?.let { java.io.File(it) }
                        prepareAppOffer(uri, details, canCopy = true, local = local, onFinished = finished)
                        finished = {}
                    }
                    return@launch
                }
                _fileTransfer.value = FileTransferState(
                    running = true, name = details.name, totalBytes = details.size)
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        FileTransfers.withSource(
                            context.contentResolver, uri, signal, sources::offer, sources::release,
                        ) { input ->
                            active.sendFile(
                                source = input,
                                size = details.size,
                                displayName = details.name,
                                onProgress = { sent ->
                                    if (owns(serial)) {
                                        _fileTransfer.value = _fileTransfer.value.copy(sentBytes = sent)
                                    }
                                },
                                isCancelled = { !isActive || !generations.accepts(token) },
                                onChannelOpen = channels::offer,
                                onChannelClosed = channels::release,
                                // Synchronous on this worker, before DONE, so a session ending
                                // at any moment reads whether the device may have the file.
                                onCommit = {
                                    if (owns(serial)) {
                                        _fileTransfer.value = _fileTransfer.value.copy(committing = true)
                                    }
                                },
                            )
                        }
                    }
                }
                if (!owns(serial) || !generations.accepts(token)) return@launch
                result.fold(
                    onSuccess = { sent ->
                        _notice.value = context.getString(
                            // The file is there either way; the difference is whether the device
                            // will show it. Saying "sent" when it will not appear in Files sends
                            // the user looking for something they cannot find.
                            if (sent.scanRequested) R.string.send_file_done else R.string.send_file_not_indexed,
                            details.name,
                        )
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Sending a file failed", error)
                        _notice.value = context.getString(FileTransfers.failureNotice(error), details.name)
                    },
                )
            } finally {
                finishFileTransfer(serial, finished)
            }
        }
    }

    /** Whether the transfer that took [serial] is still the one holding the slot. */
    private fun owns(serial: Long) = fileTransferSerial == serial

    /**
     * Releases the transfer slot, but only for the transfer that still owns it.
     *
     * A cancelled worker keeps unwinding after [cancelFileTransfer] returns. Without this check
     * its tail would clear a replacement transfer's progress and drop a hold that is still in
     * use. Returns whether this transfer was still the current one.
     */
    private fun finishFileTransfer(serial: Long, onFinished: () -> Unit): Boolean {
        val owned = owns(serial)
        if (owned) {
            fileTransferChannel = null
            fileTransferSource = null
            fileTransferSignal = null
            _fileTransfer.value = FileTransferState()
            _transferHoldsProcess.value = false
        }
        onFinished()
        // The slot is free again, so anything that arrived while it was busy can go now rather
        // than waiting for whatever happens to start a stream next.
        if (owned && session != null) sendQueuedShare()
        return owned
    }

    /**
     * Abandons an in-flight transfer; the device discards the partial file.
     *
     * The state is deliberately left alone: `running` is the only thing stopping a second push
     * from being opened on the same ADB connection, where the two would interleave and corrupt
     * each other. The worker clears it once it has actually stopped.
     */
    fun cancelFileTransfer() {
        fileTransferJob?.cancel()
        cancelTransferResources()
    }

    private fun cancelTransferResources() {
        val signal = fileTransferSignal
        fileTransferSignal = null
        val channel = fileTransferChannel
        fileTransferChannel = null
        val source = fileTransferSource
        fileTransferSource = null
        if (channel == null && source == null && signal == null) return
        // CLSE can wait behind a blocked ADB write, and provider cancellation can be blocking IPC.
        // Neither may prevent Main from reaching the physical disconnect that releases the writer.
        val channelJob = channel?.let {
            cleanupScope.launch { closeLogged(it) }
        }
        val sourceJob = source?.let {
            cleanupScope.launch { closeLogged(it) }
        }
        val signalJob = signal?.let {
            cleanupScope.launch {
                try { it.cancel() }
                catch (e: Exception) { Log.w(TAG, "Cancelling the document provider failed", e) }
            }
        }
        val preceding = transferCleanupJob
        transferCleanupJob = cleanupScope.launch {
            preceding?.join()
            channelJob?.join()
            sourceJob?.join()
            signalJob?.join()
        }
    }

    /** The user picked a file to install from the device's app list. */
    fun installPickedApp(uri: android.net.Uri) {
        prepareAppOffer(uri, details = null, canCopy = false, local = null, onFinished = {})
    }

    /**
     * Reads an app package on this phone, then asks what to do with it.
     *
     * A picked document is copied into the cache first: reading an app's name and icon needs a
     * file path, and a document provider only hands out a stream. The copy is also what is sent,
     * so a slow provider cannot stall the transfer halfway. [local] is a file that needs no copy,
     * such as a share's. [onFinished] runs once whatever is decided is done with the file.
     */
    private fun prepareAppOffer(
        uri: android.net.Uri,
        details: FileTransfers.Details?,
        canCopy: Boolean,
        local: java.io.File?,
        onFinished: () -> Unit,
    ) {
        endAppOffer()
        val context = getApplication<Application>()
        val serial = ++appOfferSerial
        appOfferFinished = onFinished
        // Nothing reaches the device while the user decides, so nothing has to hold the process.
        if (!_fileTransfer.value.running) _transferHoldsProcess.value = false
        _appOffer.value = AppOffer(name = details?.name.orEmpty(), size = details?.size ?: 0L, canCopy = canCopy)
        val iconSize = (APP_ICON_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        appOfferJob = viewModelScope.launch {
            var copy: java.io.File? = null
            var adopted = false
            try {
                val described = details ?: withContext(Dispatchers.IO) {
                    runCatching { FileTransfers.describe(context.contentResolver, uri) }
                        .onFailure { Log.w(TAG, "Could not read the chosen app", it) }
                        .getOrNull()
                }
                if (described == null) {
                    refuseAppOffer(serial, context.getString(R.string.send_file_unreadable))
                    return@launch
                }
                if (described.size > FileTransfers.MAX_BYTES) {
                    refuseAppOffer(serial, context.getString(
                        R.string.send_file_too_large, FileTransfers.formatSize(FileTransfers.MAX_BYTES)))
                    return@launch
                }
                if (serial != appOfferSerial) return@launch
                _appOffer.value = _appOffer.value?.copy(name = described.name, size = described.size)
                // The copy is claimed inside the IO block: returning to Main is cancellable, and a
                // result that arrives after the offer ended is dropped there, which would leave
                // the file it names behind. A dismissed offer stops the copy between chunks, and
                // the finally below deletes whatever it wrote.
                val file = local ?: withContext(NonCancellable + Dispatchers.IO) {
                    ShareCache.copyIn(context, uri, isCancelled = { appOfferSerial != serial })
                        ?.let { java.io.File(it.path) }
                        .also { copy = it }
                }
                if (serial != appOfferSerial) return@launch
                if (file == null) {
                    refuseAppOffer(serial, context.getString(R.string.send_file_unreadable))
                    return@launch
                }
                val single = withContext(Dispatchers.IO) { ApkDetails.read(context, file, iconSize) }
                if (serial != appOfferSerial) return@launch
                // A file Android reads as one app is one, whatever else it holds; anything else may
                // be several that install together.
                val bundle = if (single != null) null else withContext(Dispatchers.IO) { AppBundle.read(file, described.name) }
                if (serial != appOfferSerial) return@launch
                val app = single ?: bundle?.takeIf { it.problem != AppBundle.Problem.UNREADABLE }
                    ?.let { withContext(Dispatchers.IO) { readBundleApp(context, file, it, iconSize) } }
                if (serial != appOfferSerial) return@launch
                val summary = bundle?.let { BundleSummary(it.files, it.problem, it.unmatchable) }
                if (summary?.problem != null && !canCopy) {
                    // Picked to install, and it cannot be: say why rather than ask.
                    refuseAppOffer(serial, summary.problemText(context).orEmpty())
                    return@launch
                }
                if (app == null && bundle == null && !canCopy && !ApkInstall.looksLikeApk(described.name, described.mimeType)) {
                    refuseAppOffer(serial, context.getString(R.string.install_not_an_app))
                    return@launch
                }
                appOfferFile = file
                appOfferCopy = copy
                appOfferBundle = bundle
                adopted = true
                _appOffer.value = _appOffer.value?.copy(reading = false, app = app, bundle = summary)
            } finally {
                if (!adopted) copy?.let { ShareCache.discard(it.path) }
            }
        }
    }

    /**
     * Reads a bundle's base as an app, for its name and icon: Android reads an app only from a file
     * of its own, so the base is copied out and the copy deleted at once.
     */
    private fun readBundleApp(context: Application, file: java.io.File, bundle: AppBundle, iconSize: Int): ApkDetails? {
        val base = try {
            java.io.File.createTempFile("bundle-base-", ".apk", context.cacheDir)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not make room to read the bundle's base", e)
            return null
        }
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry(bundle.baseEntry) ?: return null
                if (entry.size > FileTransfers.MAX_BYTES) return null
                zip.getInputStream(entry).use { input ->
                    base.outputStream().use { AppBundle.copyBounded(input, it, FileTransfers.MAX_BYTES) }
                }
            }
            ApkDetails.read(context, base, iconSize)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not read the bundle's base", e)
            null
        } finally {
            base.delete()
        }
    }

    private fun refuseAppOffer(serial: Long, notice: String) {
        if (serial != appOfferSerial) return
        _notice.value = notice
        endAppOffer()
    }

    /**
     * Ends the waiting offer, if any: its copy is deleted and its source told it is done with,
     * which deletes a share's copy too.
     */
    private fun endAppOffer() {
        appOfferSerial++
        appOfferJob?.cancel()
        appOfferJob = null
        appOfferFile = null
        appOfferBundle = null
        appOfferCopy?.let { ShareCache.discard(it.path) }
        appOfferCopy = null
        val finished = appOfferFinished
        appOfferFinished = null
        _appOffer.value = null
        finished?.invoke()
    }

    /** The user decided against doing anything with the offered app. */
    fun dismissAppOffer() = endAppOffer()

    /**
     * Installs the offered app, or with [install] false copies it to Download as any other file.
     *
     * The offer stays when the answer cannot be carried out yet, so it can be given again: while
     * another file is being sent, or before a dropped session has reconnected.
     */
    fun acceptAppOffer(install: Boolean) {
        val offer = _appOffer.value ?: return
        val file = appOfferFile ?: return
        if (!install && !offer.canCopy) return
        // The dialog offers no Install for a bundle that cannot be installed; nor does this.
        if (install && offer.bundle?.problem != null) return
        val context = getApplication<Application>()
        if (_fileTransfer.value.running) {
            _notice.value = context.getString(if (install) R.string.install_busy else R.string.send_file_busy)
            return
        }
        if (session == null) {
            _notice.value = context.getString(if (install) R.string.install_needs_session else R.string.send_file_needs_session)
            return
        }
        val copy = appOfferCopy
        val finished = appOfferFinished
        val bundle = appOfferBundle
        appOfferSerial++
        appOfferJob = null
        appOfferFile = null
        appOfferBundle = null
        appOfferCopy = null
        appOfferFinished = null
        _appOffer.value = null
        val done = {
            copy?.let { ShareCache.discard(it.path) }
            finished?.invoke()
            Unit
        }
        // Claimed while this app is in front: leaving it mid-transfer must not freeze the session.
        _transferHoldsProcess.value = true
        if (install) installApp(file, offer, bundle, done)
        else sendFile(android.net.Uri.fromFile(file), offer.name, offerInstall = false, onFinished = done)
    }

    /**
     * Installs [file] on the device through the transfer slot, then says how it went.
     *
     * The slot is held until the device has answered, not only while the file is copied over:
     * the dialog it drives is the user's one view of whether the install finished.
     */
    private fun installApp(file: java.io.File, offer: AppOffer, bundle: AppBundle?, onFinished: () -> Unit) {
        val context = getApplication<Application>()
        val active = session
        if (active == null) {
            _transferHoldsProcess.value = false
            _notice.value = context.getString(R.string.install_needs_session)
            onFinished()
            return
        }
        val token = generations.token()
        val serial = ++fileTransferSerial
        val name = offer.app?.label ?: offer.name
        // A bundle's size is that of the files the device gets, known once the device has said what it is.
        val size = if (bundle == null) file.length() else 0L
        _fileTransfer.value = FileTransferState(running = true, name = name, totalBytes = size, install = true)
        val channels = ResourceHandoff<java.io.Closeable>()
        fileTransferChannel = java.io.Closeable { channels.close() }
        fileTransferJob = viewModelScope.launch {
            try {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val onProgress = { sent: Long ->
                            if (owns(serial)) _fileTransfer.value = _fileTransfer.value.copy(sentBytes = sent)
                        }
                        val isCancelled = { !isActive || !generations.accepts(token) }
                        val onInstalling = {
                            if (owns(serial)) _fileTransfer.value = _fileTransfer.value.copy(installing = true)
                        }
                        if (bundle == null) {
                            file.inputStream().use { input ->
                                ApkInstall.parse(active.installApp(
                                    source = input,
                                    size = size,
                                    onProgress = onProgress,
                                    isCancelled = isCancelled,
                                    onChannelOpen = channels::offer,
                                    onChannelClosed = channels::release,
                                    onInstalling = onInstalling,
                                ))
                            }
                        } else {
                            when (val choice = chooseBundleFiles(active, bundle)) {
                                is BundleChoice.Install -> java.util.zip.ZipFile(file).use { zip ->
                                    if (!AppBundle.fitsWithin(choice.parts, FileTransfers.MAX_BYTES)) throw BundleTooLargeException()
                                    val total = choice.parts.sumOf { it.size }
                                    if (owns(serial)) _fileTransfer.value = _fileTransfer.value.copy(totalBytes = total)
                                    ApkInstall.parse(active.installParts(
                                        parts = choice.parts.map { part ->
                                            ScrcpySession.UploadPart(part.size) {
                                                zip.getInputStream(zip.getEntry(part.entry)
                                                    ?: throw java.io.FileNotFoundException(part.entry))
                                            }
                                        },
                                        onProgress = onProgress,
                                        isCancelled = isCancelled,
                                        onChannelOpen = channels::offer,
                                        onChannelClosed = channels::release,
                                        onInstalling = onInstalling,
                                    ))
                                }
                                else -> bundleRefusal(choice)
                            }
                        }
                    }
                }
                if (!owns(serial) || !generations.accepts(token)) return@launch
                result.fold(
                    onSuccess = { outcome ->
                        _installOutcome.value = AppInstallOutcome(name, offer.app?.packageName ?: bundle?.packageName, outcome)
                        // The app list predates this app. Reading it again shows it there, and is
                        // how the outcome learns whether the new app has anything to open.
                        if (outcome == ApkInstall.Result.Installed) loadApps(force = true)
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "Installing an app failed", error)
                        if (error is BundleTooLargeException) {
                            _notice.value = context.getString(
                                R.string.send_file_too_large, FileTransfers.formatSize(FileTransfers.MAX_BYTES))
                            return@fold
                        }
                        _notice.value = context.getString(
                            if (_fileTransfer.value.installing) R.string.install_unconfirmed
                            else R.string.install_send_failed,
                            name,
                        )
                    },
                )
            } finally {
                finishFileTransfer(serial, onFinished)
            }
        }
    }

    /** Asks the device what it is, then which of [bundle]'s files it needs; null when it would not say. */
    private fun chooseBundleFiles(active: ScrcpySession, bundle: AppBundle): BundleChoice? =
        DeviceSpecs.parse(active.describeDevice())?.let { BundleSelection.choose(bundle, it) }

    /** A bundle refused on this phone, before anything was sent, in words of its own. */
    private fun bundleRefusal(choice: BundleChoice?): ApkInstall.Result.Refused {
        val context = getApplication<Application>()
        val detail = when (choice) {
            is BundleChoice.NoMatchingProcessor -> context.getString(R.string.install_bundle_processor_detail,
                choice.offered.joinToString(", "), choice.device.joinToString(", "))
            is BundleChoice.NeedsNewerAndroid -> context.getString(R.string.install_bundle_android_detail, choice.minSdk, choice.deviceSdk)
            is BundleChoice.Install, null -> context.getString(R.string.install_bundle_no_spec_detail)
        }
        return ApkInstall.Result.Refused(BundleSelection.refusal(choice), null, detail, local = true)
    }

    /** Opens the app the last install put on the device. Returns whether the request was sent. */
    fun openInstalledApp(): Boolean {
        val packageName = _installOutcome.value?.packageName ?: return false
        _installOutcome.value = null
        return launchApp(packageName)
    }

    fun dismissInstallOutcome() {
        _installOutcome.value = null
    }

    /**
     * Remembers a file shared from another app so it can be sent once the session is up.
     *
     * [path] is a private cache copy taken while the share's read grant was still valid, and
     * [name] is what the sharing app called it, since the copy has none of its own.
     */
    fun queueSharedFile(path: String, name: String?) {
        val previous = pendingShare
        pendingShare = path to name
        if (previous != null && previous.first != path) ShareCache.discard(previous.first)
        // Claimed immediately, while the sharing app's activity is still handing over and this
        // process is allowed to start a foreground service.
        _transferHoldsProcess.value = true
        // A session already running will never call this queue in again: it is drained as a
        // stream starts, so a file handed to a live session sat there until something happened
        // to reconnect, and then went without anyone asking.
        if (session != null) sendQueuedShare()
    }

    /** Sends a queued share, if one is waiting and the session can carry it. */
    private fun sendQueuedShare() {
        // Left in the queue rather than consumed while the single transfer slot is busy, which
        // would drop the file and leak its cached copy. Draining again once the slot frees is
        // what eventually sends it.
        if (_fileTransfer.value.running) return
        val (path, name) = pendingShare ?: return
        pendingShare = null
        sendFile(android.net.Uri.fromFile(java.io.File(path)), name) { ShareCache.discard(path) }
    }

    /**
     * Launches [packageName] on the device. Returns whether the request reached the session.
     *
     * A name the control message cannot carry is refused here: serializing an over-long one
     * throws on the control writer, which would end the session instead of this one action.
     */
    fun launchApp(packageName: String, forceStop: Boolean = false): Boolean =
        AppPackage.isSendable(packageName, forceStop) &&
            send(ControlMessage.startApp(packageName, forceStop))

    /**
     * Opens the device's own [page] about [app]: App info, or its question whether to uninstall.
     *
     * Returns whether the page was asked for. The device's answer arrives later and is reported
     * only when it refused, because an opened page is right there on the mirrored screen. An
     * uninstall marks the app list for reloading: only the device's question decides whether the
     * app is still there.
     */
    fun openAppPage(app: DeviceApp, page: DeviceAppPages.Page): Boolean =
        openAppPage(app, page) { active, command -> active.runAppCommand(command) }

    @androidx.annotation.VisibleForTesting
    internal fun openAppPage(app: DeviceApp, page: DeviceAppPages.Page, run: (ScrcpySession, String) -> String): Boolean {
        if (page !in DeviceAppPages.offered(app)) {
            Log.w(TAG, "Refusing $page for ${app.packageName}, which came with the device")
            return false
        }
        if (input.state.locked) { reportInputLocked(); return false }
        val context = getApplication<Application>()
        val active = session
        val settings = _activeSettings.value
        if (active == null || settings == null || _state.value !is MirrorUiState.Connected) {
            _notice.value = context.getString(R.string.app_pages_need_session)
            return false
        }
        if (!DeviceAppPages.availableFor(settings)) {
            _notice.value = context.getString(R.string.app_pages_unavailable)
            return false
        }
        val failed = context.getString(
            if (page == DeviceAppPages.Page.APP_INFO) R.string.app_info_failed else R.string.app_uninstall_failed,
            app.name,
        )
        val command = DeviceAppPages.command(page, app.packageName, settings.displayId)
        if (command == null) {
            Log.w(TAG, "Refusing $page for a name that is not a package")
            _notice.value = failed
            return false
        }
        val token = generations.token()
        viewModelScope.launch {
            val result = try {
                DeviceAppPages.parse(withContext(Dispatchers.IO) { run(active, command) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page is lost, not the session: a dropped connection ends that on its own.
                Log.w(TAG, "Asking the device for $page failed", e)
                DeviceAppPages.Result.Failed(e.toString())
            }
            if (!generations.accepts(token)) return@launch
            when (result) {
                DeviceAppPages.Result.Opened -> if (page == DeviceAppPages.Page.UNINSTALL) {
                    // Reloaded on the next opening; asked about again after that until it is gone.
                    pendingRemovals += app.packageName
                    _apps.value = _apps.value.copy(loaded = false)
                }
                is DeviceAppPages.Result.Failed -> {
                    Log.w(TAG, "The device did not open $page for ${app.packageName}: ${result.detail}")
                    _notice.value = failed
                }
            }
        }
        return true
    }

    fun pushClipboard(paste: Boolean = true) = pushClipboard(paste) {
        getApplication<Application>().getSystemService(ClipboardManager::class.java).primaryClip
    }

    /**
     * [pushClipboard] with the clip supplied. Read only after the Input Lock check, so a locked
     * session never touches the clipboard. Tests supply their clip here because an emulator
     * mirrors its clipboard to the host and writes the host's text back within milliseconds.
     */
    @androidx.annotation.VisibleForTesting
    internal fun pushClipboard(paste: Boolean, readClip: () -> ClipData?) {
        if (paste && input.state.locked) { reportInputLocked(); return }
        val context = getApplication<Application>()
        val item = readClip()?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        if (item == null || item.uri == null || item.text != null) {
            // Text, an intent or nothing at all is already in memory; only a URI needs a provider.
            sendClipboard(item?.coerceToText(context)?.toString().orEmpty(), paste)
            return
        }
        // A provider can be slow, hung or serve far more than the protocol carries, so its stream
        // is read off the main thread and only up to the limit.
        val token = generations.token()
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) {
                    ClipboardText.read(context, item, ControlMessage.CLIPBOARD_TEXT_MAX_LENGTH + 2)
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Could not read the clipboard", e)
                if (generations.accepts(token)) _notice.value = context.getString(R.string.clipboard_unreadable)
                return@launch
            }
            // The read can outlast the session it was meant for, or a lock engaged meanwhile.
            if (!generations.accepts(token)) return@launch
            if (paste && input.state.locked) { reportInputLocked(); return@launch }
            sendClipboard(text, paste)
        }
    }

    private fun sendClipboard(text: String, paste: Boolean) {
        val bounded = boundedClipboard(text) ?: return
        send(ControlMessage.SetClipboard(clipboardSequence.getAndIncrement(), bounded, paste))
    }
    private fun boundedClipboard(text: String): String? {
        val context = getApplication<Application>()
        return try {
            ControlMessage.truncateClipboard(text).also {
                if (it != text) _notice.value = context.getString(R.string.clipboard_truncated)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected malformed Unicode input", e)
            _notice.value = context.getString(R.string.input_invalid_unicode)
            null
        }
    }
    /**
     * Asks the device for its clipboard, optionally copying or cutting the selection first.
     *
     * Reading alone only ever returned whatever had last been copied *on the device*, so the
     * obvious thing — select some text over there, press Copy here — did nothing at all. Asking
     * the device to perform the copy first is what makes that work; with nothing selected the
     * keystroke lands on nobody and the clipboard comes back unchanged, so it is no worse than
     * a plain read.
     */
    fun pullClipboard(copyKey: Int = ControlMessage.COPY_KEY_COPY) {
        send(ControlMessage.GetClipboard(copyKey))
    }

    /** Cuts the selection on the device and brings it here, leaving the field empty over there. */
    fun cutFromDevice() = pullClipboard(ControlMessage.COPY_KEY_CUT)

    fun clearNotice() { _notice.value = null }

    /** Shows a transient message, used by the screen for results it produces itself. */
    fun showNotice(message: String) { _notice.value = message }

    /**
     * Uses [name] as the video encoder for this session, reconnecting to apply it.
     *
     * For a device with a saved profile the choice belongs there, and the caller writes it. This
     * is the path for everything else — a link, or a one-off address — where there is no profile
     * to write to and the encoder list would otherwise be shown greyed out with no way to act.
     *
     * A reconnect is unavoidable: an encoder is chosen when the stream is created, so there is no
     * way to change one on a stream already running.
     */
    fun useVideoEncoderForSession(name: String?) {
        if (request == null) return
        overrides = overrides.copy(videoEncoder = SessionOverrides.Choice(name))
        restartForOverrides()
    }

    /** The same for audio. */
    fun useAudioEncoderForSession(name: String?) {
        if (request == null) return
        overrides = overrides.copy(audioEncoder = SessionOverrides.Choice(name))
        restartForOverrides()
    }

    /**
     * Uses [camera] for this session only, or the screen again when it is null.
     *
     * Reconnects, because the capture source is chosen when the stream is created. The choice is
     * not written anywhere: links and temporary display choices must not change a saved profile.
     */
    fun useCameraForSession(camera: CameraCapture?) {
        if (request == null) return
        overrides = overrides.copy(camera = SessionOverrides.Choice(camera))
        restartForOverrides()
    }

    fun updateDesktopViewport(width: Int, height: Int, densityDpi: Int) {
        // An unmeasured or detached Compose window has no useful target size.
        if (width <= 0 || height <= 0) return
        val viewport = DesktopViewport(width, height, densityDpi)
        if (desktopViewport == viewport) return
        desktopViewport = viewport
        val settings = _activeSettings.value ?: return
        desktopResize.updateViewport(DesktopSize.fit(width, height, settings.maxSize))
        _desktopResizing.value = !desktopResize.canRecord
        scheduleDesktopResize()
    }

    fun useDisplayForSession(id: Int) {
        if (!canChangeDisplay()) return
        if (id !in 0..4096) {
            Log.w(TAG, "Refusing out-of-range display id $id")
            _notice.value = getApplication<Application>().getString(R.string.desktop_invalid_display)
            return
        }
        if (!canChooseSessionDisplay(id, _activeSettings.value?.virtualDisplay != null)) {
            Log.w(TAG, "Return to the main screen before selecting secondary display $id")
            _notice.value = getApplication<Application>().getString(R.string.desktop_return_primary)
            return
        }
        overrides = overrides.copy(display = SessionDisplay.Physical(id), camera = SessionOverrides.Choice(null))
        restartForOverrides()
    }

    fun useAdaptiveDesktopForSession() {
        if (!canChangeDisplay()) return
        val viewport = desktopViewport
        val settings = _activeSettings.value
        if (viewport == null || settings == null) {
            _notice.value = getApplication<Application>().getString(R.string.desktop_window_unavailable)
            return
        }
        overrides = overrides.copy(
            display = viewport.display(settings.maxSize),
            camera = SessionOverrides.Choice(null),
        )
        restartForOverrides()
    }

    private fun canChangeDisplay(): Boolean {
        val message = when {
            session == null || _state.value !is MirrorUiState.Connected -> R.string.desktop_needs_connection
            _recording.value -> R.string.desktop_stop_recording
            else -> return true
        }
        _notice.value = getApplication<Application>().getString(message)
        return false
    }

    private fun scheduleDesktopResize() {
        desktopResizeJob?.cancel()
        desktopResizeJob = null
        if (!desktopResize.active || !visible || _recording.value || desktopResize.pending != null) return
        val token = generations.token()
        desktopResizeJob = viewModelScope.launch {
            delay(DESKTOP_RESIZE_DEBOUNCE_MILLIS)
            if (!generations.accepts(token)) return@launch
            val size = desktopResize.nextRequest(_recording.value, visible)
            _desktopResizing.value = !desktopResize.canRecord
            if (size == null) return@launch
            if (!send(ControlMessage.ResizeDisplay(size.width, size.height))) {
                if (generations.accepts(token)) {
                    _notice.value = getApplication<Application>().getString(R.string.desktop_resize_failed)
                }
                return@launch
            }
            desktopResizeTimeout?.cancel()
            desktopResizeTimeout = viewModelScope.launch {
                delay(DESKTOP_RESIZE_TIMEOUT_MILLIS)
                if (generations.accepts(token) && desktopResize.pending == size) {
                    Log.w(TAG, "No capture acknowledgement for desktop resize ${size.width}x${size.height}")
                    _notice.value = getApplication<Application>().getString(R.string.desktop_resize_failed)
                }
            }
        }
    }

    private fun restartForOverrides(staleAgainst: MirrorSettings? = null) {
        cancelRecovery()
        recovery = ConnectionRecoveryPolicy.State()
        beginConnection(staleAgainst = staleAgainst)
    }

    /**
     * Starts writing the session's video to a file, and returns whether it began.
     *
     * The frames are already encoded when they arrive, so this copies them into a container
     * rather than encoding anything: the recording is what the device actually sent, and costs
     * no extra decoding. A key frame is asked for straight away so the file opens promptly
     * instead of waiting for whenever the device would next have sent one.
     */
    fun startRecording(withSound: Boolean = false): Boolean {
        if (recorder != null) return false
        if (_recordingSaveInProgress.value) {
            _notice.value = getApplication<Application>().getString(R.string.recording_export_in_progress)
            return false
        }
        if (!desktopResize.canRecord) {
            _notice.value = getApplication<Application>().getString(R.string.desktop_recording_wait)
            return false
        }
        val active = session ?: run {
            _notice.value = getApplication<Application>().getString(R.string.recording_needs_session)
            return false
        }
        // Asked for with sound, a recording needs the sound stream to take it from; without one
        // it would quietly be silent, which is the one thing it must not be.
        val sound = if (withSound) audio ?: return false else null
        val directory = java.io.File(getApplication<Application>().cacheDir, RECORDING_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            _notice.value = getApplication<Application>().getString(R.string.recording_failed)
            return false
        }
        // A recording left behind by a process that died mid-session, or before the user chose
        // whether to keep it, has nobody to clean it up: the only other path runs when this view
        // model is cleared, which is exactly what did not happen. Swept here, where a new
        // recording is about to make more space matter anyway.
        sweepStaleRecordings(directory)
        val started = SessionRecorder(
            java.io.File(directory, "session-${System.currentTimeMillis()}.mp4"), withSound = withSound)
        started.onStopped = {
            // A recording that ends itself is finished the same way the user ending it would
            // finish it, so the panel stops claiming to be recording and the reason is shown at
            // the moment it happened rather than whenever Stop is eventually pressed.
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (recorder === started) stopRecording()
            }
        }
        started.onKeyFrameNeeded = {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (recorder === started) requestRecordingKeyFrame()
            }
        }
        recorder = started
        video?.setRecorder(started)
        sound?.setRecorder(started)
        _recording.value = true
        _recordingStatus.value = RecordingStatus(SystemClock.elapsedRealtime(), withSound)
        // Forces the device to send a key frame, which is what a recording has to open on.
        keyFrameRequest?.cancel()
        keyFramePacer.reset()
        requestRecordingKeyFrame()
        return true
    }

    /** What pressing Record would do about this session's sound. */
    internal fun recordingSound(): RecordingSound {
        val settings = _activeSettings.value
        return RecordingSound.decide(
            soundRequested = settings?.audioEnabled == true,
            soundRunning = audio != null,
            codec = settings?.audioCodec ?: AudioCodec.OPUS,
        )
    }

    /**
     * Reconnects this session with AAC sound and starts recording with sound once it is playing.
     *
     * The user asked for this knowing the session would reconnect; the switch is for this session
     * only and leaves the profile, and any link it came from, as they were.
     */
    fun recordWithSoundAfterReconnect() {
        val connection = request ?: return
        if (recorder != null) return
        val profile = try { connection.snapshot() } catch (_: IllegalArgumentException) { null }
        // Settings that cannot be read fail the reconnect itself, with the reason.
        overrides = profile?.let(overrides::withAacForRecording)
            ?: overrides.copy(audioCodec = AudioCodec.AAC, audioCodecFrom = null)
        recordWhenSoundPlays = true
        restartForOverrides()
    }

    /** Starts the recording asked for before a reconnect, now that the new session has sound. */
    private fun startDeferredRecording() {
        if (!recordWhenSoundPlays) return
        recordWhenSoundPlays = false
        // Only AAC goes into the file: a recording with sound in any other codec would wait for
        // sound it can never take, and end empty.
        if (_activeSettings.value?.audioCodec != AudioCodec.AAC) {
            _notice.value = getApplication<Application>().getString(R.string.recording_sound_not_started)
            return
        }
        if (startRecording(withSound = true)) {
            _notice.value = getApplication<Application>().getString(R.string.recording_started_with_sound)
        }
    }

    /** The reconnect for a recording with sound did not end with sound playing. */
    private fun abandonDeferredRecording() {
        if (!recordWhenSoundPlays) return
        recordWhenSoundPlays = false
        _notice.value = getApplication<Application>().getString(R.string.recording_sound_not_started)
    }

    /**
     * Asks the device for a key frame on behalf of the recording, at most a few times a second.
     *
     * A recording with sound can turn down a key frame its sound does not cover, and asks again;
     * each request makes the device encode a full frame, so they are spaced out. One asked for
     * too soon is sent when the spacing allows rather than dropped: dropped, it left the
     * recording waiting for the device's own next key frame, ten seconds away.
     */
    private fun requestRecordingKeyFrame() {
        val wait = keyFramePacer.request(SystemClock.elapsedRealtime()) ?: return
        if (wait == 0L) {
            send(ControlMessage.resetVideo())
            return
        }
        val owner = recorder
        keyFrameRequest = viewModelScope.launch {
            delay(wait)
            keyFramePacer.fired(SystemClock.elapsedRealtime())
            if (owner != null && recorder === owner) send(ControlMessage.resetVideo())
        }
    }

    /**
     * Stops recording, offering whatever was written through [finishedRecording].
     *
     * A reason is shown when the recording ended on its own — a change of capture size, or a
     * codec this device cannot put in an MP4 — so a short or missing file is explained rather
     * than just appearing.
     */
    fun stopRecording() {
        val current = recorder ?: return
        recorder = null
        video?.setRecorder(null)
        audio?.setRecorder(null)
        keyFrameRequest?.cancel()
        keyFramePacer.reset()
        _recording.value = false
        _recordingStatus.value = null
        scheduleDesktopResize()
        // Closing the muxer writes the MP4 index and can block on disk, while the codec worker
        // is held up by the recorder's lock: so it happens off the main thread, after the
        // recorder was detached above, and the result is published back on Main.
        val finishing = cleanupScope.launch {
            val file = current.finish()
            val offered = withContext(NonCancellable + Dispatchers.Main.immediate) {
                publishFinishedRecording(current, file)
            }
            if (!offered && file != null) preserveRecording(file)
        }
        recordingFinishJob = finishing
    }

    private var recordingFinishJob: Job? = null

    /**
     * Reports how [current] ended and offers [file]. Returns false when nobody is left to offer
     * it to (the view model was cleared), so the caller can keep it somewhere recoverable.
     */
    private fun publishFinishedRecording(current: SessionRecorder, file: java.io.File?): Boolean {
        if (cleared) return false
        val context = getApplication<Application>()
        when (current.error) {
            SessionRecorder.STORAGE_FULL -> _notice.value = context.getString(R.string.recording_storage_full)
            SessionRecorder.SIZE_CHANGED -> _notice.value = context.getString(R.string.recording_stopped_resized)
            SessionRecorder.STREAM_CHANGED ->
                _notice.value = context.getString(R.string.recording_stopped_stream_changed)
            SessionRecorder.UNSUPPORTED_CODEC ->
                _notice.value = context.getString(R.string.recording_unsupported_codec,
                    _activeSettings.value?.videoCodec?.label ?: "")
            SessionRecorder.WRITE_FAILED -> _notice.value = context.getString(R.string.recording_failed)
            SessionRecorder.AUDIO_STOPPED, SessionRecorder.AUDIO_TIMELINE, SessionRecorder.AUDIO_UNSUPPORTED ->
                RecordingSound.stopNotice(current.error, kept = file != null)?.let { _notice.value = context.getString(it) }
            else -> if (file == null) _notice.value = context.getString(R.string.recording_empty)
        }
        // A recording that could not be closed properly has no index and no player will open it,
        // so there is nothing to offer — and saying so matters more here, because the frames did
        // reach the disk and the file it would have produced looks convincing until it is opened.
        if (file == null && (current.error == SessionRecorder.WRITE_FAILED ||
                current.error == SessionRecorder.STORAGE_FULL)) {
            _notice.value = context.getString(R.string.recording_lost)
        }
        if (file == null) return true
        offerFinishedRecording(file)
        return true
    }

    /**
     * Moves a finished recording nobody can be offered into the app's own Movies folder.
     *
     * When the view model is cleared with a recording in progress, the offer to save it has no
     * screen left to appear on, and deleting it silently loses minutes of video the user asked
     * for. Saved recordings otherwise go through a document picker (no fixed folder exists), so
     * the least invasive persistent place is the app-specific external Movies directory: no
     * permission, no notification, reachable from a file manager, removed with the app.
     */
    private fun preserveRecording(file: java.io.File) {
        try {
            val directory = getApplication<Application>()
                .getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            if (directory == null || (!directory.isDirectory && !directory.mkdirs())) {
                Log.w(TAG, "No folder to keep the unsaved recording in")
                return
            }
            val target = java.io.File(directory, "recovered-${file.name}")
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep the unsaved recording", e)
        }
    }

    /** Offers [file] as the finished recording, replacing any earlier offer. */
    internal fun offerFinishedRecording(file: java.io.File) {
        // Replace the pending offer, retaining any URI already handed to a share receiver.
        if (_recordingSaveInProgress.value) {
            // The current offer is being copied; replacing it now would delete that source.
            val queued = pendingFinishedRecording
            pendingFinishedRecording = file
            if (queued != null && queued != file) releaseFinishedRecording(queued)
            return
        }
        val previous = _finishedRecording.value
        _finishedRecording.value = file
        // Named in the saved state as well: the document picker that Save opens can outlive this
        // process, and without the name the destination it returns had nothing to be copied into
        // it, while the recording itself sat unoffered in the cache.
        savedState[STATE_FINISHED_RECORDING] = file.name
        if (previous != null && previous != file) releaseFinishedRecording(previous)
        saveSharedFinishedRecording()
    }

    /**
     * The recording a previous process was offering, if it is still there.
     *
     * Only a plain file named like a recording, directly in the recordings cache: the name comes
     * back from saved state, and nothing outside that folder may be offered, shared or deleted.
     */
    private fun restoreFinishedRecording(): java.io.File? {
        val name = savedState.get<String>(STATE_FINISHED_RECORDING) ?: return null
        val directory = java.io.File(getApplication<Application>().cacheDir, RECORDING_DIRECTORY)
        val file = java.io.File(directory, name)
        if (RECORDING_NAME.matches(name) && file.isFile) return file
        savedState.remove<String>(STATE_FINISHED_RECORDING)
        savedState.remove<Boolean>(STATE_FINISHED_RECORDING_SHARED)
        return null
    }

    /** Whether the current offer was handed to another app, kept with its name in saved state. */
    private fun saveSharedFinishedRecording() {
        val current = _finishedRecording.value
        if (current != null && sharedFinishedRecording == current) {
            savedState[STATE_FINISHED_RECORDING_SHARED] = true
        } else {
            savedState.remove<Boolean>(STATE_FINISHED_RECORDING_SHARED)
        }
    }

    internal fun shareFinishedRecording(launch: (java.io.File) -> Boolean): Boolean {
        if (_recordingSaveInProgress.value) return false
        val file = _finishedRecording.value ?: return false
        try {
            if (!file.setLastModified(System.currentTimeMillis())) {
                Log.w(TAG, "Could not refresh the shared recording's cache lifetime")
                return false
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not refresh the shared recording's cache lifetime", error)
            return false
        }
        if (!launch(file)) return false
        sharedFinishedRecording = file
        saveSharedFinishedRecording()
        return true
    }

    /** Called once the finished recording has been saved or refused, so its copy can go. */
    fun consumeFinishedRecording(keep: Boolean) {
        if (_recordingSaveInProgress.value) return
        val file = _finishedRecording.value ?: return
        _finishedRecording.value = null
        savedState.remove<String>(STATE_FINISHED_RECORDING)
        releaseFinishedRecording(file, keep)
        saveSharedFinishedRecording()
    }

    private fun releaseFinishedRecording(file: java.io.File, keep: Boolean = false) {
        val shared = sharedFinishedRecording == file
        if (shared) sharedFinishedRecording = null
        // A receiver may open its granted URI after this offer or its owner is gone.
        // Shared files remain for the existing cache-expiry sweep, like shared screenshots.
        if (!keep && !shared) {
            try {
                if (!file.delete() && file.exists()) Log.w(TAG, "Could not remove a finished recording")
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not remove a finished recording", error)
            }
        }
    }

    /**
     * Copies the finished recording to [destination] and then drops the cached copy.
     *
     * Owned here rather than by the screen because the screen can go away mid-copy — leaving the
     * chosen document truncated, no message shown, and the cached original deleted by teardown,
     * which loses a recording the user had asked to keep. The byte copy itself is uncancellable
     * for the same reason: it runs on an IO thread, so the cost of finishing it is a moment of
     * process lifetime rather than a frozen screen.
     */
    fun saveFinishedRecording(destination: android.net.Uri?) {
        // Cancelling the destination picker leaves Save, Share and Discard available.
        if (_recordingSaveInProgress.value || destination == null) return
        val file = _finishedRecording.value ?: return
        // A restored document result or a duplicate callback must not start a second copy.
        _recordingSaveInProgress.value = true
        val context = getApplication<Application>()
        viewModelScope.launch {
            // Own the source through both the copy and its cleanup, even if the ViewModel clears
            // while a document provider is still opening the destination.
            withContext(NonCancellable) {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: throw java.io.IOException("No output stream for $destination")
                        null
                    } catch (e: Exception) {
                        Log.w(TAG, "Saving recording failed", e)
                        e
                    }
                }
                _recordingSaveInProgress.value = false
                if (!cleared) {
                    _notice.value = context.getString(
                        if (failure == null) R.string.recording_saved else R.string.recording_save_failed)
                }
                // Only the file that was copied: a newer offer must not be discarded unsaved.
                if ((failure == null || cleared) && _finishedRecording.value == file) {
                    consumeFinishedRecording(keep = false)
                }
                pendingFinishedRecording?.let {
                    pendingFinishedRecording = null
                    offerFinishedRecording(it)
                }
            }
        }
    }

    /** Ends a recording without keeping it, used when the session itself goes away. */
    private fun discardRecording() {
        val current = recorder ?: return
        recorder = null
        video?.setRecorder(null)
        audio?.setRecorder(null)
        keyFrameRequest?.cancel()
        keyFramePacer.reset()
        _recording.value = false
        _recordingStatus.value = null
        // discard() takes the recorder lock and stops the muxer: never on the main thread.
        cleanupScope.launch { current.discard() }
    }

    /**
     * Removes recordings old enough that nothing can still be waiting on them.
     *
     * Only ones older than the cutoff: a recording being written right now, or one still offered
     * to the user, is far younger than that, so this cannot take a file out from under either.
     */
    private fun sweepStaleRecordings(directory: java.io.File) {
        val cutoff = System.currentTimeMillis() - STALE_RECORDING_MILLIS
        val keep = _finishedRecording.value
        directory.listFiles()?.forEach { file ->
            if (file != keep && file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }

    /** Surfaces a refused foreground service instead of silently dropping the session later. */
    fun reportBackgroundSessionUnavailable() {
        _backgroundAudio.value = false
        _notice.value = getApplication<Application>().getString(R.string.background_session_unavailable)
    }

    fun toggleAudioMute(): Boolean {
        userMuted = !_audioState.value.muted
        if (userMuted) {
            audioFocus.abandon()
            focusGranted = false
        } else {
            audioFocus.abandon()
            acquireAudioFocus()
            audio?.resetTimeline()
        }
        applyAudioMute()
        return userMuted
    }

    /**
     * Applies a background-audio change to the session already running.
     *
     * Whether audio survives backgrounding is read from the settings this session connected with,
     * and background audio is deliberately not one of the settings that asks for the stream to be
     * restarted. Without telling the session directly, changing it therefore did nothing at all
     * until the next reconnect.
     */
    fun onBackgroundAudioChanged(enabled: Boolean) {
        val active = _activeSettings.value ?: return
        if (active.backgroundAudio == enabled) return
        _activeSettings.value = active.copy(backgroundAudio = enabled)
        if (enabled && !focusGranted) acquireAudioFocus()
        applyAudioMute()
    }

    private fun acquireAudioFocus() {        if (!(visible || backgroundSessionEnabled) || userMuted || !_audioState.value.playing) return
        focusGranted = audioFocus.acquire()
        if (!focusGranted) {
            _notice.value = getApplication<Application>().getString(R.string.audio_focus_denied)
        }
    }

    private fun applyAudioMute() {
        val muted = userMuted || !(visible || backgroundSessionEnabled) || !focusGranted
        audio?.setMuted(muted)
        _audioState.value = _audioState.value.copy(muted = muted)
        // Recomputed here so every mute, focus, playback and visibility path keeps the service
        // declaration in step with what the session is actually doing.
        _backgroundAudio.value = backgroundSessionEnabled
    }

    fun retryAudio() {
        cancelRecovery()
        recovery = ConnectionRecoveryPolicy.State()
        beginConnection(enableAudio = true)
    }

    /**
     * Reconnects this session with its sound moved here, after the device refused to send sound it
     * keeps playing (Android 13). For this session only, like the other session choices: the saved
     * device keeps its setting, which Android 14 and newer honour.
     */
    /**
     * Reconnects this session with its sound from [source], for this session only, from the
     * mirror's controls. The saved device keeps its own source.
     */
    fun useAudioSourceForSession(source: com.scrcpy.remote.scrcpy.AudioSource) {
        if (request == null) return
        if (_activeSettings.value?.audioSource == source && _activeSettings.value?.audioEnabled == true) return
        overrides = overrides.copy(audioSource = source)
        restartForOverrides()
    }

    fun hearSoundHere() {
        if (request == null) return
        overrides = overrides.copy(audioDup = false)
        restartForOverrides()
    }

    /**
     * The choice that was working before the last Picture & sound switch, offered back while that
     * switch has not yet produced a picture. Null once it has, or when there is nothing to go back to.
     */
    private val _revertibleChoice = kotlinx.coroutines.flow.MutableStateFlow<StreamChoice?>(null)
    val revertibleChoice = _revertibleChoice.asStateFlow()

    /** The session choices in effect when that working choice ran, and the settings they ran as. */
    private var revertTo: SessionOverrides? = null
    private var revertSettings: MirrorSettings? = null

    /**
     * Reconnects this session with [choice]'s codecs, for this session only.
     *
     * Both codecs apply in one restart, and choosing what already runs restarts nothing. When the
     * session was working, what it ran is kept and offered back should the new choice fail, rather
     * than the session retrying a codec the device cannot send or silently changing it.
     */
    fun applyStreamChoice(choice: StreamChoice) {
        val connection = request ?: return
        val active = _activeSettings.value ?: return
        val current = StreamChoice.of(active)
        if (choice == current) return
        val profile = try { connection.snapshot() } catch (_: IllegalArgumentException) { return }
        // Kept from before an earlier switch that has not shown a picture yet: that one is unproven.
        if (revertTo == null && _state.value is MirrorUiState.Connected) {
            revertTo = overrides
            revertSettings = active
            _revertibleChoice.value = current
        }
        overrides = StreamChoices.overridesFor(overrides, choice, profile)
        restartForOverrides()
    }

    /** Returns to the choice that worked before the last switch, offered while that switch fails. */
    fun revertStreamChoice() {
        val previous = revertTo ?: return
        val ranAs = revertSettings
        if (request == null) return
        clearRevertibleChoice()
        overrides = previous
        // Compared with the settings those choices ran as, not the failed attempt's: an encoder chosen
        // for the working codec belongs with it, and would otherwise be dropped as stale.
        restartForOverrides(staleAgainst = ranAs)
    }

    private fun clearRevertibleChoice() {
        revertTo = null
        revertSettings = null
        _revertibleChoice.value = null
    }

    /** The codec ids the running session's streams announced, for checking what a device really sends. */
    @androidx.annotation.VisibleForTesting
    internal fun streamCodecIds(): Pair<Int?, Int?>? = session?.let { it.videoReader?.codecId to it.audioReader?.codecId }

    /** The decoder's own frame counts, read directly rather than through the sampled diagnostics. */
    @androidx.annotation.VisibleForTesting
    internal fun liveFrameStats(): VideoFrameStats? = video?.frameStats()

    fun reconnect() {
        val connection = request ?: return
        cancelRecovery()
        recovery = ConnectionRecoveryPolicy.State()
        // Wireless debugging picks a new port every time it is switched off and on, so retrying
        // the remembered one can never succeed. Automatic recovery only rediscovers after a
        // session has connected once; a saved device whose port moved before it ever connected
        // needs that more, not less, and Retry was the one path that skipped it.
        if (!discoveryAllowed || connection.port == 5555) {
            beginConnection()
            return
        }
        val application = getApplication<Application>()
        stateBeforeRetry = _state.value
        _state.value = MirrorUiState.Connecting(
            application.getString(R.string.status_connecting, "${connection.host}:${retryPort ?: connection.port}"))
        recoveryJob = viewModelScope.launch {
            val result = NsdConnectionPortDiscovery(application).resolveConnectionPort(
                connection.host, retryPort ?: connection.port, enabled = true)
            if (request !== connection) {
                stateBeforeRetry = null
                return@launch
            }
            when (result) {
                is ConnectionPortResult.Updated -> {
                    retryPort = result.port
                    _notice.value = application.getString(R.string.recovery_new_port, result.port)
                }
                // Reported the same way automatic recovery reports it. Saying nothing here meant
                // Retry on a device whose port had moved looked like it had not tried anything,
                // when in fact discovery had run and found nothing it could trust.
                is ConnectionPortResult.Ambiguous, is ConnectionPortResult.Unavailable,
                ConnectionPortResult.Incomplete, ConnectionPortResult.InvalidEndpoint -> {
                    Log.w(TAG, "Retry port discovery selected no replacement: $result")
                    _notice.value = noReplacementNotice()
                }
                else -> Unit
            }
            stateBeforeRetry = null
            beginConnection()
        }
    }

    fun disconnect() {
        resetLiveTyping()
        cancelRecovery()
        request = null
        establishedSession = false
        checkupTracker = null
        failedCheckup = null
        _checkup.value = null
        failedHistory = null
        _failureHistory.value = null
        _failureReport.value = null
        deviceClipAtMillis = null
        recordWhenSoundPlays = false
        // Session-only choices go with the session that made them.
        overrides = SessionOverrides.EMPTY
        clearRevertibleChoice()
        if (monitoringNetwork) {
            connectivity.unregisterNetworkCallback(networkCallback)
            monitoringNetwork = false
        }
        generations.next()
        connectJob?.cancel()
        // Leaving deliberately abandons a share that never got sent. Keeping it would upload a
        // file the user walked away from as soon as anything connected next.
        abandonPendingShare()
        // An app still waiting for an answer goes the same way, with the copy taken of it.
        endAppOffer()
        _installOutcome.value = null
        detachSession()
        _state.value = MirrorUiState.Disconnected
    }

    private fun fail(error: Throwable) {
        recoveryJob?.cancel()
        recoveryJob = null
        // The failure is what the screen explains; a recording waiting for this session's sound
        // will not start on a later one the user did not ask it for.
        recordWhenSoundPlays = false
        // A retry's remembered screen belongs to that retry. Leaving it behind let a failure
        // during the retry window be overwritten later by a stale "Connected".
        stateBeforeRetry = null
        recoveryToken = generations.next()
        connectJob?.cancel()
        automaticAttempt = false
        Log.e(TAG, "Session failed", error)
        val serverLog = session?.serverOutput().orEmpty()
        val interrupted = detachSession()
        val kind = ConnectionRecoveryPolicy.classify(error)
        failureGuided = ConnectionFailureGuidance.guidanceFor(kind) != null
        failedCheckup = checkupTracker?.fail(kind)
        checkupTracker = null
        failedHistory = recordProblem(failedCheckup, kind)
        _failureHistory.value = failedHistory
        // Worded from how far the attempt got: a connection lost after the device answered was
        // reported as an address that could not be reached.
        val presentation = describeFailure(error, failedCheckup?.failedStage)
        failureMessage = presentation.message
        failureDetails = presentation.details
        _checkup.value = failedCheckup
        _failureReport.value = request?.let {
            val diagnosis = ConnectionDiagnoser.diagnose(error, failedCheckup?.failedStage)
            FailureReport(
                diagnosis, it.host, it.port,
                DiagnosticReport.build(it.host, it.port, error, diagnosis.category, failedCheckup?.failedStage, serverLog,
                    appVersion = runCatching {
                        getApplication<Application>().packageManager
                            .getPackageInfo(getApplication<Application>().packageName, 0).versionName
                    }.getOrNull()),
            )
        }
        recoveryFailure = error
        _state.value = MirrorUiState.Failed(presentation.message, presentation.guided, presentation.details)
        // A share that never reached a session must not keep the process held up, and the user
        // needs to know their file did not go anywhere. A transfer cut off by the same failure is
        // still reported with it: that is the file the user believes has already arrived.
        if (abandonPendingShare()) {
            val unsent = getApplication<Application>().getString(R.string.share_not_sent)
            _notice.value = if (interrupted == null) unsent else "$interrupted\n$unsent"
        }
        scheduleRecovery()
    }

    /**
     * Drops a share that will never be sent, deleting the cache copy taken for it.
     *
     * Returns whether there was one. The copy belongs to this app once it has been taken, so
     * letting go of the reference without deleting the file would strand it in the cache.
     */
    private fun abandonPendingShare(): Boolean {
        val waiting = pendingShare ?: return false
        ShareCache.discard(waiting.first)
        pendingShare = null
        _transferHoldsProcess.value = false
        return true
    }

    /**
     * Explains a failure using the same classification the retry policy uses, so the message and
     * the retry behaviour can never disagree. The technical text is kept as a secondary line: it
     * is what makes a bug report useful, but it is no longer the whole message.
     */
    private fun describeFailure(error: Throwable, stage: CheckupStage?): ConnectionFailureGuidance.FailureCopy {
        val context = getApplication<Application>()
        val kind = ConnectionRecoveryPolicy.classify(error)
        val detail = ConnectionFailureGuidance.technicalDetail(error)
        return ConnectionFailureGuidance.buildFailureCopy(
            kind = kind,
            stage = stage,
            sessionChoices = sessionChoicesInEffect(),
            rawDetail = detail,
            resolveGuided = { id, withEndpoint ->
                if (withEndpoint) context.getString(id, endpointLabel()) else context.getString(id)
            },
            fallbackMessage = context.getString(R.string.error_unexpected),
        )
    }

    private fun noReplacementNotice(): String {
        val notice = PortDiscoveryNotice.noReplacement(retryPort)
        val resources = getApplication<Application>()
        return notice.port?.let { resources.getString(notice.text, it) } ?: resources.getString(notice.text)
    }

    /**
     * Whether this attempt carries choices made for this session only: options from the link
     * that opened it, or a display, encoder, camera or sound choice made in its controls.
     */
    private fun sessionChoicesInEffect(): Boolean = overrides != SessionOverrides.EMPTY || request?.linkOptions == true

    /** The endpoint actually being attempted, including a discovered replacement port. */
    internal fun endpointLabel(): String {
        val connection = request ?: return ""
        val port = retryPort ?: connection.port
        val host = connection.host
        return if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
    }

    /** Cuts short a scheduled reconnect's wait, keeping its attempt count. */
    private fun retryRecoveryNow() {
        val waiting = recoveryJob ?: return
        if (!waiting.isActive || !recoveryWaiting) return
        recoverySkipWait.trySend(Unit)
    }
    private var recoveryWaiting = false
    private val recoverySkipWait = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
        stateBeforeRetry = null
        recoveryFailure = null
        automaticAttempt = false
    }

    private fun scheduleRecovery() {
        val error = recoveryFailure ?: return
        val connection = request ?: return
        if (!visible || !establishedSession || recoveryJob?.isActive == true ||
            _activeSettings.value?.autoReconnect != true) return
        val decision = ConnectionRecoveryPolicy.onFailure(recovery, error, enabled = true)
        if (decision !is ConnectionRecoveryPolicy.Decision.Retry) {
            recovery = decision.state
            recoveryFailure = null
            if (decision is ConnectionRecoveryPolicy.Decision.Stop &&
                decision.reason == ConnectionRecoveryPolicy.StopReason.EXHAUSTED) {
                _state.value = MirrorUiState.Failed("$failureMessage\n" +
                    getApplication<Application>().getString(R.string.recovery_exhausted), failureGuided, failureDetails)
            }
            return
        }
        val token = recoveryToken
        val seconds = (decision.delayMillis / 1_000).toInt()
        _state.value = MirrorUiState.Connecting(getApplication<Application>().resources.getQuantityString(
            R.plurals.recovery_waiting, seconds, decision.attempt, ConnectionRecoveryPolicy.MAX_RETRIES, seconds))
        recoveryJob = viewModelScope.launch {
            while (recoverySkipWait.tryReceive().isSuccess) Unit
            recoveryWaiting = true
            kotlinx.coroutines.withTimeoutOrNull(decision.delayMillis) { recoverySkipWait.receive() }
            recoveryWaiting = false
            if (!visible || request !== connection || !generations.accepts(token)) return@launch
            if (discoveryAllowed && connection.port != 5555) {
                val result = NsdConnectionPortDiscovery(getApplication()).resolveConnectionPort(
                    connection.host, retryPort ?: connection.port, enabled = true)
                if (!visible || request !== connection || !generations.accepts(token)) return@launch
                when (result) {
                    is ConnectionPortResult.Updated -> {
                        retryPort = result.port
                        _notice.value = getApplication<Application>().getString(R.string.recovery_new_port, result.port)
                    }
                    is ConnectionPortResult.Ambiguous, is ConnectionPortResult.Unavailable,
                    ConnectionPortResult.Incomplete, ConnectionPortResult.InvalidEndpoint -> {
                        Log.w(TAG, "Port discovery selected no replacement: $result")
                        _notice.value = noReplacementNotice()
                    }
                    else -> Unit
                }
            }
            recovery = decision.state
            recoveryJob = null
            beginConnection(automatic = true)
        }
    }

    /** Tears the session down; returns the notice for a transfer it cut off, if there was one. */
    private fun detachSession(): String? {
        resetLiveTyping()
        desktopResizeJob?.cancel()
        desktopResizeJob = null
        desktopResizeTimeout?.cancel()
        desktopResizeTimeout = null
        desktopResize.stop()
        _desktopResizing.value = false
        // Kept rather than thrown away: whatever was captured before the session went is still
        // what the user asked to record, and it is offered the same way a manual stop is.
        stopRecording()
        timingJob?.cancel()
        timingJob = null
        _qualityHistory.value = recentQuality.reset(SystemClock.elapsedRealtime())
        _mediaTiming.value = MediaTimingDiagnostics()
        _videoFrames.value = VideoFrameStats()
        _videoLatency.value = LatencyStats()
        _backgroundAudio.value = false
        mediaClock?.reset()
        mediaClock = null
        audioFocus.abandon()
        focusGranted = false
        // The player goes with the session. Leaving "playing" set let a later return to the app
        // ask for audio focus again on behalf of a session that no longer exists, ducking or
        // interrupting whatever the user had started listening to in the meantime.
        _audioState.value = _audioState.value.copy(playing = false)
        val current = _state.value as? MirrorUiState.Connected
        if (current != null) {
            // Both halves of the input state, for the same reason: a key or a finger the device
            // still believes is down stays down, and its release is delivered to whatever has
            // focus next rather than to this session.
            releaseHeldKeys()
            releasePointers(current.videoWidth, current.videoHeight)
        }
        val oldSession = session
        val oldVideo = video
        val oldAudio = audio
        val oldMessages = deviceMessages
        session = null; video = null; audio = null; deviceMessages = null
        // The app list belongs to the session that produced it. Keeping it would let a reconnected
        // session show apps that were enumerated before, and suppress the reload that would fix it.
        _apps.value = DeviceAppsState()
        pendingRemovals.clear()
        removalCheck?.cancel()
        removalCheck = null
        _encoders.value = DeviceEncodersState()
        _audioEncoders.value = DeviceEncodersState()
        _displays.value = DeviceDisplaysState()
        _cameras.value = DeviceCamerasState()
        // The light belongs to the session that turned it on; a new one starts dark as far as
        // this client knows, whatever the device is actually doing.
        _cameraTorch.value = false
        _deviceScreenOff.value = false
        stableJob?.cancel()
        stableJob = null
        // A transfer writes through this session's ADB connection, so it cannot outlive it.
        val abandoned = _fileTransfer.value.takeIf { it.running }
        fileTransferJob?.cancel()
        fileTransferJob = null
        cancelTransferResources()
        // Retires the slot so a worker still unwinding cannot clear the next session's transfer.
        fileTransferSerial++
        _fileTransfer.value = FileTransferState()
        _transferHoldsProcess.value = false
        // Said out loud. The transfer used to vanish with the session and the reconnected one
        // looked perfectly healthy, so the user had no reason to doubt that their file had
        // arrived — and no way to find out that it had not, short of going to look.
        val interrupted = abandoned?.let {
            getApplication<Application>().getString(FileTransfers.interruptedNotice(it), it.name)
        }
        if (interrupted != null) _notice.value = interrupted
        input.clear(); pendingSamples.clear()
        oldVideo?.stop(); oldAudio?.stop(); oldMessages?.interrupt()
        if (oldSession != null) {
            val preceding = cleanupJob
            cleanupJob = cleanupScope.launch {
                preceding?.join()
                closeLogged(oldSession)
                if (oldVideo?.awaitStopped(2_000) == false) Log.w(TAG, "Video worker shutdown timed out")
                if (oldAudio?.awaitStopped(2_000) == false) Log.w(TAG, "Audio worker shutdown timed out")
                oldMessages?.join(2_000)
            }
        }
        return interrupted
    }

    private fun closeLogged(resource: AutoCloseable) {
        try { resource.close() }
        catch (e: Exception) { Log.w(TAG, "Resource cleanup failed", e) }
    }

    override fun onCleared() {
        cleared = true
        disconnect()
        // Nothing is left to offer the recording to once this is gone, so its cached copy goes
        // with it rather than sitting in the cache directory unreferenced.
        discardRecording()
        consumeFinishedRecording(keep = false)
        audioFocus.close()
        // Last resort: the Activity normally stops the service when this flow clears, but if the
        // ViewModel is destroyed without that collector running the notification would otherwise
        // outlive the session it claims to be keeping alive.
        MirrorSessionService.stop(getApplication())
        val connection = connectJob
        val cleanup = cleanupJob
        val transferCleanup = transferCleanupJob
        val recordingFinish = recordingFinishJob
        cleanupScope.launch {
            recordingFinish?.join()
            connection?.join()
            cleanup?.join()
            transferCleanup?.join()
            cleanupScope.cancel()
        }
        super.onCleared()
    }

    companion object {
        /** The device's own words when it refused cameras; null for internal errors, which are not for users. */
        internal fun cameraListFailureMessage(error: Throwable): String? {
            val text = error.message?.takeIf { it.isNotBlank() } ?: return null
            val refused = error is com.scrcpy.remote.scrcpy.ScrcpyServerRefusedException ||
                text.contains("Camera mirroring is not supported", ignoreCase = true)
            return text.takeIf { refused }
        }

        private class BundleTooLargeException : java.io.IOException("The bundle's files are too large")

        private const val STATE_INPUT_LOCKED = "mirror.inputLocked"
        private const val TAG = "MirrorViewModel"
        private const val LIVE_ACK_TIMEOUT_MS = 600L
        private const val LIVE_PASTE_SETTLE_MS = 250L
        private const val LIVE_BATCH_MS = 40L
        /** Network-bound startup: connect, push the server, open its sockets. */
        private const val STARTUP_TIMEOUT_MS = 45_000L
        /**
         * Added once the device asks its user to allow this phone; matches the handshake's own
         * extension so the outer deadline never cuts it short.
         */
        private val USER_AUTHORISATION_EXTRA_MS =
            (com.scrcpy.remote.adb.AdbDeviceConnection.CONNECT_TIMEOUT_SECONDS +
                com.scrcpy.remote.adb.AdbDeviceConnection.USER_AUTHORISATION_TIMEOUT_SECONDS) * 1_000L
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        /**
         * Cache folder recordings are staged in before the user chooses where to keep them.
         *
         * Not private: the file provider that shares a finished recording is configured for this
         * exact directory, and a rename here that did not reach `shared_artefacts.xml` would stop
         * sharing working with no compile error to say so.
         */
        internal const val RECORDING_DIRECTORY = "recordings"

        /** Saved-state key naming the recording still offered, so it survives the process. */
        internal const val STATE_FINISHED_RECORDING = "mirror.finishedRecording"
        private const val STATE_FINISHED_RECORDING_SHARED = "mirror.finishedRecordingShared"

        /** What [startRecording] names a recording; a restored name must look the same. */
        private val RECORDING_NAME = Regex("""session-\d+\.mp4""")

        /** How long an abandoned recording is kept before a later session sweeps it away. */
        private const val STALE_RECORDING_MILLIS = 6L * 60 * 60 * 1000

        /** The least time between two key frames a recording asks the device for. */
        private const val RECORDING_KEY_FRAME_SPACING_MS = 250L

        /** The size an offered app's icon is drawn at. */
        private const val APP_ICON_DP = 48f
        private const val DESKTOP_RESIZE_DEBOUNCE_MILLIS = 150L
        private const val DESKTOP_RESIZE_TIMEOUT_MILLIS = 10_000L
    }
}

/** A failure's diagnosis with the endpoint and the redacted text "Copy diagnostic details" copies. */
data class FailureReport(val diagnosis: ConnectionDiagnosis, val host: String, val port: Int, val details: String)
