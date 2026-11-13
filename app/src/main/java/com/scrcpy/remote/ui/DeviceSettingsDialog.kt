package com.scrcpy.remote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceMirroringSettings
import com.scrcpy.remote.data.MirrorSettings
import com.scrcpy.remote.data.PlaybackSyncMode
import com.scrcpy.remote.data.SavedDevice
import com.scrcpy.remote.data.SettingsValidation
import com.scrcpy.remote.ui.design.SettingsSheet
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun DeviceSettingsDialog(
    device: SavedDevice,
    defaults: MirrorSettings,
    onDismiss: () -> Unit,
    onSave: (baseline: DeviceMirroringSettings?, edited: DeviceMirroringSettings?) -> Unit,
    sessionActive: Boolean = false,
    saving: Boolean = false,
) {
    var draft by rememberSaveable(device.id, stateSaver = DeviceSettingsDraftSaver) {
        mutableStateOf(
            DeviceSettingsDraft(
                baseline = device.mirroringSettings,
                custom = device.mirroringSettings,
                useGlobal = device.mirroringSettings == null,
            ),
        )
    }
    // Validated as the store does, so Save waits for the field that is wrong rather than ending
    // in a storage error; inheriting the global settings is always storable.
    val draftValid = draft.useGlobal ||
        draft.custom?.let { SettingsValidation.settingsErrors(it.applyTo(MirrorSettings())).isEmpty() } != false

    SettingsSheet(
        title = stringResource(R.string.device_settings_title, device.name),
        saveLabel = stringResource(if (saving) R.string.settings_saving else R.string.save),
        saveEnabled = !saving && draftValid,
        cancelEnabled = !saving,
        onCancel = { if (!saving) onDismiss() },
        onSave = { if (!saving && draftValid) onSave(draft.baseline, if (draft.useGlobal) null else draft.custom) },
        bodyTag = "device-settings-body",
    ) {
        SwitchRow(stringResource(R.string.device_settings_use_global), draft.useGlobal, enabled = !saving) { useGlobal ->
            if (!saving) draft = draft.copy(
                useGlobal = useGlobal,
                custom = draft.custom ?: if (!useGlobal) DeviceMirroringSettings.from(defaults) else null,
            )
        }
        Text(
            stringResource(
                if (draft.useGlobal) R.string.device_settings_global_help
                else R.string.device_settings_custom_help,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (sessionActive) {
            InformationCard(stringResource(R.string.device_settings_reconnect_help))
        }
        Text(
            stringResource(R.string.device_settings_calibration_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (draft.useGlobal) {
            val inherited = DeviceMirroringSettings.from(defaults)
            Text(
                deviceSettingsSummary(inherited),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(playbackSettingsSummary(inherited), style = MaterialTheme.typography.bodySmall)
            if (inherited.syncMode == PlaybackSyncMode.SYNCHRONIZED) {
                Text(
                    stringResource(R.string.setting_sync_offset_value, inherited.syncOffsetMs),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            MirroringSettingsControls(
                settings = checkNotNull(draft.custom).applyTo(defaults),
                onSettingsChange = { if (!saving) draft = draft.copy(custom = DeviceMirroringSettings.from(it)) },
                enabled = !saving,
            )
        }
    }
}

@Composable
internal fun deviceSettingsSummary(settings: DeviceMirroringSettings?): String =
    if (settings == null) {
        stringResource(R.string.device_settings_global_summary)
    } else {
        stringResource(
            R.string.device_settings_custom_summary,
            settings.maxSize,
            settings.maxFps,
            stringResource(
                if (settings.audioEnabled) R.string.device_settings_audio_on else R.string.device_settings_audio_off,
            ),
        )
    }

@Composable
private fun playbackSettingsSummary(settings: DeviceMirroringSettings): String = stringResource(
    R.string.setting_playback_summary,
    stringResource(
        if (settings.syncMode == PlaybackSyncMode.LOW_LATENCY) R.string.setting_sync_low_latency
        else R.string.setting_sync_synchronized,
    ),
    if (settings.audioBufferMs == 0) stringResource(R.string.setting_audio_buffer_automatic_summary)
    else stringResource(R.string.setting_audio_buffer_value, settings.audioBufferMs),
    stringResource(
        if (settings.autoReconnect) R.string.setting_auto_reconnect_on
        else R.string.setting_auto_reconnect_off,
    ),
)

@Serializable
private data class DeviceSettingsDraft(
    val baseline: DeviceMirroringSettings?,
    val custom: DeviceMirroringSettings?,
    val useGlobal: Boolean,
)

private val DeviceSettingsDraftSaver = Saver<DeviceSettingsDraft, String>(
    save = { Json.encodeToString(it) },
    restore = { raw ->
        try {
            // Structure only. A draft is allowed to be half-typed, and rejecting the whole thing
            // because one field is unfinished threw away every other edit the user had made; the
            // editor shows the error and Save stays gated.
            Json.decodeFromString<DeviceSettingsDraft>(raw)
                .takeIf { draft -> draft.useGlobal || draft.custom != null }
        } catch (_: SerializationException) {
            // Discard only an invalid transient draft, never the persisted device profile.
            null
        }
    },
)
