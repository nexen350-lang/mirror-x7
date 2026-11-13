package com.scrcpy.remote.ui

import java.util.UUID

import com.scrcpy.remote.R

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import com.scrcpy.remote.ui.design.deviceAvatarColors
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import com.scrcpy.remote.data.AppUxPreferences
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.sameEndpoint
import com.scrcpy.remote.data.takeWholeChars
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.DeviceShortcuts
import com.scrcpy.remote.data.duplicateDraft
import com.scrcpy.remote.session.ApkInstall
import com.scrcpy.remote.session.ShareCache
import com.scrcpy.remote.ui.design.DeviceAvatar
import com.scrcpy.remote.ui.design.avatarPaletteIndex
import com.scrcpy.remote.ui.design.avatarPaletteIndices
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val transfers: ProfileTransferViewModel by viewModels()
    private val history: BackupHistoryViewModel by viewModels()
    private val exportProfiles = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        transfers.exportSelected(it)
    }
    private val importProfiles = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        transfers.importSelected(it)
    }

    /** A device offered by a `scrcpy://` link, awaiting confirmation. */
    private var pendingLink by mutableStateOf<LinkEndpoint?>(null)
    private var invalidLink by mutableStateOf(false)
    /** Set when the failure screen asks to re-pair: opens the pairing dialog once. */
    private var openPairingRequest by mutableStateOf(false)

    /**
     * Absolute cache path of a file another app shared, copied while its read grant was still
     * valid. Held here because a share names a file but not a destination: the device list is the
     * destination picker.
     */
    private var pendingShare by mutableStateOf<String?>(null)
    private var pendingShareName by mutableStateOf<String?>(null)
    private var invalidShare by mutableStateOf(false)

    /** A share that carried no file at all, such as text or a link. */
    private var noFileShare by mutableStateOf(false)

    /** A share whose copy was still running when the app's process went away, taking it along. */
    private var interruptedShare by mutableStateOf(false)
    private var showOnboardingOnLaunch by mutableStateOf(false)
    private val uxPreferences by lazy { AppUxPreferences.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            val linkLaunched = isConnectionLink(intent?.action, intent?.data)
            handleDeepLink(intent)
            handleShare(intent)
            val shortcutHandled = handleShortcut(intent)
            handleOpenPairing(intent)
            val explicitLaunch = linkLaunched ||
                shortcutHandled ||
                openPairingRequest ||
                pendingLink != null ||
                invalidLink ||
                pendingShare != null ||
                viewModel.shareCopying.value ||
                noFileShare
            if (!explicitLaunch) {
                if (!uxPreferences.onboardingCompleted) {
                    showOnboardingOnLaunch = true
                } else {
                    uxPreferences.resolveAutoReconnectTarget(viewModel.devices.value)?.let { target ->
                        startMirroring(target.host, target.port, target.id)
                    }
                }
            }
        } else {
            savedInstanceState.getString(PENDING_LINK_HOST)?.let { host ->
                pendingLink = LinkEndpoint(
                    host = host,
                    port = savedInstanceState.getInt(PENDING_LINK_PORT, 5555),
                    name = savedInstanceState.getString(PENDING_LINK_NAME),
                    request = LinkRequest.parseQuery(savedInstanceState.getString(PENDING_LINK_OPTIONS)),
                )
            }
            invalidLink = savedInstanceState.getBoolean(INVALID_LINK)
            invalidShare = savedInstanceState.getBoolean(INVALID_SHARE)
            noFileShare = savedInstanceState.getBoolean(NO_FILE_SHARE)
            pendingShare = savedInstanceState.getString(PENDING_SHARE)
            pendingShareName = savedInstanceState.getString(PENDING_SHARE_NAME)
            // A rotation keeps the copy running in the same view model; a new process has none,
            // and the sharing app's read grant left with the old one. Say so instead of nothing.
            interruptedShare = savedInstanceState.getBoolean(INTERRUPTED_SHARE) || shareCopyWasLost(
                copyingWhenSaved = savedInstanceState.getBoolean(SHARE_COPYING),
                copyingNow = viewModel.shareCopying.value,
                resultWaiting = viewModel.shareIngest.value != null,
                pendingShare = pendingShare,
            )
            handleOpenPairing(intent)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Mirrored into the launcher so a long-press reconnects without opening the app.
                // Keyed on what the launcher displays: a saved device also carries per-session
                // touch calibration, and republishing on every nudge of that would spend the
                // launcher's rate limit on identical shortcuts.
                viewModel.devices
                    .map { it to DeviceShortcuts.signature(it) }
                    .distinctUntilChangedBy { (_, signature) -> signature }
                    .flowOn(Dispatchers.Default)
                    .collect { (devices, _) ->
                        // Preferences commit and ShortcutManager binder calls stay off Main.
                        withContext(Dispatchers.IO) {
                            DeviceShortcutPublisher.publish(this@MainActivity, devices)
                        }
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                transfers.state.collect {
                    when (val picker = transfers.takePickerRequest()) {
                        is ProfilePicker.Export -> try {
                            exportProfiles.launch(picker.suggestedName)
                        } catch (_: ActivityNotFoundException) { transfers.pickerUnavailable() }
                        ProfilePicker.Import -> try {
                            importProfiles.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (_: ActivityNotFoundException) { transfers.pickerUnavailable() }
                        null -> Unit
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Applied here rather than where the share arrives: the copy runs in the
                // ViewModel so a recreation cannot cancel it, and its result waits until an
                // activity is around to take it.
                viewModel.shareIngest.collect { ingest ->
                    when (ingest) {
                        is ShareIngest.Ready -> {
                            ShareCache.discard(pendingShare)
                            pendingShare = ingest.path
                            pendingShareName = ingest.name
                            // A device picked while it copied gets it now.
                            viewModel.takeShareDestination()?.let { device ->
                                startMirroring(device.host, device.port, device.id, carryShare = true)
                            }
                        }
                        ShareIngest.Unreadable -> {
                            viewModel.takeShareDestination()
                            invalidShare = true
                        }
                        null -> return@collect
                    }
                    viewModel.consumeShareIngest()
                }
            }
        }
        setContent {
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            ScrcpyRemoteTheme(darkTheme = darkTheme) {
                val shareCopying by viewModel.shareCopying.collectAsState()
                val shareIngest by viewModel.shareIngest.collectAsState()
                val shareDestination by viewModel.shareDestination.collectAsState()
                var reconnectLastDevice by remember { mutableStateOf(uxPreferences.reconnectLastDeviceOnLaunch) }
                MainScreen(
                    viewModel = viewModel,
                    openPairing = openPairingRequest,
                    onPairingOpened = { openPairingRequest = false },
                    transfers = transfers,
                    history = history,
                    onConnect = ::connectFromList,
                    // Pending from the moment the share arrives, not only once its copy is taken:
                    // the copy can take a while, and the list is the destination picker meanwhile.
                    sharePending = pendingShare != null || shareCopying || shareIngest is ShareIngest.Ready,
                    shareCopying = shareCopying,
                    shareDestinationName = shareDestination?.name,
                    // An app is asked about once the session is up, so the banner says so.
                    shareIsApp = ApkInstall.looksLikeApk(
                        pendingShareName ?: (shareIngest as? ShareIngest.Ready)?.name, null),
                    onCancelShare = {
                        viewModel.cancelShare()
                        // The cache copy is ours now; dropping the reference without deleting it
                        // would leave the file behind until another share replaced it.
                        ShareCache.discard(pendingShare)
                        pendingShare = null
                        pendingShareName = null
                    },
                    onQuickConnect = { host, port, linkOptions ->
                        startMirroring(host, port, extraLinkOptions = linkOptions)
                    },
                    reconnectLastDevice = reconnectLastDevice,
                    onReconnectLastDeviceChange = { enabled ->
                        reconnectLastDevice = enabled
                        uxPreferences.reconnectLastDeviceOnLaunch = enabled
                    },
                    initialShowOnboarding = showOnboardingOnLaunch,
                    onOnboardingConsumed = {
                        showOnboardingOnLaunch = false
                        uxPreferences.onboardingCompleted = true
                    },
                )
                pendingLink?.let { link ->
                    // A link that points at a phone already in the list connects as that phone,
                    // so its per-device settings and touch calibration apply. Without this a
                    // shared link always produced a bare, unconfigured connection even when the
                    // user had already set that device up.
                    val matches = viewModel.devices.collectAsState().value.filter {
                        sameEndpoint(it.host, it.port, link.host, link.port)
                    }
                    // Several profiles can share an address. The link's own name may preselect
                    // the one profile carrying exactly that name; the user still confirms it.
                    var chosenId by rememberSaveable(link) {
                        mutableStateOf(matches.singleOrNull()?.id ?: matches.singleOrNull { it.name == link.name }?.id)
                    }
                    val saved = matches.singleOrNull() ?: matches.firstOrNull { it.id == chosenId }
                    LinkConfirmationDialog(
                        device = saved ?: SavedDevice(
                            name = link.name ?: link.host, host = link.host, port = link.port),
                        saved = saved != null,
                        requests = linkRequestSummary(link),
                        profiles = if (matches.size > 1) matches else emptyList(),
                        selectedProfileId = chosenId,
                        onSelectProfile = { chosenId = it },
                        onConfirm = {
                            if (pendingLink == link && (matches.size <= 1 || saved != null)) {
                                pendingLink = null
                                startMirroring(link.host, link.port, saved?.id, link = link)
                            }
                        },
                        onDismiss = { pendingLink = null },
                    )
                }
                if (invalidLink) {
                    AlertDialog(
                        onDismissRequest = { invalidLink = false },
                        title = { Text(stringResource(R.string.ui_invalid_link_title)) },
                        text = { Text(stringResource(R.string.ui_invalid_link_message)) },
                        confirmButton = {
                            TextButton(onClick = { invalidLink = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
                if (invalidShare) {
                    AlertDialog(
                        onDismissRequest = { invalidShare = false },
                        title = { Text(stringResource(R.string.share_pick_device)) },
                        text = { Text(stringResource(R.string.send_file_unreadable)) },
                        confirmButton = {
                            TextButton(onClick = { invalidShare = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
                if (noFileShare) {
                    AlertDialog(
                        onDismissRequest = { noFileShare = false },
                        title = { Text(stringResource(R.string.share_no_file_title)) },
                        text = { Text(stringResource(R.string.share_no_file_message)) },
                        confirmButton = {
                            TextButton(onClick = { noFileShare = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
                if (interruptedShare) {
                    AlertDialog(
                        onDismissRequest = { interruptedShare = false },
                        title = { Text(stringResource(R.string.share_pick_device)) },
                        text = { Text(stringResource(R.string.share_interrupted)) },
                        confirmButton = {
                            TextButton(onClick = { interruptedShare = false }) {
                                Text(stringResource(R.string.close))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        handleShare(intent)
        handleShortcut(intent)
        handleOpenPairing(intent)
    }

    /** Read once and cleared, so a rotation does not reopen the pairing dialog. */
    private fun handleOpenPairing(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PAIRING, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PAIRING)
            openPairingRequest = true
        }
    }

    override fun onDestroy() {
        // Once this screen is gone nothing can choose a destination for the copy a share left
        // here, so it would sit in the cache until a later share happened to sweep it.
        if (isFinishing) {
            ShareCache.discard(pendingShare)
            pendingShare = null
            pendingShareName = null
        }
        super.onDestroy()
    }

    /**
     * Connects straight to the device a launcher shortcut names.
     *
     * No confirmation, unlike a `scrcpy://` link: the id is only honoured when it matches a
     * device the user has already saved, so the worst another app could do with this extra is
     * connect to one of their own devices. A link can name any address, which is why that one
     * still asks.
     */
    private fun handleShortcut(intent: Intent?): Boolean {
        if (intent == null) return false
        val id = intent.getStringExtra(EXTRA_SHORTCUT_DEVICE_ID)
        val host = intent.getStringExtra(EXTRA_SHORTCUT_HOST)
        if (id == null && host == null) return false
        intent.removeExtra(EXTRA_SHORTCUT_DEVICE_ID)
        intent.removeExtra(EXTRA_SHORTCUT_HOST)
        // Only a shortcut this app published (VIEW action plus the per-install nonce) is
        // pre-approved; any other caller of this exported activity gets the confirmation prompt.
        val trusted = ShortcutTrust.isTrusted(
            intent.action, intent.getStringExtra(EXTRA_SHORTCUT_NONCE), ShortcutTrust.nonce(this))
        intent.removeExtra(EXTRA_SHORTCUT_NONCE)
        val saved = id?.takeIf { trusted }
            ?.let { known -> viewModel.devices.value.firstOrNull { it.id == known } }
        if (saved != null) {
            // A shortcut says which device to connect to and nothing about sending a file, so any
            // pending share stays pending and the device list keeps offering it a destination.
            // A link prompt still waiting from earlier is superseded, though: left in place it
            // reappears when this session ends and can start the obsolete destination.
            pendingLink = null
            invalidLink = false
            startMirroring(saved.host, saved.port, saved.id)
            return true
        }
        // The saved device is gone — deleted, or lost with the app's data — but the shortcut
        // still knows where it pointed. Asking is what keeps this safe: this activity is
        // exported, so an address that is not already a saved device cannot be treated as
        // pre-approved just because it arrived in an intent.
        val port = intent.getIntExtra(EXTRA_SHORTCUT_PORT, 5555)
        intent.removeExtra(EXTRA_SHORTCUT_PORT)
        // Checked the same way a link's label is. This activity is exported, so these extras are
        // no more trustworthy than a link's, and this is the one piece of caller-supplied text
        // shown in the prompt that stands between an unknown address and a connection.
        val name = validatedLinkName(intent.getStringExtra(EXTRA_SHORTCUT_NAME))
        intent.removeExtra(EXTRA_SHORTCUT_NAME)
        if (host == null || SettingsValidation.endpointError(host, port) != null) return false
        invalidLink = false
        pendingLink = LinkEndpoint(host = host, port = port, name = name)
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingLink?.let {
            outState.putString(PENDING_LINK_HOST, it.host)
            outState.putInt(PENDING_LINK_PORT, it.port)
            it.name?.let { name -> outState.putString(PENDING_LINK_NAME, name) }
            it.request.toQuery().takeIf { query -> query.isNotEmpty() }
                ?.let { query -> outState.putString(PENDING_LINK_OPTIONS, query) }
        }
        outState.putBoolean(INVALID_LINK, invalidLink)
        outState.putBoolean(INVALID_SHARE, invalidShare)
        outState.putBoolean(NO_FILE_SHARE, noFileShare)
        pendingShare?.let { outState.putString(PENDING_SHARE, it) }
        pendingShareName?.let { outState.putString(PENDING_SHARE_NAME, it) }
        outState.putBoolean(SHARE_COPYING, viewModel.shareCopying.value)
        outState.putBoolean(INTERRUPTED_SHARE, interruptedShare)
        super.onSaveInstanceState(outState)
    }

    /**
     * Supports `scrcpy://connect?host=192.168.1.20&port=5555` and `scrcpy://host:port`.
     *
     * Any app can fire this, so it asks first rather than opening an ADB connection to whatever
     * host the link names.
     */
    private fun handleDeepLink(intent: Intent?) {
        if (intent == null || !isConnectionLink(intent.action, intent.data)) return
        val endpoint = parseLinkEndpoint(intent.data?.toString())
        intent.data = null
        invalidLink = endpoint == null
        pendingLink = endpoint
    }

    /**
     * Accepts a file shared from another app. The device list then doubles as the destination
     * picker: choosing a device connects to it and sends the file once the session is up.
     *
     * The bytes are copied into this app's cache straight away. A share's read grant belongs to
     * the activity that received it and does not survive being handed to the session activity, so
     * the only reliable moment to read the file is now.
     */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // EXTRA_STREAM is the standard place, but a sender may name the file only in its ClipData.
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.clipData = null
        // Whatever this share turns out to be, it replaces what was said about the one before.
        // A message left from an earlier share stayed over the new file's destination picker,
        // saying the share had failed when it had not.
        invalidShare = false
        noFileShare = false
        interruptedShare = false
        if (uri == null) {
            // The share filter has to accept every type, so text and links arrive here too. They
            // carry nothing to send; saying so beats opening as if nothing had been shared. A file
            // already waiting for a destination is left alone.
            noFileShare = true
            return
        }
        // This share supersedes anything still waiting for a destination. Dropping it now is what
        // stops a device chosen while the new copy is still running from sending the previous
        // file instead of the one the user just shared.
        ShareCache.discard(pendingShare)
        pendingShare = null
        pendingShareName = null
        viewModel.ingestShare(uri)
    }

    /**
     * A device picked from the list: the destination for a waiting share, if there is one.
     *
     * While the shared file is still copying, the pick is kept and connects with the file once it
     * lands.
     */
    private fun connectFromList(device: SavedDevice) {
        if (viewModel.queueShareDestination(device)) return
        startMirroring(device.host, device.port, device.id, carryShare = true)
    }

    /**
     * Starts mirroring [host]:[port].
     *
     * @param carryShare send a file that is waiting for a destination. Only true when the user
     * picked this device from the list while the share banner was on screen: that is the one
     * action that means "send it there". A deep link or a launcher shortcut says to connect, and
     * neither dialog mentions uploading anything, so neither may quietly take the file with it.
     */
    private fun startMirroring(
        host: String,
        port: Int,
        deviceId: String? = null,
        carryShare: Boolean = false,
        link: LinkEndpoint? = null,
        extraLinkOptions: String? = null,
    ) {
        val share = pendingShare.takeIf { carryShare }
        val shareName = pendingShareName.takeIf { carryShare }
        if (carryShare) {
            pendingShare = null
            pendingShareName = null
        }
        if (deviceId != null) {
            uxPreferences.lastConnectedDeviceId = deviceId
        }
        val options = link?.request?.toQuery()?.takeIf { it.isNotEmpty() }
            ?: extraLinkOptions?.takeIf { it.isNotEmpty() }
        startActivity(MirrorActivity.newIntent(
            this, host, port, deviceId, share, shareName,
            linkOptions = options,
        ))
    }

    companion object {
        private const val PENDING_LINK_HOST = "pendingLink.host"
        private const val PENDING_LINK_PORT = "pendingLink.port"
        private const val PENDING_LINK_NAME = "pendingLink.name"
        private const val PENDING_LINK_OPTIONS = "pendingLink.options"
        private const val INVALID_SHARE = "invalidShare"
        private const val NO_FILE_SHARE = "noFileShare"
        private const val INVALID_LINK = "pendingLink.invalid"
        private const val PENDING_SHARE = "pendingShare.path"
        private const val PENDING_SHARE_NAME = "pendingShare.name"
        private const val SHARE_COPYING = "pendingShare.copying"
        private const val INTERRUPTED_SHARE = "pendingShare.interrupted"

        /** Names a saved device by id; only honoured when that device still exists. */
        const val EXTRA_SHORTCUT_DEVICE_ID = "shortcutDeviceId"

        /**
         * Where the shortcut pointed, used when its saved device no longer exists.
         *
         * Never connects on its own: an address that is not already a saved device goes through
         * the same confirmation as a link, because this activity is exported.
         */
        /** Asks the device list to open the pairing dialog; harmless from any caller. */
        const val EXTRA_OPEN_PAIRING = "openPairing"
        const val EXTRA_SHORTCUT_NONCE = "shortcutNonce"
        const val EXTRA_SHORTCUT_HOST = "shortcutHost"
        const val EXTRA_SHORTCUT_PORT = "shortcutPort"
        const val EXTRA_SHORTCUT_NAME = "shortcutName"
    }
}

/**
 * A connection link's destination, and anything it asks to change for that one session.
 *
 * [name] is optional and only a label: a link that carries one can say which phone it points at
 * before anything is connected, which matters because the confirmation dialog is the only thing
 * standing between an arbitrary link and an ADB connection.
 *
 * The overrides are deliberately limited to how the stream looks and sounds. A link comes from
 * outside the app, so it may not reach anything with a lasting effect — it cannot launch an app,
 * create a display, or change what is saved. Whatever it does ask for is shown in the prompt and
 * applies to that session only.
 */
internal data class LinkEndpoint(
    val host: String,
    val port: Int,
    val name: String? = null,
    val request: LinkRequest = LinkRequest.EMPTY,
) {
    /** Whether this link asks for anything beyond where to connect. */
    val hasOverrides: Boolean get() = !request.isEmpty
}

/** Parameters a `scrcpy://connect` link may carry. */
private val LINK_PARAMETERS = setOf("host", "port", "name") + LinkRequest.NAMES

/** Longest label a link may carry, matching what the device list will store. */
private const val LINK_NAME_LIMIT = 100

/**
 * Builds the canonical link for [host]:[port], optionally labelled [name].
 *
 * The query form is used rather than `scrcpy://host:port` because it is the one that can carry a
 * name and the per-session overrides, and because a reader can tell at a glance which part is the
 * address.
 */
internal fun connectionLink(
    host: String,
    port: Int,
    name: String? = null,
    request: LinkRequest = LinkRequest.EMPTY,
): String = buildString {
    append("scrcpy://connect?host=")
    append(URLEncoder.encode(host, "UTF-8"))
    append("&port=")
    append(port)
    name?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append("&name=")
        append(URLEncoder.encode(it.takeWholeChars(LINK_NAME_LIMIT), "UTF-8"))
    }
    request.toQuery().takeIf { it.isNotEmpty() }?.let { append('&').append(it) }
}

/**
 * A label a link or shortcut may carry, or null if it is unusable.
 *
 * This is the one piece of attacker-supplied text shown in the prompt that guards connecting to
 * an address nobody saved, so it has to be something a reader can actually see and judge. Beyond
 * the C0 controls, Unicode's format and separator characters are dropped: they are invisible, and
 * a label made only of those would otherwise pass the "not empty" check and render as nothing at
 * all next to the address it is supposed to describe.
 */
internal fun validatedLinkName(value: String?): String? {
    if (value == null) return null
    // By code point: an invisible character outside the Basic Multilingual Plane reaches Kotlin as
    // two surrogate halves, each of which looks like an ordinary character on its own.
    val visible = buildString {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == 0 -> append(' ')
                codePoint < 0x20 || isInvisibleFormatting(codePoint) || isBlankFiller(codePoint) -> Unit
                else -> appendCodePoint(codePoint)
            }
        }
    }
    // Something a reader can see: not only spaces, combining marks and blank "filler" glyphs.
    if (!visible.codePoints().anyMatch { hasVisibleBase(it) }) return null
    return visible.trim().takeIf { it.isNotEmpty() }?.takeWholeChars(LINK_NAME_LIMIT)
}

/** Letters and symbols that render as blank: Hangul fillers, Braille blank, Khmer inherent vowels, variation selectors. */
private fun isBlankFiller(codePoint: Int): Boolean = when (codePoint) {
    0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800, 0x17B4, 0x17B5, 0x034F, 0x00AD,
    in 0xFE00..0xFE0F, in 0xE0100..0xE01EF -> true
    else -> false
}

private fun hasVisibleBase(codePoint: Int): Boolean = !isBlankFiller(codePoint) &&
    !Character.isWhitespace(codePoint) && when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(), Character.SPACE_SEPARATOR.toInt(),
        Character.UNASSIGNED.toInt(), Character.PRIVATE_USE.toInt() -> false
        else -> true
    }

/**
 * Code points that occupy no width, including the C1 controls, joiners and bidi overrides, and
 * an unpaired surrogate, which is not a character at all. A properly paired one, such as an
 * emoji, is a single code point here and is kept.
 */
private fun isInvisibleFormatting(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.FORMAT.toInt(), Character.CONTROL.toInt(), Character.SURROGATE.toInt(),
    Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
    else -> false
}

/**
 * Decodes one `application/x-www-form-urlencoded` query component, or null when it is malformed.
 *
 * Strict where `URLDecoder` is lenient: bytes that are not valid UTF-8 refuse the link instead of
 * becoming replacement characters, which would show the user a label the link never carried.
 * `+` still means a space, because links are written with `URLEncoder`.
 */
private fun decodeQueryComponent(value: String): String? {
    val bytes = java.io.ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        when (val char = value[index]) {
            '+' -> { bytes.write(' '.code); index++ }
            '%' -> {
                if (index + 2 >= value.length) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                bytes.write(high * 16 + low)
                index += 3
            }
            else -> {
                if (char.isLowSurrogate()) return null
                if (char.isHighSurrogate() &&
                    !(index + 1 < value.length && value[index + 1].isLowSurrogate())
                ) return null
                val end = index + if (char.isHighSurrogate()) 2 else 1
                bytes.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** Parse without Android's permissive URI port fallback or opaque-URI query exceptions. */
/**
 * Whether an intent is a `scrcpy://` connection link rather than something else entirely.
 *
 * Both halves are required. A launcher shortcut arrives as an `ACTION_VIEW` carrying no data at
 * all, so treating "a VIEW, or anything with data" as a link made every shortcut tap report a
 * broken link on top of the session it had just started.
 */
internal fun isConnectionLink(action: String?, data: android.net.Uri?): Boolean =
    action == Intent.ACTION_VIEW && data != null

internal fun parseLinkEndpoint(value: String?): LinkEndpoint? {
    if (value.isNullOrBlank()) return null
    return try {
        val uri = URI(value)
        if (!uri.scheme.equals("scrcpy", ignoreCase = true) || uri.isOpaque ||
            uri.rawUserInfo != null || uri.rawFragment != null ||
            !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/"
        ) return null
        val authority = uri.rawAuthority ?: return null
        if (authority.equals("connect", ignoreCase = true)) {
            val query = uri.rawQuery ?: return null
            val parameters = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                val separator = part.indexOf('=')
                if (separator < 1) return null
                val key = decodeQueryComponent(part.substring(0, separator)) ?: return null
                val content = decodeQueryComponent(part.substring(separator + 1)) ?: return null
                if (key !in LINK_PARAMETERS || parameters.put(key, content) != null) return null
            }
            val host = validatedLinkHost(parameters["host"] ?: return null) ?: return null
            val port = parameters["port"]?.let { parseLinkPort(it) ?: return null } ?: 5555
            // An unrecognised value refuses the whole link rather than being ignored: a link that
            // silently did something other than what it says would be worse than one that does
            // not open at all.
            val request = LinkRequest.parse(parameters) ?: return null
            LinkEndpoint(host, port, validatedLinkName(parameters["name"]), request)
        } else {
            if (uri.rawQuery != null) return null
            // URI.getHost() is null for names it deems invalid hostnames (underscores, a digit-led
            // last label), so fall back to the raw authority and let validatedLinkHost judge.
            val rawHost = uri.host ?: if (authority.startsWith('[')) {
                authority.substring(0, authority.indexOf(']') + 1)
            } else {
                authority.substringBefore(':')
            }
            val host = validatedLinkHost(rawHost) ?: return null
            val suffix = if (authority.startsWith('[')) {
                authority.substring(authority.indexOf(']') + 1)
            } else {
                authority.substringAfter(':', "")
                    .let { if (':' in authority) ":$it" else "" }
            }
            val port = if (suffix.isEmpty()) 5555 else {
                if (!suffix.startsWith(':')) return null
                parseLinkPort(suffix.substring(1)) ?: return null
            }
            LinkEndpoint(host, port)
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
}

private fun parseLinkPort(value: String): Int? =
    value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * What a link is asking to change, in the user's words, or null when it only names a destination.
 *
 * Shown in the prompt because a link comes from outside the app: agreeing to connect should not
 * also mean agreeing to something unseen.
 */
@Composable
internal fun linkRequestSummary(link: LinkEndpoint): String? {
    val request = link.request
    if (request.isEmpty) return null
    val parts = buildList {
        request.preset?.let { add(stringResource(R.string.link_request_preset, stringResource(it.labelRes))) }
        request.maxSize?.let { add(stringResource(R.string.link_request_max_size, it)) }
        request.bitrateMbps?.let { add(stringResource(R.string.link_request_bitrate, it)) }
        request.maxFps?.let { add(stringResource(R.string.link_request_fps, it)) }
        request.codec?.let { add(stringResource(R.string.link_request_codec, it.label)) }
        request.orientation?.let { add(stringResource(R.string.link_request_orientation, stringResource(it.labelRes))) }
        request.displayId?.let { add(stringResource(R.string.link_request_display, it)) }
        request.audio?.let {
            val source = it.source
            add(if (source == null) stringResource(R.string.link_request_audio_off)
                else stringResource(R.string.link_request_audio, stringResource(source.labelRes)))
        }
        request.audioCodec?.let { add(stringResource(R.string.link_request_audio_codec, it.label)) }
        request.screenOff?.let {
            add(stringResource(if (it) R.string.link_request_screen_off else R.string.link_request_screen_on))
        }
        request.stayAwake?.let {
            add(stringResource(if (it) R.string.link_request_awake_on else R.string.link_request_awake_off))
        }
        request.showTouches?.let {
            add(stringResource(if (it) R.string.link_request_touches_on else R.string.link_request_touches_off))
        }
        request.audioDup?.let {
            add(stringResource(if (it) R.string.link_request_audio_dup_on else R.string.link_request_audio_dup_off))
        }
        request.audioBitrateKbps?.let { add(stringResource(R.string.link_request_audio_bitrate, it)) }
        request.keepActive?.let {
            add(stringResource(if (it) R.string.link_request_keep_active_on else R.string.link_request_keep_active_off))
        }
        request.server?.let { add(stringResource(R.string.link_request_server, it.version)) }
    }
    if (parts.isEmpty()) return null
    return stringResource(R.string.link_request_summary, parts.joinToString(", "))
}

private fun validatedLinkHost(value: String): String? {
    val host = value.removeSurrounding("[", "]")
    if (host.isBlank() || host.any { it.isWhitespace() || it.isISOControl() || it in "/\\@?#[]" }) return null
    // `%` is the one character that has to be judged rather than banned outright: it introduces a
    // zone in a link-local IPv6 address, which the typed-address validator already accepts, but
    // anywhere else it is a leftover from a malformed encoding.
    if ('%' in host && ':' !in host) return null
    return host.takeIf { SettingsValidation.endpointError(it, 5555) == null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    openPairing: Boolean = false,
    onPairingOpened: () -> Unit = {},
    transfers: ProfileTransferViewModel,
    history: BackupHistoryViewModel,
    onConnect: (SavedDevice) -> Unit,
    sharePending: Boolean,
    onCancelShare: () -> Unit,
    shareCopying: Boolean = false,
    shareDestinationName: String? = null,
    shareIsApp: Boolean = false,
    onQuickConnect: (host: String, port: Int, linkOptions: String) -> Unit = { _, _, _ -> },
    reconnectLastDevice: Boolean = false,
    onReconnectLastDeviceChange: ((Boolean) -> Unit)? = null,
    initialShowOnboarding: Boolean = false,
    onOnboardingConsumed: () -> Unit = {},
) {
    val devices by viewModel.devices.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    val pairingDiscoveryState by viewModel.pairingDiscoveryState.collectAsState()
    val postPairingState by viewModel.postPairingState.collectAsState()
    val qrPairingState by viewModel.qrPairingState.collectAsState()
    val persistenceIssues by viewModel.errors.collectAsState()
    val transfer by transfers.state.collectAsState()
    val mutation by viewModel.mutation.collectAsState()
    val historyState by history.state.collectAsState()
    val historyEntries by history.entries.collectAsState()
    val context = LocalContext.current
    // Resources, not the context: a composable's string lookups must follow configuration and
    // locale changes rather than capture whatever the context held when it was first read.
    val resources = LocalResources.current
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var duplicateDraft by rememberSaveable(stateSaver = SavedDeviceDraftSaver) { mutableStateOf<SavedDevice?>(null) }
    // The device as the editor found it. A live lookup turned an editor whose device was deleted
    // elsewhere into an "Add device" dialog that saved a copy; the snapshot keeps it an edit, and
    // the repository refuses it and merges only the fields this editor changed.
    var editBaseline by rememberSaveable(stateSaver = SavedDeviceDraftSaver) { mutableStateOf<SavedDevice?>(null) }
    var addPrefillDraft by rememberSaveable(stateSaver = SavedDeviceDraftSaver) { mutableStateOf<SavedDevice?>(null) }
    var showDeviceDialog by rememberSaveable { mutableStateOf(false) }
    var showPairDialog by rememberSaveable { mutableStateOf(false) }
    var showQuickConnect by rememberSaveable { mutableStateOf(false) }
    var showOnboarding by rememberSaveable { mutableStateOf(initialShowOnboarding) }
    LaunchedEffect(initialShowOnboarding) {
        if (initialShowOnboarding) showOnboarding = true
    }
    LaunchedEffect(openPairing) { if (openPairing) { showPairDialog = true; onPairingOpened() } }
    LaunchedEffect(postPairingState) {
        if (postPairingState !is PostPairingState.Idle) {
            showPairDialog = false
        }
        val fallback = postPairingState as? PostPairingState.FallbackAddDevice
        if (fallback != null) {
            viewModel.dismissPostPairing()
            viewModel.resetPairingState()
            duplicateDraft = null
            editBaseline = null
            addPrefillDraft = SavedDevice(
                id = UUID.randomUUID().toString(),
                name = fallback.suggestedName.orEmpty(),
                host = fallback.host,
                port = 5555,
            )
            showDeviceDialog = true
        }
    }
    val connectionCheck by viewModel.connectionCheck.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var historyDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    val connectionHistory by viewModel.connectionHistory.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var pairingHelpExpanded by rememberSaveable { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    var mutationError by rememberSaveable { mutableStateOf<String?>(null) }
    var previewNotice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(mutation.requestId, mutation.busy, mutation.success) {
        if (mutation.success != null) {
            if (mutation.success == true) {
                if (mutation.action != MainMutationAction.FAVORITE) focusManager.clearFocus()
                when (mutation.action) {
                    MainMutationAction.SAVE_DEVICE -> { showDeviceDialog = false; duplicateDraft = null; editBaseline = null; addPrefillDraft = null }
                    MainMutationAction.DEVICE_SETTINGS -> settingsDeviceId = null
                    MainMutationAction.DEFAULTS -> showSettings = false
                    MainMutationAction.DELETE_DEVICE, MainMutationAction.FAVORITE, null -> Unit
                }
            } else {
                mutationError = mutation.error
            }
            viewModel.consumeMutation(mutation.requestId)
        }
    }
    LaunchedEffect(transfer.notice) {
        transfer.notice?.let {
            if (transfer.preview != null && !transfer.busy) {
                previewNotice = it
                transfers.clearNotice()
            } else {
                snackbar.showSnackbar(it)
                transfers.clearNotice()
            }
        }
    }
    // Removing asks nothing first; the message after it can take it back. Shown again after a
    // rotation, since the offer is kept with the list rather than with this screen.
    val recentlyRemoved by viewModel.recentlyRemoved.collectAsState()
    LaunchedEffect(recentlyRemoved?.serial) {
        val removed = recentlyRemoved ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            message = resources.getString(
                if (removed.restoreFailed) R.string.device_restore_failed else R.string.device_removed,
                removed.device.name,
            ),
            actionLabel = resources.getString(if (removed.restoreFailed) R.string.device_restore_retry else R.string.undo),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoRemoval(removed.serial)
        else viewModel.forgetRemoval(removed.serial)
    }
    val settingsDevice = devices.firstOrNull { it.id == settingsDeviceId }
    LaunchedEffect(settingsDeviceId, devices) {
        if (settingsDeviceId != null && settingsDevice == null) settingsDeviceId = null
    }
    val issueDetails = persistenceIssues.joinToString("\n\n") {
        "${listOfNotNull(it.key, it.field).joinToString(".")}: ${it.message}"
    }

    // One title, large while the list is at the top and collapsing as it scrolls, in place of the
    // app's name in a bar above a second, larger heading for the same page.
    val titleScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val addDevice: () -> Unit = {
        if (!mutation.busy) {
            editBaseline = null
            duplicateDraft = null
            showDeviceDialog = true
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().nestedScroll(titleScroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    // The page's heading for screen readers; the bar hides its second, collapsing
                    // copy of the title from them, so it is announced once.
                    Text(stringResource(R.string.release_devices_title), Modifier.semantics { heading() },
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = titleScroll,
                actions = {
                    IconButton(onClick = { showQuickConnect = true }, enabled = !mutation.busy) {
                        Icon(Icons.Default.Bolt, contentDescription = stringResource(R.string.quick_connect))
                    }
                    IconButton(onClick = { showSettings = true }, enabled = !mutation.busy) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    Box {
                        IconButton(
                            enabled = !transfer.busy && transfer.preview == null && !mutation.busy,
                            onClick = { showTools = true },
                        ) { Icon(Icons.Outlined.SettingsBackupRestore, stringResource(R.string.profile_tools_menu)) }
                        DropdownMenu(expanded = showTools, onDismissRequest = { showTools = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_tools_export)) },
                                leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                                onClick = { showTools = false; confirmExport = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.profile_tools_import)) },
                                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                                onClick = { showTools = false; transfers.beginImport() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.history_title)) },
                                leadingIcon = { Icon(Icons.Outlined.History, null) },
                                onClick = { showTools = false; history.open() })
                        }
                    }
                    // In the bar rather than floating over the list, where it rested on a card's
                    // Connect. An empty list offers Add device in place, beside its explanation.
                    if (devices.isNotEmpty()) {
                        IconButton(onClick = addDevice, enabled = !mutation.busy) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_device))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize()) {
            if (sharePending) {
                // A share names a file but not a destination, so the device list becomes the
                // picker and this explains why it is being shown.
                Surface(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.share_pick_device),
                            Modifier.semantics { heading() },
                            style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (shareIsApp) R.string.share_pick_device_help_app else R.string.share_pick_device_help),
                            style = MaterialTheme.typography.bodySmall)
                        // A large file takes a moment to copy in. Picking a device meanwhile is
                        // kept, and says so, rather than connecting without the file.
                        if (shareCopying) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            Text(
                                shareDestinationName?.let { stringResource(R.string.share_queued, it) }
                                    ?: stringResource(R.string.share_preparing),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = onCancelShare, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.share_cancel))
                        }
                    }
                }
            }
        DeviceList(
            devices = devices,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            pairingHelpExpanded = pairingHelpExpanded,
            onPairingHelpExpandedChange = { pairingHelpExpanded = it },
            contentPadding = PaddingValues(0.dp),
            onConnect = onConnect,
            onEdit = {
                editBaseline = it
                duplicateDraft = null
                showDeviceDialog = true
            },
            onDelete = { viewModel.deleteDevice(it.id) },
            onDeviceSettings = { settingsDeviceId = it.id },
            onDuplicate = { device ->
                duplicateDraft = device.duplicateDraft(resources.getString(R.string.profile_tools_duplicate_name, device.name))
                editBaseline = null
                showDeviceDialog = true
            },
            onCopyAddress = { device ->
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(device.name, device.address))
                scope.launch { snackbar.showSnackbar(resources.getString(R.string.profile_tools_address_copied)) }
            },
            onAddToHomeScreen = { device ->
                // The launcher shows its own confirmation, so success here only means the request
                // was accepted. A launcher that cannot pin says so rather than silently doing
                // nothing, which is how this looked when a browser bookmark was the only option.
                val asked = DeviceShortcutPublisher.requestPin(context, device)
                if (!asked) {
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.profile_tools_pin_unsupported)) }
                }
            },
            onShareLink = { device ->
                // Handed to the share sheet rather than only the clipboard, so the link can go
                // straight to whoever needs it. Opening one asks for confirmation before it
                // connects, which is what makes a link safe to pass around.
                val link = connectionLink(device.host, device.port, device.name)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, device.name)
                    putExtra(Intent.EXTRA_TEXT, link)
                }
                try {
                    context.startActivity(Intent.createChooser(send, resources.getString(R.string.profile_tools_share_link)))
                } catch (_: ActivityNotFoundException) {
                    // Nothing to share with: the link is still worth having, so fall back to the
                    // clipboard rather than failing silently.
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText(device.name, link))
                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.profile_tools_link_copied)) }
                }
            },
            onFavoriteChange = { device, favorite ->
                if (!mutation.busy) viewModel.setFavorite(device.id, favorite)
            },
            onPair = { showPairDialog = true },
            onAdd = addDevice,
            removalBusy = mutation.busy,
            onConnectionHistory = { historyDeviceId = it.id },
            onCheckConnection = viewModel::checkConnection,
            onQuickConnect = { showQuickConnect = true },
            onOpenOnboarding = { showOnboarding = true },
        )
        }
        }
    }
    ProfileTransferDialogs(
        state = transfer,
        onOptionsChanged = transfers::updateOptions,
        onConfirm = transfers::confirmImport,
        onDismiss = transfers::cancel,
    )
    BackupHistoryDialogs(
        state = historyState,
        entries = historyEntries,
        onDismiss = history::close,
        onRefresh = history::refresh,
        onSelect = history::select,
        onRestore = { history.prepareSnapshot(it, transfers::previewSnapshot) },
        onAskExport = history::askExport,
        onConfirmExport = { history.confirmExport { transfers.beginExport(it) } },
        onAskDelete = history::askDelete,
        onConfirmDelete = history::confirmDelete,
    )
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(R.string.profile_tools_export_title)) },
            text = { Text(stringResource(R.string.profile_tools_export_help), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; transfers.beginExport() }) {
                    Text(stringResource(R.string.profile_tools_export_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showDeviceDialog) {
        DeviceEditorDialog(
            device = duplicateDraft ?: editBaseline ?: addPrefillDraft,
            duplicate = duplicateDraft != null,
            saving = mutation.busy,
            onDismiss = { if (!mutation.busy) { showDeviceDialog = false; duplicateDraft = null; editBaseline = null; addPrefillDraft = null } },
            onSave = { edited ->
                val baseline = editBaseline
                if (duplicateDraft == null && baseline != null) viewModel.editDevice(baseline, edited)
                else viewModel.saveDevice(edited)
            },
            // The colour the device has, or will have once added at the end of the list.
            automaticColor = { id ->
                avatarPaletteIndices(devices.map { it.id }.let { ids -> if (id in ids) ids else ids + id }).getValue(id)
            },
        )
    }

    if (showPairDialog) {
        PairDialog(
            state = pairingState,
            onPair = { host, port, code -> viewModel.pair(host, port, code) },
            onDismiss = {
                showPairDialog = false
                viewModel.resetPairingState()
            },
            discoveryState = pairingDiscoveryState,
            onStartDiscovery = viewModel::startPairingDiscovery,
            onStopDiscovery = viewModel::stopPairingDiscovery,
            onPairWithName = { host, port, code, name -> viewModel.pair(host, port, code, name) },
            onPairWithQr = {
                showPairDialog = false
                viewModel.resetPairingState()
                viewModel.startQrPairing()
            },
        )
    }

    if (qrPairingState !is QrPairingState.Idle) {
        PairQrDialog(
            state = qrPairingState,
            onRetry = viewModel::startQrPairing,
            onCancel = viewModel::cancelQrPairing,
        )
    }

    when (val postPairing = postPairingState) {
        is PostPairingState.Searching -> PostPairingSearchingDialog(
            host = postPairing.host,
            onDismiss = viewModel::dismissPostPairing,
        )
        is PostPairingState.ConfirmAddAndConnect -> PostPairingConfirmDialog(
            device = postPairing.device,
            busy = mutation.busy,
            onConfirm = { viewModel.confirmPostPairingAddAndConnect(postPairing.device, onConnect) },
            onDismiss = viewModel::dismissPostPairing,
        )
        PostPairingState.Idle, is PostPairingState.FallbackAddDevice -> Unit
    }

    if (showQuickConnect) {
        QuickConnectDialog(
            onConnect = { host, port, linkOptions ->
                showQuickConnect = false
                onQuickConnect(host, port, linkOptions)
            },
            onDismiss = { showQuickConnect = false },
        )
    }
    if (showOnboarding) {
        OnboardingDialog(
            onFinish = {
                showOnboarding = false
                onOnboardingConsumed()
            },
        )
    }
    if (showSettings) {
        SettingsDialog(
            settings = settings,
            saving = mutation.busy,
            onDismiss = { if (!mutation.busy) showSettings = false },
            onSave = viewModel::applySettingsChanges,
            reconnectLastDevice = reconnectLastDevice,
            onReconnectLastDeviceChange = onReconnectLastDeviceChange,
            onOpenOnboarding = {
                showSettings = false
                showOnboarding = true
            },
        )
    }

    connectionCheck?.let { check ->
        ConnectionCheckDialog(
            state = check,
            onCheckAgain = viewModel::recheckConnection,
            onRepair = { viewModel.dismissConnectionCheck(); showPairDialog = true },
            onDismiss = viewModel::dismissConnectionCheck,
        )
    }

    devices.firstOrNull { it.id == historyDeviceId }?.let { device ->
        ConnectionHistoryDialog(
            device = device,
            history = connectionHistory[device.id],
            onClear = { viewModel.clearConnectionHistory(device.id) },
            onDismiss = { historyDeviceId = null },
        )
    }

    if (settingsDevice != null) {
        DeviceSettingsDialog(
            device = settingsDevice,
            defaults = settings,
            saving = mutation.busy,
            onDismiss = { if (!mutation.busy) settingsDeviceId = null },
            onSave = { baseline, edited ->
                viewModel.saveDeviceSettings(settingsDevice.id, baseline, edited)
            },
        )
    }

    if (mutationError != null || issueDetails.isNotEmpty()) {
        // Closing tells the repository these were seen, so a list opened later does not ask again.
        val close = { viewModel.acknowledgeIssues(persistenceIssues); mutationError = null }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.ui_storage_issue_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.ui_storage_issue_message))
                    mutationError?.let { Text(it) }
                    Text(issueDetails, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = close) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
    previewNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { previewNotice = null },
            title = { Text(stringResource(R.string.transfer_preview_title)) },
            // A notice can carry appended failure detail, so it has to scroll like its siblings.
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { Text(notice) }
            },
            confirmButton = { TextButton(onClick = { previewNotice = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (mutation.busy) StorageBusyDialog()
    else if (transfer.busy && transfer.preview != null) StorageBusyDialog(stringResource(R.string.transfer_working))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceList(
    devices: List<SavedDevice>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pairingHelpExpanded: Boolean,
    onPairingHelpExpandedChange: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    onConnect: (SavedDevice) -> Unit,
    onEdit: (SavedDevice) -> Unit,
    onDelete: (SavedDevice) -> Unit,
    onDeviceSettings: (SavedDevice) -> Unit,
    onDuplicate: (SavedDevice) -> Unit,
    onCopyAddress: (SavedDevice) -> Unit,
    onShareLink: (SavedDevice) -> Unit,
    onAddToHomeScreen: (SavedDevice) -> Unit,
    onFavoriteChange: (SavedDevice, Boolean) -> Unit,
    onPair: () -> Unit,
    onAdd: () -> Unit = {},
    removalBusy: Boolean = false,
    onConnectionHistory: (SavedDevice) -> Unit = {},
    onCheckConnection: (SavedDevice) -> Unit = {},
    onQuickConnect: (() -> Unit)? = null,
    onOpenOnboarding: (() -> Unit)? = null,
) {
    val visible = remember(devices, searchQuery) { visibleDevices(devices, searchQuery) }
    // From the whole list in its saved order, not the sorted or searched one, so a device keeps
    // its colour while searching or when another is made a favourite.
    val colours = remember(devices) { deviceAvatarColors(devices) }
    val focusManager = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().padding(contentPadding)
            // Android 8 focused the search field as the list opened, and again on every return
            // from a session, as though it had been tapped.
            .noInitialFocusInTouchMode()
            .testTag("saved-device-list"),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = 24.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nothing to search in an empty list, and a field there invited typing an address into
        // it, where nothing connects. Kept while a search is typed, so it can still be cleared.
        if (devices.isNotEmpty() || searchQuery.isNotEmpty()) item(key = "device-list-search") {
            DeviceSearchField(searchQuery, onSearchQueryChange, onSearch = { focusManager.clearFocus() })
        }

        if (visible.isEmpty()) {
            item(key = "device-list-empty") {
                if (devices.isEmpty()) EmptyDeviceList(onAdd = onAdd, onPair = onPair, onQuickConnect = onQuickConnect)
                else NoMatchingDevices(onClear = { onSearchQueryChange("") })
            }
        } else if (searchQuery.isNotBlank()) {
            item(key = "device-list-section") {
                Text(stringResource(R.string.release_search_section),
                    Modifier.padding(top = 4.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(visible, key = { "device:${it.id}" }) { device ->
            SwipeToRemove(busy = removalBusy, onRemove = { onDelete(device) }) {
                DeviceRow(
                    device = device,
                    onConnect = { onConnect(device) },
                    onEdit = { onEdit(device) },
                    onDelete = { onDelete(device) },
                    onDeviceSettings = { onDeviceSettings(device) },
                    onDuplicate = { onDuplicate(device) },
                    onCopyAddress = { onCopyAddress(device) },
                    onShareLink = { onShareLink(device) },
                    onAddToHomeScreen = { onAddToHomeScreen(device) },
                    onFavoriteChange = { onFavoriteChange(device, it) },
                    favoriteEnabled = !removalBusy,
                    removeEnabled = !removalBusy,
                    modifier = Modifier.testTag("device-card:${device.id}"),
                    avatarColour = colours[device.id] ?: avatarPaletteIndex(device.id),
                    onConnectionHistory = { onConnectionHistory(device) },
                    onCheckConnection = { onCheckConnection(device) },
                )
            }
        }

        // Setup help is for adding a device: after the devices once there are some, rather than
        // between the user and them on every visit, and under the empty list's buttons before.
        if (searchQuery.isBlank()) item(key = "device-list-pairing") {
            WirelessDebuggingCard(
                expanded = pairingHelpExpanded,
                onExpandedChange = onPairingHelpExpandedChange,
                onPair = onPair,
                // The empty list already offers pairing right above it.
                showPairButton = devices.isNotEmpty(),
                modifier = Modifier.padding(top = if (devices.isEmpty()) 0.dp else 8.dp),
                onOpenOnboarding = onOpenOnboarding,
            )
        }
    }
}

/** A filled, rounded search field: a tool on the page rather than a form field. */
@Composable
private fun DeviceSearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.device_list_search)) },
        placeholder = { Text(stringResource(R.string.device_list_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.device_list_clear_search))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** No devices yet: what this is for, and the two ways to add one, where the eye already is. */
@Composable
private fun EmptyDeviceList(
    onAdd: () -> Unit,
    onPair: () -> Unit,
    onQuickConnect: (() -> Unit)? = null,
) {
    val addLabel = stringResource(R.string.add_device)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.Phonelink, null, Modifier.padding(20.dp).size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(stringResource(R.string.release_empty_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(stringResource(R.string.release_empty_body), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        // Named as the bar's + is, which it stands in for while the list is empty.
        Button(onClick = onAdd, modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = addLabel }) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(addLabel)
        }
        OutlinedButton(onClick = onPair, modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 48.dp)) {
            Icon(Icons.Default.Link, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pair_new_device))
        }
        if (onQuickConnect != null) {
            TextButton(onClick = onQuickConnect, modifier = Modifier.widthIn(min = 220.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Default.Bolt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.quick_connect))
            }
        }
    }
}

/**
 * A card swiped towards the start to remove it, with what it does written underneath as it moves.
 * The other direction does nothing, so a sideways drift while scrolling cannot remove anything.
 *
 * Removing takes the card out of the list. If it is still there a moment after being swiped
 * away, because the write failed or another was still running, it slides back rather than staying
 * off its row with its device still saved.
 */
@Composable
private fun SwipeToRemove(busy: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    // Not saved, and not handed to SwipeToDismissBox's own onDismiss: that one fires whenever a
    // swiped-away state is composed, so a card brought back by Undo with its saved state removed
    // itself again.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * 0.4f }) }
    val currentBusy by rememberUpdatedState(busy)
    val currentOnRemove by rememberUpdatedState(onRemove)
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.filter { it == SwipeToDismissBoxValue.EndToStart }.collect {
            currentOnRemove()
            // Removing takes the card out of the list, which ends this. Still here once nothing is
            // being written, the removal did not happen, so the card comes back.
            delay(SWIPE_REMOVE_GRACE_MS)
            snapshotFlow { currentBusy }.first { !it }
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val showing = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large)
                    .background(if (showing) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (showing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(stringResource(R.string.device_list_remove), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !busy,
    ) { content() }
}

/** How long a swiped-away card waits to be removed before it comes back. */
private const val SWIPE_REMOVE_GRACE_MS = 600L

@Composable
private fun NoMatchingDevices(onClear: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Icon(Icons.Outlined.SearchOff, null, Modifier.padding(16.dp).size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.release_no_matches_title),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.device_list_no_matches), style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClear) { Text(stringResource(R.string.release_no_matches_action)) }
    }
}

/** How to pair over Wireless debugging, collapsed until asked for. */
@Composable
private fun WirelessDebuggingCard(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPair: () -> Unit,
    showPairButton: Boolean,
    modifier: Modifier = Modifier,
    onOpenOnboarding: (() -> Unit)? = null,
) {
    Card(modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Wifi, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.wireless_debugging), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.release_setup_subtitle), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.device_list_hide_pairing_help
                            else R.string.device_list_show_pairing_help,
                        ),
                    )
                }
            }
            if (expanded) {
                Text(stringResource(R.string.release_setup_step_one), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.release_setup_step_one_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.release_setup_step_two), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.release_setup_step_two_help), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (showPairButton) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pair_new_device))
                    }
                }
                if (onOpenOnboarding != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenOnboarding, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_open_guide))
                    }
                }
                Text(stringResource(R.string.release_legacy_setup),
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceRow(
    device: SavedDevice,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDeviceSettings: () -> Unit,
    onDuplicate: () -> Unit,
    onCopyAddress: () -> Unit,
    onShareLink: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    favoriteEnabled: Boolean = true,
    removeEnabled: Boolean = true,
    avatarColour: Int = avatarPaletteIndex(device.id),
    onConnectionHistory: () -> Unit = {},
    onCheckConnection: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val connectLabel = stringResource(R.string.device_list_connect, device.name)
    val removeLabel = stringResource(R.string.device_list_remove_named, device.name)
    Card(modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f)
                    .clickable(role = Role.Button, onClickLabel = connectLabel, onClick = onConnect)
                    // Where a screen reader stops for the device: its name and address, merged into
                    // this row. Swiping is not something it can do, so removing is offered here as
                    // an action of its own, beside the one in the menu; on the card, which is never
                    // focused, it was never offered.
                    .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { if (removeEnabled) { onDelete(); true } else false }) }
                    .heightIn(min = 72.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeviceAvatar(device.name, avatarColour, icon = device.avatarIcon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        wrappableAddress(device.address),
                        // Read and searched as written; the zero-width spaces only place the breaks.
                        Modifier.semantics { text = AnnotatedString(device.address) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconToggleButton(
                checked = device.isFavorite,
                onCheckedChange = onFavoriteChange,
                enabled = favoriteEnabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (device.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(
                        if (device.isFavorite) R.string.device_list_remove_favorite
                        else R.string.device_list_add_favorite,
                        device.name,
                    ),
                    tint = if (device.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.device_list_more_actions, device.name),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_settings)) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDeviceSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.check_connection)) },
                        leadingIcon = { Icon(Icons.Default.NetworkCheck, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onCheckConnection()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.connection_history_title)) },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onConnectionHistory()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_tools_duplicate)) },
                        leadingIcon = { Icon(Icons.Default.FileCopy, contentDescription = null) },
                        onClick = { menuExpanded = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_tools_copy_address)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menuExpanded = false; onCopyAddress() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_tools_share_link)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShareLink() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profile_tools_pin)) },
                        leadingIcon = { Icon(Icons.Default.AddToHomeScreen, contentDescription = null) },
                        onClick = { menuExpanded = false; onAddToHomeScreen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.device_list_remove)) },
                        enabled = removeEnabled,
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }

            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Side by side when both fit. With large text in a narrow card, Connect moves under the
            // badge, which beside it was once left too little room to keep even a word whole.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsBadge(
                    stringResource(if (device.mirroringSettings == null)
                        R.string.device_settings_global_summary else R.string.release_custom_profile),
                    actionLabel = stringResource(R.string.device_list_settings_action, device.name),
                    onOpen = onDeviceSettings,
                )
                Spacer(Modifier.weight(1f))
                // The card's one filled button: connecting is what it is for.
                Button(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { contentDescription = connectLabel }) {
                    Text(stringResource(R.string.connect))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            if (device.mirroringSettings != null) Text(deviceSettingsSummary(device.mirroringSettings),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val SavedDeviceDraftSaver = Saver<SavedDevice?, String>(
    save = { SettingsCodec.encodeDevices(listOfNotNull(it)) },
    restore = {
        val decoded = SettingsCodec.decodeDevices(it)
        decoded.value.singleOrNull()?.takeIf { decoded.issues.isEmpty() }
    },
)
