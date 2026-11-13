package com.scrcpy.remote.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Grouped inset list components for settings and detail screens.
 *
 * Interactive rows preserve standard Compose `selected` and `toggleable` semantics for TalkBack
 * and UI tests.
 */

/** Horizontal margin between an inset group card and the window edge. */
val InsetGroupPadding = 16.dp

/**
 * Rounded card container for related rows, with an optional uppercase caption and footer note.
 * Callers place [InsetDivider] explicitly between visible rows so conditional rows stay stateless.
 */
@Composable
fun InsetGroup(
    modifier: Modifier = Modifier,
    caption: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        caption?.let {
            Text(
                it.uppercase(),
                // Aligned with the row text inside the card rather than the card edge, so the
                // caption reads as a heading for those rows rather than floating beside them.
                Modifier.padding(start = 14.dp, end = InsetGroupPadding, bottom = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            content = content,
        )
        footer?.let {
            Text(
                it,
                Modifier.padding(start = 14.dp, end = InsetGroupPadding, top = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The hairline between two rows.
 *
 * Inset from the left so it lines up under the text rather than running under a leading icon,
 * which is what makes a group read as one object instead of a stack of separate ones.
 */
@Composable
fun InsetDivider(startInset: Dp = InsetGroupPadding) {
    Box(
        Modifier
            .padding(start = startInset)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/**
 * Single row inside an [InsetGroup], with optional leading icon badge, subtitle [detail], and
 * trailing summary [value].
 */
@Composable
fun InsetRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    showChevron: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            Box(
                Modifier
                    .size(29.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(iconTint ?: MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(it, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        value?.let {
            // Allowed to shrink rather than claim its full width: a long value — an encoder name
            // is the usual one — otherwise squeezed the title beside it until that wrapped into
            // a narrow column, so the row read as "Enc od…" against the name.
            Text(it, Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * A row that is one of several choices, marked with a checkmark when it is the chosen one.
 *
 * The replacement for a filter chip. It reports `selected` through [Modifier.selectable], which
 * is the same property a chip reports, so anything reading the selection — an accessibility
 * service, or a test asserting on it — sees no difference.
 */
@Composable
fun InsetSelectRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            // Decorative: the row already reports its own selection, so announcing a tick as well
            // would have a screen reader say the same thing twice.
            Icon(Icons.Default.Check, null, Modifier.size(20.dp).clearAndSetSemantics {},
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}
