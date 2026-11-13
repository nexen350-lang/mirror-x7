package com.scrcpy.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.scrcpy.DeviceEncoder
import com.scrcpy.remote.scrcpy.AudioCodec
import com.scrcpy.remote.scrcpy.VideoCodec
import com.scrcpy.remote.session.DeviceEncodersState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics

/** Human wording for the device's own `hw`/`sw`/`hybrid` classification. */
@Composable
internal fun accelerationLabel(acceleration: String?): String? = when (acceleration) {
    "hw" -> stringResource(R.string.encoders_hardware)
    "sw" -> stringResource(R.string.encoders_software)
    "hybrid" -> stringResource(R.string.encoders_hybrid)
    else -> null
}

/**
 * Lets the user pick which of the device's video encoders a session should use.
 *
 * Offered in-session because only a connected device can say which encoders it has, and the
 * names are opaque strings nobody would type from memory.
 */
@Composable
internal fun DeviceEncodersDialog(
    state: DeviceEncodersState,
    selected: String?,
    /** The codec the session is configured for; only its encoders can be used. */
    codec: VideoCodec,
    onSelect: (String?) -> Unit,
    createsProfile: Boolean,
    /** No saved device, so the choice applies to this session and is not kept. */
    sessionOnly: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    /** Switches to the audio list; null hides the switch. */
    onSwitch: (() -> Unit)? = null,
) = EncodersDialog(
    state = state,
    selected = selected,
    codecLabel = codec.label,
    codecServerValue = codec.serverValue,
    title = stringResource(R.string.encoders_title),
    helpText = stringResource(R.string.encoders_help_for_codec, codec.label),
    switchLabel = stringResource(R.string.audio_encoders_title),
    onSwitch = onSwitch,
    onSelect = onSelect,
    createsProfile = createsProfile,
    sessionOnly = sessionOnly,
    onRetry = onRetry,
    onDismiss = onDismiss,
)

/**
 * The same choice for audio.
 *
 * Kept as its own entry point rather than a mode flag: the two lists are loaded separately and
 * remembered in different settings, so anything that conflated them would let one overwrite the
 * other's choice.
 */
@Composable
internal fun DeviceAudioEncodersDialog(
    state: DeviceEncodersState,
    selected: String?,
    codec: AudioCodec,
    onSelect: (String?) -> Unit,
    createsProfile: Boolean,
    sessionOnly: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    /** Switches back to the video list; null hides the switch. */
    onSwitch: (() -> Unit)? = null,
) = EncodersDialog(
    state = state,
    selected = selected,
    codecLabel = codec.label,
    codecServerValue = codec.serverValue,
    title = stringResource(R.string.audio_encoders_title),
    helpText = stringResource(R.string.audio_encoders_help_for_codec, codec.label),
    switchLabel = stringResource(R.string.encoders_title),
    onSwitch = onSwitch,
    onSelect = onSelect,
    createsProfile = createsProfile,
    sessionOnly = sessionOnly,
    onRetry = onRetry,
    onDismiss = onDismiss,
)

@Composable
private fun EncodersDialog(
    state: DeviceEncodersState,
    selected: String?,
    codecLabel: String,
    codecServerValue: String,
    title: String,
    helpText: String,
    switchLabel: String,
    onSwitch: (() -> Unit)?,
    onSelect: (String?) -> Unit,
    createsProfile: Boolean,
    sessionOnly: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The server refuses an encoder whose type does not match the configured codec
    // ("Incorrect encoder type"), which shows up only as a failed connection. Offering just the
    // matching ones makes that combination unreachable rather than merely discouraged.
    val usable = remember(state.encoders, codecServerValue) {
        state.encoders.filter { it.codec.equals(codecServerValue, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // An encoder name only means anything on the device that reported it, so the
            // choice is per device — which for a device still inheriting the defaults means
            // giving it settings of its own, and for one nobody saved means it lasts only as
            // long as this session. Said before the choice, not discovered after.
            val note = when {
                sessionOnly -> stringResource(R.string.encoders_session_only)
                createsProfile -> stringResource(R.string.encoders_creates_profile)
                else -> null
            }
            // Keep help and choices in one viewport so large text cannot crowd out the list.
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).then(
                    if (!state.loading && state.error == null) Modifier.testTag("device-encoder-list")
                    else Modifier,
                ),
            ) {
                item {
                    Text(
                        helpText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (note != null) {
                    item {
                        Text(
                            note,
                            Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A refresh that failed keeps the list it had, with the reason above it.
                if (state.loaded && state.error != null) item {
                    Text(state.error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                when {
                    state.loading -> item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.heightIn(min = 24.dp))
                            Text(stringResource(R.string.encoders_loading),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    state.error != null && !state.loaded -> item {
                        Column(
                            Modifier.padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(state.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.apps_retry))
                            }
                        }
                    }

                    else -> {
                        // Always offered, and always first: it is the setting that works
                        // everywhere, and the way back if a chosen encoder turns out worse.
                        item {
                            EncoderRow(
                                title = stringResource(R.string.encoders_default),
                                subtitle = stringResource(R.string.encoders_default_help),
                                selected = selected == null,
                            ) { onSelect(null) }
                            HorizontalDivider()
                        }
                        if (usable.isEmpty() && state.loaded) {
                            item {
                                Text(stringResource(R.string.encoders_empty_for_codec, codecLabel),
                                    Modifier.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        // A saved encoder the device no longer lists still needs a row, or nothing
                        // shows as selected while Save keeps it.
                        if (selected != null && usable.none { it.name == selected }) {
                            item {
                                EncoderRow(
                                    title = selected,
                                    subtitle = stringResource(R.string.encoders_current_unlisted),
                                    selected = true,
                                ) { onSelect(selected) }
                                HorizontalDivider()
                            }
                        }
                        itemsIndexed(
                            usable,
                            key = { index, encoder -> "$index-${encoder.name}" },
                        ) { _, encoder ->
                            EncoderRow(
                                title = encoder.name,
                                subtitle = subtitleFor(encoder),
                                selected = selected == encoder.name,
                            ) { onSelect(encoder.name) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.done))
            }
        },
        // The other list reached from here rather than from its own panel button: the tools row
        // is already full on a small screen, and one more button there pushed the controls above
        // it out of reach.
        dismissButton = onSwitch?.let {
            {
                TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(switchLabel)
                }
            }
        },
    )
}

@Composable
private fun subtitleFor(encoder: DeviceEncoder): String {
    val acceleration = accelerationLabel(encoder.acceleration)
    return if (acceleration == null) encoder.codec
    else stringResource(R.string.encoders_row, encoder.codec, acceleration)
}

@Composable
private fun EncoderRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedLabel = stringResource(R.string.encoders_selected)
    Row(
        // One actionable node for the whole row, carrying the selected state itself. Previously
        // "in use" lived on a sibling check icon, so a screen reader announced the encoder in use
        // exactly like every other one unless the user happened to traverse past that icon.
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { if (selected) stateDescription = selectedLabel }
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Ordinary text, not the accent colour: every row was previously drawn in it, which
            // read as though all of them were selected and left the checkmark doing all the work.
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
