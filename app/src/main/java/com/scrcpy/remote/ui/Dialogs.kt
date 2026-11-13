package com.scrcpy.remote.ui

import com.scrcpy.remote.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.scrcpy.remote.adb.PairingDiscoveryState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.ui.design.DeviceLookChoices
import com.scrcpy.remote.ui.design.InsetDivider
import com.scrcpy.remote.ui.design.InsetGroup
import com.scrcpy.remote.ui.design.InsetSelectRow
import com.scrcpy.remote.ui.design.InsetRow
import com.scrcpy.remote.ui.design.SettingsSheet
import com.scrcpy.remote.ui.design.avatarPaletteIndex
import java.util.UUID
import com.scrcpy.remote.data.StreamingPreset
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsCodec
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.data.VirtualDisplaySpec
import com.scrcpy.remote.scrcpy.AppPackage
import com.scrcpy.remote.scrcpy.EncoderName
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.AudioSource
import com.scrcpy.remote.scrcpy.CaptureOrientation
import com.scrcpy.remote.scrcpy.VideoCodec
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription

@Composable
fun LinkConfirmationDialog(
    device: SavedDevice,
    /** True when this endpoint is already in the device list, so its own settings will apply. */
    saved: Boolean = false,
    /** What the link asks to change for this session, or null when it only names a destination. */
    requests: String? = null,
    /**
     * Saved profiles sharing this address, when there is more than one. Duplicating a profile is
     * how one phone gets, say, work and camera settings, and which of those a link means is the
     * user's call rather than whichever happens to be listed first.
     */
    profiles: List<SavedDevice> = emptyList(),
    selectedProfileId: String? = null,
    onSelectProfile: (String) -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choosing = profiles.size > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.link_confirm_message, device.address),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (choosing) {
                    Text(
                        stringResource(R.string.link_confirm_choose_profile),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Column(Modifier.selectableGroup()) {
                        profiles.forEach { profile ->
                            val selected = profile.id == selectedProfileId
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected, role = Role.RadioButton) { onSelectProfile(profile.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } else if (device.name.isNotBlank() && device.name != device.host) {
                    // The address alone says little about which phone this is. A link may carry a
                    // name, and a link to a device already saved can use that device's own name —
                    // either way it is what makes the choice recognisable rather than a guess.
                    Text(
                        stringResource(
                            if (saved) R.string.link_confirm_saved else R.string.link_confirm_named,
                            device.name,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.link_confirm_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (requests != null) {
                    Text(
                        requests,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !choosing || profiles.any { it.id == selectedProfileId },
            ) { Text(stringResource(R.string.connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DeviceEditorDialog(
    device: SavedDevice?,
    onDismiss: () -> Unit,
    onSave: (SavedDevice) -> Unit,
    duplicate: Boolean = false,
    saving: Boolean = false,
    automaticColor: (id: String) -> Int = ::avatarPaletteIndex,
) {
    // A new device's id is settled now rather than when it is saved, so the automatic colour shown
    // here is the one it will have.
    val draftId = rememberSaveable { device?.id ?: UUID.randomUUID().toString() }
    var name by rememberSaveable { mutableStateOf(device?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(device?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((device?.port ?: 5555).toString()) }
    var avatarColor by rememberSaveable { mutableStateOf(device?.avatarColor) }
    var avatarIcon by rememberSaveable { mutableStateOf(device?.avatarIcon) }
    var lookExpanded by rememberSaveable { mutableStateOf(false) }

    val portValue = port.toIntOrNull()
    val validHost = SettingsValidation.endpointError(host, 5555) == null
    val validPort = portValue != null && portValue in 1..65535
    val nameTooLong = name.ifBlank { host }.length !in 1..100
    val nameSpoofing = com.scrcpy.remote.data.DeviceNameRules.hasInvisibleOrSpoofing(name)
    val validName = !nameTooLong && !nameSpoofing
    val isValid = validHost && validPort && validName

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(
            if (duplicate) R.string.profile_tools_duplicate
            else if (device == null) R.string.dialog_add_device else R.string.dialog_edit_device,
        )) },
        text = {
            Column(
                // Android 8 focused the first field as the dialog opened: highlighted, no keyboard.
                Modifier.noInitialFocusInTouchMode().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (duplicate) Text(stringResource(R.string.profile_tools_duplicate_help),
                    style = MaterialTheme.typography.bodySmall)
                val nameError = !validName && (name.isNotEmpty() || host.isNotEmpty())
                val hostError = host.isNotEmpty() && !validHost
                val portError = port.isNotEmpty() && !validPort
                // A field reserves a line under itself only for something to say there. An empty
                // line kept for an error that was not there spread three fields over most of
                // the screen.
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (!saving) name = it },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.field_name_optional)) },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(if (nameSpoofing) R.string.ui_name_invisible_error else R.string.ui_name_length_error)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { if (!saving) host = it.trim() },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.field_ip_address)) },
                    placeholder = { Text("192.168.1.20") },
                    isError = hostError,
                    supportingText = {
                        Text(stringResource(if (hostError) R.string.ui_host_error else R.string.field_ip_help))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (!saving) port = asciiDigitsOnly(it, 5) },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.field_port)) },
                    isError = portError,
                    supportingText = {
                        Text(stringResource(if (portError) R.string.ui_port_error else R.string.port_help))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                DeviceLookChoices(
                    name = name.ifBlank { host },
                    automaticColor = automaticColor(draftId),
                    color = avatarColor,
                    icon = avatarIcon,
                    enabled = !saving,
                    expanded = lookExpanded,
                    onExpandedChange = { lookExpanded = it },
                    onColorChange = { if (!saving) avatarColor = it },
                    onIconChange = { if (!saving) avatarIcon = it },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = isValid && !saving,
                onClick = {
                    if (!saving) onSave(
                        device?.copy(
                            name = name.ifBlank { host },
                            host = host,
                            port = portValue ?: 5555,
                            avatarColor = avatarColor,
                            avatarIcon = avatarIcon,
                        ) ?: SavedDevice(
                            id = draftId,
                            name = name.ifBlank { host },
                            host = host,
                            port = portValue ?: 5555,
                            avatarColor = avatarColor,
                            avatarIcon = avatarIcon,
                        ),
                    )
                },
            ) { Text(stringResource(if (saving) R.string.settings_saving else R.string.save)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = { if (!saving) onDismiss() }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Keeps only ASCII 0-9, at most [max] of them: the pairing endpoint rejects other Unicode digits. */
internal fun asciiDigitsOnly(value: String, max: Int = Int.MAX_VALUE): String =
    value.filter { it in '0'..'9' }.take(max)

@Composable
fun PairDialog(
    state: PairingState,
    onPair: (host: String, port: Int, code: String) -> Unit,
    onDismiss: () -> Unit,
    discoveryState: PairingDiscoveryState = PairingDiscoveryState.Idle,
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    onPairWithName: ((host: String, port: Int, code: String, suggestedName: String?) -> Unit)? = null,
    onPairWithQr: (() -> Unit)? = null,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var selectedDeviceName by rememberSaveable { mutableStateOf<String?>(null) }
    // Not saveable: a pairing code is a short-lived credential and must not sit in instance state.
    var code by remember { mutableStateOf("") }
    val codeFocusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onStartDiscovery()
        onDispose { onStopDiscovery() }
    }

    val portValue = port.toIntOrNull()
    val validHost = SettingsValidation.endpointError(host, 5555) == null
    val validPort = portValue != null && portValue in 1..65535
    val isValid = validHost && validPort && code.length == 6

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_title)) },
        text = {
            Column(
                Modifier.noInitialFocusInTouchMode().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InformationCard(stringResource(R.string.pair_help))
                if (onPairWithQr != null) {
                    OutlinedButton(
                        enabled = state !is PairingState.Pairing,
                        onClick = onPairWithQr,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pair_with_qr))
                    }
                }
                when (discoveryState) {
                    is PairingDiscoveryState.Searching -> {
                        if (discoveryState.devices.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.pair_discovery_searching),
                                    Modifier.padding(start = 10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            InsetGroup(
                                caption = stringResource(R.string.pair_discovered_devices),
                                footer = stringResource(R.string.pair_discovery_tap_hint),
                            ) {
                                discoveryState.devices.forEachIndexed { index, device ->
                                    if (index > 0) InsetDivider()
                                    InsetSelectRow(
                                        title = device.name,
                                        detail = device.address,
                                        selected = host == device.host && port == device.port.toString(),
                                        enabled = state !is PairingState.Pairing,
                                        onClick = {
                                            host = device.host
                                            port = device.port.toString()
                                            selectedDeviceName = device.name
                                            codeFocusRequester.requestFocus()
                                        },
                                    )
                                }
                            }
                        }
                    }

                    is PairingDiscoveryState.Empty -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (discoveryState.failed) R.string.pair_discovery_unavailable
                                    else R.string.pair_discovery_none_found,
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                enabled = state !is PairingState.Pairing,
                                onClick = onStartDiscovery,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.pair_discovery_retry))
                            }
                        }
                    }

                    PairingDiscoveryState.Idle -> Unit
                }
                val hostError = host.isNotEmpty() && !validHost
                val portError = port.isNotEmpty() && !validPort
                // As in the device editor: a line under a field only when there is an error to show.
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(stringResource(R.string.field_ip_address)) },
                    isError = hostError,
                    supportingText = if (hostError) {
                        { Text(stringResource(R.string.ui_host_error)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = asciiDigitsOnly(it, 5) },
                    label = { Text(stringResource(R.string.field_pairing_port)) },
                    isError = portError,
                    supportingText = if (portError) {
                        { Text(stringResource(R.string.ui_port_error)) }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = asciiDigitsOnly(it, 6) },
                    label = { Text(stringResource(R.string.field_pairing_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocusRequester),
                )

                when (state) {
                    is PairingState.Pairing -> Row(
                        // Announced as it changes: the dialog stays open across the whole attempt,
                        // so without a live region a screen reader user hears nothing when pairing
                        // finishes and cannot tell success from failure.
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.pairing_in_progress), Modifier.padding(start = 12.dp))
                    }

                    is PairingState.Success -> Text(
                        state.message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is PairingState.Failure -> Text(
                        state.message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    PairingState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid && (state is PairingState.Idle || state is PairingState.Failure),
                onClick = {
                    val p = portValue ?: 0
                    val name = selectedDeviceName?.takeIf { host.isNotBlank() }
                    if (onPairWithName != null) onPairWithName(host, p, code, name)
                    else onPair(host, p, code)
                },
            ) { Text(stringResource(R.string.pair)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
fun SettingsDialog(
    settings: MirrorSettings,
    onDismiss: () -> Unit,
    onSave: (base: MirrorSettings, updated: MirrorSettings) -> Unit,
    saving: Boolean = false,
    reconnectLastDevice: Boolean = false,
    onReconnectLastDeviceChange: ((Boolean) -> Unit)? = null,
    onOpenOnboarding: (() -> Unit)? = null,
) {
    val base = rememberSaveable(saver = SettingsDraftSaver) { settings }
    var draft by rememberSaveable(stateSaver = SettingsDraftSaver) { mutableStateOf(settings) }
    var showAppInfo by rememberSaveable { mutableStateOf(false) }
    // Kept above the App info branch, which takes the sheet off the screen: returning from App
    // info, at the very end of the form, finds the form where it was rather than at its top.
    val scroll = rememberScrollState()
    // Refused by the store, which would answer Save with a storage error; the field that is wrong
    // already says why, so Save simply waits for it.
    val draftValid = SettingsValidation.settingsErrors(draft).isEmpty()
    if (showAppInfo) {
        AppInfoDialog(onDismiss = { showAppInfo = false })
        return
    }

    SettingsSheet(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.device_settings_global_defaults),
        saveLabel = stringResource(if (saving) R.string.settings_saving else R.string.save),
        saveEnabled = !saving && draftValid,
        cancelEnabled = !saving,
        onCancel = { if (!saving) onDismiss() },
        onSave = { if (!saving && draftValid) onSave(base, draft) },
        bodyTag = "main-settings-body",
        scrollState = scroll,
    ) {
        MirroringSettingsControls(
            settings = draft,
            onSettingsChange = { if (!saving) draft = it },
            enabled = !saving,
        ) {
            Text(stringResource(R.string.setting_touch_heading), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.setting_touch_help),
                style = MaterialTheme.typography.bodySmall,
            )

            OffsetRow(
                label = stringResource(R.string.setting_vertical_offset),
                value = draft.touchOffsetYDp,
                enabled = !saving,
            ) { if (!saving) draft = draft.copy(touchOffsetYDp = it) }

            OffsetRow(
                label = stringResource(R.string.setting_horizontal_offset),
                value = draft.touchOffsetXDp,
                enabled = !saving,
            ) { if (!saving) draft = draft.copy(touchOffsetXDp = it) }
        }
        if (onReconnectLastDeviceChange != null) {
            Spacer(Modifier.height(16.dp))
            InsetGroup(
                caption = stringResource(R.string.settings_startup_group),
                footer = stringResource(R.string.setting_reconnect_last_device_help),
            ) {
                SwitchRow(
                    label = stringResource(R.string.setting_reconnect_last_device),
                    checked = reconnectLastDevice,
                    enabled = !saving,
                    onCheckedChange = onReconnectLastDeviceChange,
                )
            }
        }
        // About the app rather than about mirroring, so after everything else, where settings
        // screens keep it.
        Spacer(Modifier.height(16.dp))
        InsetGroup(caption = stringResource(R.string.settings_about)) {
            InsetRow(
                title = stringResource(R.string.app_info_title),
                detail = stringResource(R.string.app_info_summary),
                icon = Icons.Outlined.Info,
                showChevron = true,
                enabled = !saving,
                onClick = { showAppInfo = true },
            )
            if (onOpenOnboarding != null) {
                InsetDivider()
                InsetRow(
                    title = stringResource(R.string.onboarding_open_guide),
                    detail = stringResource(R.string.onboarding_open_guide_summary),
                    icon = Icons.Outlined.PhoneAndroid,
                    showChevron = true,
                    enabled = !saving,
                    onClick = onOpenOnboarding,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MirroringSettingsControls(
    settings: MirrorSettings,
    onSettingsChange: (MirrorSettings) -> Unit,
    supportedVideoCodecs: List<VideoCodec> = remember { VideoCodec.supported() },
    supportedAudioCodecs: List<AudioCodec> = remember { AudioCodec.supported() },
    enabled: Boolean = true,
    calibrationControls: @Composable () -> Unit = {},
) {
    val updateSettings: (MirrorSettings) -> Unit = { if (enabled) onSettingsChange(it) }
    SectionHeading(stringResource(R.string.release_video_heading), Icons.Outlined.Tune,
        stringResource(R.string.release_video_help))

    // Offered before the individual sliders because this is the control someone reaches for when
    // the mirror feels behind their finger, and picking a smaller picture helps far more than any
    // single slider they would otherwise guess at.
    val activePreset = StreamingPreset.of(settings)
    SelectGroup(
        caption = stringResource(R.string.setting_preset),
        options = StreamingPreset.entries,
        selected = activePreset,
        enabled = enabled,
        onSelect = { updateSettings(it.applyTo(settings)) },
        footer = stringResource(
            if (activePreset == null) R.string.setting_preset_custom else R.string.setting_preset_help),
        label = { stringResource(it.labelRes) },
    )
    Spacer(Modifier.height(18.dp))

    val resolutionLabel = stringResource(R.string.setting_max_resolution, settings.maxSize)
    Text(resolutionLabel, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = settings.maxSize.toFloat(),
        onValueChange = { updateSettings(settings.copy(maxSize = (it / 160).roundToInt() * 160)) },
        enabled = enabled,
        valueRange = 640f..2560f,
        modifier = Modifier.semantics { contentDescription = resolutionLabel },
    )

    val bitrateLabel = stringResource(R.string.setting_bit_rate, settings.videoBitRateMbps)
    Text(bitrateLabel, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = settings.videoBitRateMbps.toFloat(),
        onValueChange = { updateSettings(settings.copy(videoBitRateMbps = it.roundToInt())) },
        enabled = enabled,
        valueRange = 1f..30f,
        modifier = Modifier.semantics { contentDescription = bitrateLabel },
    )

    val fpsLabel = stringResource(R.string.setting_max_fps, settings.maxFps)
    Text(fpsLabel, style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = settings.maxFps.toFloat(),
        onValueChange = { updateSettings(settings.copy(maxFps = it.roundToInt())) },
        enabled = enabled,
        valueRange = 15f..120f,
        modifier = Modifier.semantics { contentDescription = fpsLabel },
    )

    Spacer(Modifier.height(18.dp))
    SelectGroup(
        caption = stringResource(R.string.setting_video_codec),
        options = VideoCodec.entries.filter { it in supportedVideoCodecs || it == settings.videoCodec },
        selected = settings.videoCodec,
        enabled = enabled,
        // Changing the codec invalidates any encoder chosen for the old one: the device rejects a
        // mismatched pair outright, which would surface only as a connection that no longer works.
        onSelect = {
            if (it != settings.videoCodec) updateSettings(settings.copy(videoCodec = it, videoEncoder = null))
        },
        label = { it.label },
    )
    if (settings.videoCodec !in supportedVideoCodecs) {
        Text(
            stringResource(R.string.device_settings_unsupported_codec, settings.videoCodec.label),
            Modifier.padding(start = 14.dp, top = 7.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(18.dp))
    SelectGroup(
        caption = stringResource(R.string.setting_capture_orientation),
        options = CaptureOrientation.entries,
        selected = settings.captureOrientation,
        enabled = enabled,
        onSelect = { updateSettings(settings.copy(captureOrientation = it)) },
        footer = stringResource(R.string.setting_capture_orientation_help),
        label = { stringResource(it.labelRes) },
    )
    Spacer(Modifier.height(18.dp))

    // Editable here, not only from the in-session picker: an encoder that stops the session from
    // starting has to be clearable without a session, which is exactly when it matters.
    var encoderShown by rememberSaveable { mutableStateOf(settings.videoEncoder != null) }
    if (encoderShown) {
        val encoder = settings.videoEncoder.orEmpty()
        val usable = encoder.isEmpty() || EncoderName.isUsable(encoder)
        OutlinedTextField(
            value = encoder,
            onValueChange = { updateSettings(settings.copy(videoEncoder = it.trim().ifEmpty { null })) },
            enabled = enabled,
            label = { Text(stringResource(R.string.setting_video_encoder)) },
            isError = !usable,
            supportingText = {
                Text(
                    if (usable) stringResource(R.string.setting_video_encoder_help)
                    else stringResource(R.string.setting_video_encoder_error)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                encoderShown = false
                updateSettings(settings.copy(videoEncoder = null))
            },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.setting_video_encoder_clear)) }
    }

    SwitchRow(
        stringResource(R.string.setting_virtual_display),
        settings.virtualDisplay != null,
        enabled,
    ) { on ->
        updateSettings(settings.copy(virtualDisplay = if (on) "" else null))
    }
    Text(stringResource(R.string.setting_virtual_display_help), style = MaterialTheme.typography.bodySmall)
    settings.virtualDisplay?.let { spec ->
        val problem = VirtualDisplaySpec.error(spec)
        OutlinedTextField(
            value = spec,
            onValueChange = { updateSettings(settings.copy(virtualDisplay = it)) },
            enabled = enabled,
            label = { Text(stringResource(R.string.setting_virtual_display_size)) },
            placeholder = { Text(stringResource(R.string.setting_virtual_display_size_hint)) },
            isError = problem != null,
            supportingText = {
                Text(problem ?: stringResource(R.string.setting_virtual_display_size_help))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SwitchRow(
        stringResource(R.string.setting_start_app),
        settings.startApp != null,
        enabled,
    ) { on ->
        updateSettings(settings.copy(startApp = if (on) "" else null))
    }
    Text(stringResource(R.string.setting_start_app_help), style = MaterialTheme.typography.bodySmall)
    settings.startApp?.let { app ->
        // Empty is not "nothing chosen yet": the switch being on means a package is required,
        // and saving one that is empty is refused. Treating it as acceptable here left Save
        // failing with the field still showing its ordinary help.
        val usable = AppPackage.isSendable(app)
        OutlinedTextField(
            value = app,
            onValueChange = { updateSettings(settings.copy(startApp = it.trim())) },
            enabled = enabled,
            label = { Text(stringResource(R.string.setting_start_app_field)) },
            placeholder = { Text(stringResource(R.string.setting_start_app_hint)) },
            isError = !usable,
            supportingText = {
                Text(
                    if (usable) stringResource(R.string.setting_start_app_field_help)
                    else stringResource(R.string.setting_start_app_hint_error)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionHeading(stringResource(R.string.release_audio_heading), Icons.AutoMirrored.Outlined.VolumeUp)
    SwitchRow(stringResource(R.string.setting_forward_audio), settings.audioEnabled, enabled) {
        updateSettings(settings.copy(audioEnabled = it))
    }
    if (settings.audioEnabled) {
        Spacer(Modifier.height(18.dp))
        SelectGroup(
            caption = stringResource(R.string.ui_setting_audio_codec),
            options = AudioCodec.entries.filter { it in supportedAudioCodecs || it == settings.audioCodec },
            selected = settings.audioCodec,
            enabled = enabled,
            // Clears the encoder with the codec, exactly as the video control does: an encoder
            // only works for the codec it was listed under, and the server refuses the mismatched
            // pair with "Incorrect encoder type", which reaches the user only as audio that never
            // starts.
            onSelect = {
                if (it != settings.audioCodec) updateSettings(settings.copy(audioCodec = it, audioEncoder = null))
            },
            label = { it.label },
        )
        // Recording takes the session's sound only in AAC; said here, where the codec is chosen,
        // rather than only when Record asks to reconnect.
        Text(
            stringResource(R.string.audio_codec_recording_hint),
            Modifier.padding(start = 14.dp, top = 7.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (settings.audioCodec !in supportedAudioCodecs) {
            Text(
                stringResource(R.string.device_settings_unsupported_codec, settings.audioCodec.label),
                Modifier.padding(start = 14.dp, top = 7.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(18.dp))
        val audioSourceDetails = AudioSource.entries.mapNotNull { it.detailRes }.distinct()
            .associateWith { stringResource(it) }
        SelectGroup(
            caption = stringResource(R.string.setting_audio_source),
            options = AudioSource.entries,
            selected = settings.audioSource,
            enabled = enabled,
            onSelect = { updateSettings(settings.copy(audioSource = it)) },
            footer = stringResource(R.string.setting_audio_source_help),
            detail = { source -> source.detailRes?.let { audioSourceDetails[it] } },
            label = { stringResource(it.labelRes) },
        )
        Spacer(Modifier.height(18.dp))
        if (settings.audioSource == AudioSource.PLAYBACK) {
            SwitchRow(stringResource(R.string.setting_audio_dup), settings.audioDup, enabled) {
                updateSettings(settings.copy(audioDup = it))
            }
        }
        SwitchRow(stringResource(R.string.setting_background_audio), settings.backgroundAudio, enabled) {
            updateSettings(settings.copy(backgroundAudio = it))
        }
        Text(
            stringResource(R.string.setting_background_audio_help),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        val audioBitRateLabel = stringResource(R.string.setting_audio_bit_rate, settings.audioBitRateKbps)
        Text(audioBitRateLabel, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.audioBitRateKbps.toFloat(),
            onValueChange = { updateSettings(settings.copy(audioBitRateKbps = it.roundToInt())) },
            enabled = enabled,
            valueRange = SettingsValidation.AUDIO_BIT_RATE_RANGE.first.toFloat()..
                SettingsValidation.AUDIO_BIT_RATE_RANGE.last.toFloat(),
            modifier = Modifier.semantics { contentDescription = audioBitRateLabel },
        )
        Text(
            stringResource(R.string.setting_audio_bit_rate_help),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    PlaybackSettingsControls(settings, updateSettings, enabled)

    SectionHeading(stringResource(R.string.release_device_heading), Icons.Outlined.PhoneAndroid)
    SwitchRow(stringResource(R.string.setting_keep_awake), settings.stayAwake, enabled) {
        updateSettings(settings.copy(stayAwake = it))
    }
    SwitchRow(stringResource(R.string.setting_keep_active), settings.keepActive, enabled) {
        updateSettings(settings.copy(keepActive = it))
    }
    Text(
        stringResource(R.string.setting_keep_active_help),
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow(stringResource(R.string.setting_show_touches), settings.showTouches, enabled) {
        updateSettings(settings.copy(showTouches = it))
    }
    SwitchRow(stringResource(R.string.setting_turn_screen_off), settings.turnScreenOff, enabled) {
        updateSettings(settings.copy(turnScreenOff = it))
    }
    SwitchRow(stringResource(R.string.setting_power_off), settings.powerOffOnClose, enabled) {
        updateSettings(settings.copy(powerOffOnClose = it))
    }

    SectionHeading(stringResource(R.string.release_input_heading), Icons.Outlined.TouchApp)
    calibrationControls()
    SwitchRow(stringResource(R.string.setting_clipboard_autosync), settings.clipboardAutosync, enabled) {
        updateSettings(settings.copy(clipboardAutosync = it))
    }
    Text(
        stringResource(R.string.setting_clipboard_autosync_help),
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow(stringResource(R.string.setting_edge_gestures), settings.captureEdgeGestures, enabled) {
        updateSettings(settings.copy(captureEdgeGestures = it))
    }
    Text(
        stringResource(R.string.setting_edge_gestures_help),
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsControls(
    settings: MirrorSettings,
    onSettingsChange: (MirrorSettings) -> Unit,
    enabled: Boolean,
) {
    SectionHeading(stringResource(R.string.setting_playback_sync), Icons.Outlined.AvTimer)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaybackSyncMode.entries.forEach { mode ->
            FilterChip(
                selected = settings.syncMode == mode,
                onClick = { onSettingsChange(settings.copy(syncMode = mode)) },
                enabled = enabled,
                label = {
                    Text(stringResource(
                        if (mode == PlaybackSyncMode.LOW_LATENCY) R.string.setting_sync_low_latency
                        else R.string.setting_sync_synchronized,
                    ))
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    Text(stringResource(R.string.setting_sync_help), style = MaterialTheme.typography.bodySmall)

    if (settings.syncMode == PlaybackSyncMode.SYNCHRONIZED) {
        val offsetLabel = stringResource(R.string.setting_sync_offset_value, settings.syncOffsetMs)
        Text(offsetLabel, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.setting_sync_offset_help), style = MaterialTheme.typography.bodySmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(
                enabled = enabled && settings.syncOffsetMs > SettingsValidation.SYNC_OFFSET_RANGE.first,
                onClick = { onSettingsChange(settings.copy(syncOffsetMs = settings.syncOffsetMs - 1)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.setting_sync_offset_decrease))
            }
            IconButton(
                enabled = enabled && settings.syncOffsetMs < SettingsValidation.SYNC_OFFSET_RANGE.last,
                onClick = { onSettingsChange(settings.copy(syncOffsetMs = settings.syncOffsetMs + 1)) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.setting_sync_offset_increase))
            }
            TextButton(
                enabled = enabled && settings.syncOffsetMs != 0,
                onClick = { onSettingsChange(settings.copy(syncOffsetMs = 0)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.setting_sync_offset_reset)) }
        }
        Slider(
            value = settings.syncOffsetMs.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(syncOffsetMs = it.roundToInt())) },
            enabled = enabled,
            valueRange = SettingsValidation.SYNC_OFFSET_RANGE.first.toFloat()..SettingsValidation.SYNC_OFFSET_RANGE.last.toFloat(),
            modifier = Modifier.semantics { contentDescription = offsetLabel },
        )
    }

    Text(stringResource(R.string.setting_audio_buffer), style = MaterialTheme.typography.bodyMedium)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = settings.audioBufferMs == 0,
            onClick = { onSettingsChange(settings.copy(audioBufferMs = 0)) },
            enabled = enabled,
            label = { Text(stringResource(R.string.setting_audio_buffer_automatic)) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        for (bufferMs in listOf(40, 80, 150)) {
            FilterChip(
                selected = settings.audioBufferMs == bufferMs,
                onClick = { onSettingsChange(settings.copy(audioBufferMs = bufferMs)) },
                enabled = enabled,
                label = { Text(stringResource(R.string.setting_audio_buffer_preset, bufferMs)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    Text(stringResource(R.string.setting_audio_buffer_help), style = MaterialTheme.typography.bodySmall)
    if (settings.audioBufferMs != 0) {
        val bufferLabel = stringResource(R.string.setting_audio_buffer_value, settings.audioBufferMs)
        Text(bufferLabel, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = settings.audioBufferMs.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(audioBufferMs = it.roundToInt())) },
            enabled = enabled,
            valueRange = SettingsValidation.AUDIO_BUFFER_RANGE.first.toFloat()..SettingsValidation.AUDIO_BUFFER_RANGE.last.toFloat(),
            modifier = Modifier.semantics { contentDescription = bufferLabel },
        )
    }

    SectionHeading(stringResource(R.string.release_connection_heading), Icons.Outlined.Wifi)
    SwitchRow(stringResource(R.string.setting_auto_reconnect), settings.autoReconnect, enabled) {
        onSettingsChange(settings.copy(autoReconnect = it))
    }
    Text(stringResource(R.string.setting_auto_reconnect_help), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.setting_playback_apply_help), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 16.dp),
            style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/**
 * One choice among several, rendered as a grouped inset list.
 *
 * Replaces a row of filter chips. Chips are fine for three options and poor for five: they wrap
 * unpredictably, and they leave nowhere to say what an option actually does. Each row here keeps
 * the `selected` semantics a chip reported, so nothing reading the selection has to change.
 */
@Composable
private fun <T> SelectGroup(
    caption: String,
    options: List<T>,
    selected: T?,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    footer: String? = null,
    detail: (T) -> String? = { null },
    label: @Composable (T) -> String,
) {
    InsetGroup(caption = caption, footer = footer) {
        options.forEachIndexed { index, option ->
            if (index > 0) InsetDivider()
            InsetSelectRow(
                title = label(option),
                detail = detail(option),
                selected = selected == option,
                enabled = enabled,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Calibration row: a coarse slider plus arrows for exact single-dp adjustments. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OffsetRow(label: String, value: Int, enabled: Boolean = true, onValueChange: (Int) -> Unit) {
    val offsetDescription = stringResource(R.string.setting_offset_state, value)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.setting_offset_value, label, value),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(-TOUCH_OFFSET_LIMIT_DP)) },
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.control_minus_one, label))
            }
            IconButton(
                onClick = { onValueChange((value + 1).coerceAtMost(TOUCH_OFFSET_LIMIT_DP)) },
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.control_plus_one, label))
            }
        }
    }
    Slider(
        value = value.toFloat().coerceIn(-TOUCH_OFFSET_LIMIT_DP.toFloat(), TOUCH_OFFSET_LIMIT_DP.toFloat()),
        onValueChange = { onValueChange(it.roundToInt()) },
        enabled = enabled,
        valueRange = -TOUCH_OFFSET_LIMIT_DP.toFloat()..TOUCH_OFFSET_LIMIT_DP.toFloat(),
        // The label above is a separate semantic node, so without this the slider announces only
        // that it is adjustable, with nothing to say which axis it moves.
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = offsetDescription
        },
    )
}

@Composable
internal fun PairQrDialog(
    state: QrPairingState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pair_qr_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InformationCard(stringResource(R.string.pair_qr_help))
                when (state) {
                    is QrPairingState.WaitingForScan -> {
                        val qrBitmap = remember(state.payload) { renderQrCodeBitmap(state.payload) }
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Color.White,
                        ) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = stringResource(R.string.pair_qr_content_description),
                                modifier = Modifier.size(220.dp).padding(12.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.pair_qr_expires_in, state.remainingSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }

                    is QrPairingState.Pairing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.pairing_in_progress))
                        }
                    }

                    is QrPairingState.Failed -> {
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                        Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.pair_qr_try_again))
                        }
                    }

                    QrPairingState.Idle -> Unit
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun PostPairingSearchingDialog(
    host: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.post_pair_searching_title)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.post_pair_searching_body, host), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun PostPairingConfirmDialog(
    device: SavedDevice,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.post_pair_confirm_title, device.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.post_pair_confirm_body, device.name, device.address),
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = onConfirm) {
                Text(stringResource(R.string.post_pair_confirm_button))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickConnectDialog(
    onConnect: (host: String, port: Int, linkOptions: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var portText by rememberSaveable { mutableStateOf(QuickConnectConfig.DEFAULT_PORT.toString()) }
    var preset by rememberSaveable { mutableStateOf(QuickConnectConfig.DEFAULT_PRESET) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var audioEnabled by rememberSaveable { mutableStateOf(true) }
    var turnScreenOff by rememberSaveable { mutableStateOf(false) }
    var stayAwake by rememberSaveable { mutableStateOf(false) }

    val trimmedHost = host.trim()
    val parsedPort = portText.trim().toIntOrNull()
    val config = parsedPort?.let {
        QuickConnectConfig(
            host = trimmedHost,
            port = it,
            preset = preset,
            audioEnabled = audioEnabled,
            turnScreenOff = turnScreenOff,
            stayAwake = stayAwake,
        )
    }
    val valid = config != null && config.validate() == null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
        title = { Text(stringResource(R.string.quick_connect_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.quick_connect_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.field_ip_address)) },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("quick-connect-host"),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = asciiDigitsOnly(it, max = 65535) },
                    label = { Text(stringResource(R.string.field_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("quick-connect-port"),
                )
                Text(
                    stringResource(R.string.setting_preset),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickConnectConfig.PRESET_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = preset == option,
                            onClick = { preset = option },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .heightIn(min = 48.dp)
                        .testTag("quick-connect-advanced-toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.quick_connect_advanced),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Icon(
                        if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                }
                if (advancedExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("quick-connect-advanced-panel"),
                    ) {
                        SwitchRow(
                            label = stringResource(R.string.setting_forward_audio),
                            checked = audioEnabled,
                            onCheckedChange = { audioEnabled = it },
                        )
                        SwitchRow(
                            label = stringResource(R.string.setting_turn_screen_off),
                            checked = turnScreenOff,
                            onCheckedChange = { turnScreenOff = it },
                        )
                        SwitchRow(
                            label = stringResource(R.string.setting_keep_awake),
                            checked = stayAwake,
                            onCheckedChange = { stayAwake = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    if (config != null && config.validate() == null) {
                        onConnect(trimmedHost, config.port, config.toLinkOptions())
                    }
                },
            ) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun OnboardingDialog(
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(0) }
    val steps = listOf(
        Triple(
            Icons.Outlined.PhoneAndroid,
            R.string.onboarding_step1_title,
            R.string.onboarding_step1_body,
        ),
        Triple(
            Icons.Outlined.Wifi,
            R.string.onboarding_step2_title,
            R.string.onboarding_step2_body,
        ),
        Triple(
            Icons.Default.QrCode2,
            R.string.onboarding_step3_title,
            R.string.onboarding_step3_body,
        ),
    )
    val (icon, titleRes, bodyRes) = steps[step]

    AlertDialog(
        onDismissRequest = onFinish,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp).size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    stringResource(R.string.onboarding_step_counter, step + 1, steps.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step < steps.lastIndex) step++ else onFinish()
                },
            ) {
                Text(
                    stringResource(
                        if (step < steps.lastIndex) R.string.onboarding_next else R.string.onboarding_done,
                    ),
                )
            }
        },
        dismissButton = {
            if (step > 0) {
                TextButton(onClick = { step-- }) {
                    Text(stringResource(R.string.onboarding_back))
                }
            } else {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        },
    )
}

private val SettingsDraftSaver = Saver<MirrorSettings, String>(
    // A draft is allowed to be half-typed, so this must not depend on it being valid. Validation
    // gates Save; all this has to do is survive saved instance state.
    save = { SettingsCodec.encodeDraft(it) },
    restore = { SettingsCodec.decodeDraft(it) },
)
