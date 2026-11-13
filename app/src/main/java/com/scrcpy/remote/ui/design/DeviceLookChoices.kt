package com.scrcpy.remote.ui.design

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import com.scrcpy.remote.data.DeviceIcon
import kotlinx.coroutines.flow.first

/** The palette's colours by name, in [AvatarPalette] order. */
private val colorNames = listOf(
    R.string.avatar_color_blue, R.string.avatar_color_indigo, R.string.avatar_color_purple,
    R.string.avatar_color_pink, R.string.avatar_color_orange, R.string.avatar_color_amber,
    R.string.avatar_color_green, R.string.avatar_color_teal,
)

@StringRes
private fun DeviceIcon.label(): Int = when (this) {
    DeviceIcon.PHONE -> R.string.device_icon_phone
    DeviceIcon.TABLET -> R.string.device_icon_tablet
    DeviceIcon.TV -> R.string.device_icon_tv
    DeviceIcon.WATCH -> R.string.device_icon_watch
}

/**
 * A device's look: a palette colour or the automatic one, and its initials or a kind of device.
 *
 * Folded into one row, the avatar as it will be and what it is, until asked for: the editor is a
 * form typed into, and nine colours and five kinds open by default pushed its Save under the
 * keyboard. Each choice is a radio button named for what it is, so a screen reader hears "Pink"
 * rather than an unnamed circle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceLookChoices(
    name: String,
    automaticColor: Int,
    color: Int?,
    icon: DeviceIcon?,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onColorChange: (Int?) -> Unit,
    onIconChange: (DeviceIcon?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = stringResource(
        R.string.device_look_summary,
        stringResource(color?.let { colorNames[it] } ?: R.string.device_look_automatic),
        stringResource(icon?.label() ?: R.string.device_icon_initials),
    )
    val toggleLabel = stringResource(if (expanded) R.string.device_look_hide else R.string.device_look_show)
    val focus = LocalFocusManager.current
    val inputMode = LocalInputModeManager.current
    val section = remember { BringIntoViewRequester() }
    Column(modifier.bringIntoViewRequester(section), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = toggleLabel) {
                    // A look is not typed: a soft keyboard left up would cover the choices being
                    // opened. Only by touch: with a keyboard this row holds the focus, and taking it
                    // away sent a keyboard user back to the form's first field.
                    if (inputMode.inputMode == InputMode.Touch) focus.clearFocus()
                    onExpandedChange(!expanded)
                }
                .testTag("device-look-toggle")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DeviceAvatar(name, color ?: automaticColor, Modifier.testTag("device-look-preview"), icon = icon)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.device_look_heading), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            LaunchedEffect(Unit) {
                // Opened at the foot of a scrolling form, the choices would start below its edge. Only
                // an opening the user just made scrolls; one restored already open leaves the form be.
                if (transition.currentState != EnterExitState.Visible) {
                    snapshotFlow { transition.currentState }.first { it == EnterExitState.Visible }
                    section.bringIntoView()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.device_look_color), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.selectableGroup()) {
                    ColorChoice(stringResource(R.string.device_look_automatic), automaticColor, selected = color == null,
                        automatic = true, enabled = enabled) { onColorChange(null) }
                    for (index in 0 until AvatarPalette.SIZE) {
                        ColorChoice(stringResource(colorNames[index]), index, selected = color == index,
                            automatic = false, enabled = enabled) { onColorChange(index) }
                    }
                }
                Text(stringResource(R.string.device_look_shows), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KindChoice(stringResource(R.string.device_icon_initials), null, selected = icon == null,
                        enabled = enabled) { onIconChange(null) }
                    for (kind in DeviceIcon.entries) {
                        KindChoice(stringResource(kind.label()), kind.vector(), selected = icon == kind,
                            enabled = enabled) { onIconChange(kind) }
                    }
                }
            }
        }
    }
}

/** One colour: a swatch in a 48 dp target, ringed and ticked when chosen. */
@Composable
private fun ColorChoice(
    label: String,
    paletteIndex: Int,
    selected: Boolean,
    automatic: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val (fill, letters) = avatarColors(paletteIndex)
    Box(
        Modifier.size(48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(fill)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = letters)
                // Marks the automatic colour as such, apart from choosing the same one by hand.
                automatic -> Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = letters)
            }
        }
    }
}

/** One kind of avatar: a pill with its picture and name, filled when chosen. */
@Composable
private fun KindChoice(label: String, vector: ImageVector?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            selected -> Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = content)
            vector != null -> Icon(vector, null, Modifier.size(18.dp), tint = content)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = content)
    }
}
