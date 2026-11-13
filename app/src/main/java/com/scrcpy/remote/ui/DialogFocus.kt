package com.scrcpy.remote.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * Keeps a dialog from handing focus to one of its controls merely because it opened.
 *
 * Android 8 gives a new window's first touch-focusable control focus even in touch mode, and a
 * slider or a text field counts. The settings dialogs therefore opened scrolled down to their
 * first slider, past the card at the top; with a text field first, the keyboard would have come
 * up as well. Only that automatic, directional entry is refused, and only in touch mode: a tap
 * still focuses a field (it enters with [FocusDirection.Enter]), and with a keyboard, focus moves
 * in as usual.
 */
@Composable
internal fun Modifier.noInitialFocusInTouchMode(): Modifier {
    val inputModes = LocalInputModeManager.current
    return focusProperties {
        onEnter = {
            if (inputModes.inputMode == InputMode.Touch && requestedFocusDirection != FocusDirection.Enter) {
                cancelFocusChange()
            }
        }
    }.focusGroup()
}
