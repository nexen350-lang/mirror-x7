package com.scrcpy.remote.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.scrcpy.remote.R
import com.scrcpy.remote.ui.noInitialFocusInTouchMode

/**
 * Full-height modal sheet for multi-section forms, with pinned Cancel, title, and Save actions.
 *
 * Back cancels; outside taps are ignored so unsaved edits cannot be dismissed accidentally.
 * Hoist [scrollState] when navigating to sub-pages (such as App info) so scroll position survives.
 */
@Composable
fun SettingsSheet(
    title: String,
    saveLabel: String,
    saveEnabled: Boolean,
    cancelEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    bodyTag: String,
    subtitle: String? = null,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (cancelEnabled) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 12.dp).fillMaxHeight(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                    // Cancel is a text button, whose own padding already insets its word; Save is
                    // filled, so its end lines up with the form's groups below instead.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.cancel))
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(saveLabel)
                        }
                    }
                    // Show the top bar divider only once content scrolls beneath it. At rest
                    // it only doubled the first section's own divider just below.
                    val scrolled by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }
                    HorizontalDivider(
                        Modifier.testTag("settings-sheet-hairline").alpha(if (scrolled) 1f else 0f),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // Tagged outside the scroll, so the tag measures what is on screen rather than
                    // the whole form.
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag(bodyTag)
                            .noInitialFocusInTouchMode()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
