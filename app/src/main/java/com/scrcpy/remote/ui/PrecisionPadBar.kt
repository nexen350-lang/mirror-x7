package com.scrcpy.remote.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.edit
import androidx.compose.ui.unit.dp
import com.scrcpy.remote.R
import java.text.NumberFormat

/**
 * Connects the pad's bar to the mirror view that owns the cursor, so a button can hold or lift a
 * press there. One per mirror screen; the view registers itself while it exists.
 */
@Stable
internal class PrecisionPadHandle {
    internal var view: ScrcpyMirrorView? = null
        set(value) {
            field = value
            if (value == null) update(dragging = false, cursorY = Float.NaN)
        }

    /** Whether a press is being held on the device. */
    var dragging by mutableStateOf(false)
        private set

    /** Where the cursor tip is drawn, in the mirror's coordinates; NaN when there is none. */
    var cursorY by mutableFloatStateOf(Float.NaN)
        private set

    fun toggleDrag() {
        view?.togglePrecisionPadDrag()
    }

    fun releaseDrag() {
        view?.releasePrecisionPadDrag()
    }

    internal fun update(dragging: Boolean, cursorY: Float) {
        this.dragging = dragging
        this.cursorY = cursorY
    }
}

/**
 * The speed is about this phone's user and screen rather than any device, so it is remembered
 * here, outside device profiles and their backups.
 */
internal object PrecisionPadPreferences {
    private const val FILE = "precision_pad"
    private const val KEY_SPEED = "speed"

    fun speed(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, PrecisionPad.DEFAULT_SPEED)
            .takeIf { it in PrecisionPad.SPEEDS } ?: PrecisionPad.DEFAULT_SPEED

    fun saveSpeed(context: Context, speed: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat(KEY_SPEED, speed) }
    }
}

/**
 * The precision pad's controls: what the pad does, an explicit hold for dragging, the speed and
 * the way back to direct touch. It sits at the bottom of the screen and moves to the top whenever
 * it would hide the cursor, so the place being aimed at is never under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrecisionPadBar(
    dragging: Boolean,
    speed: Float,
    cursorY: () -> Float,
    onToggleDrag: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var atTop by rememberSaveable { mutableStateOf(false) }
    var laidOut by remember { mutableStateOf(LaidOutBar(atTop, 0f, 0f)) }
    // Top plus bottom of the area the bar moves in: mirroring a span through it gives the span at
    // the opposite edge, wherever that area sits.
    var mirror by remember { mutableFloatStateOf(0f) }
    val margin = with(LocalDensity.current) { BAR_MARGIN.toPx() }
    LaunchedEffect(margin) {
        // Checked again whenever the bar itself moves or resizes — a keyboard opening lifts it —
        // not only when the cursor does.
        snapshotFlow { Triple(cursorY(), laidOut, mirror) }.collect { (y, bar, area) ->
            atTop = padBarAtTop(y, bar.top, bar.bottom, area, margin, bar.atTop)
        }
    }
    // The edge this composition lays the bar out at, for its measured bounds to be judged against.
    val shownAtTop = atTop
    val holdLabel = stringResource(if (dragging) R.string.precision_pad_drop else R.string.precision_pad_drag_label)
    val holdDescription = stringResource(R.string.precision_pad_drag)
    val holdState = stringResource(if (dragging) R.string.precision_pad_drag_held else R.string.precision_pad_drag_free)
    val speedText = remember(speed) {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(speed)
    }
    val speedDescription = stringResource(R.string.precision_pad_speed_description, speedText)
    val exitDescription = stringResource(R.string.precision_pad_exit_description)
    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { it.boundsInRoot().let { area -> mirror = area.top + area.bottom } },
    ) {
        Surface(
            modifier = Modifier
                .align(if (shownAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(8.dp)
                .widthIn(max = 520.dp)
                // Never more than half the screen, whatever the text size: the rest is the pad.
                .heightIn(max = maxHeight / 2)
                .onGloballyPositioned {
                    val bounds = it.boundsInRoot()
                    laidOut = LaidOutBar(shownAtTop, bounds.top, bounds.bottom)
                }
                .testTag("precision-pad-bar"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
            shadowElevation = 4.dp,
        ) {
            // The actions come first, so a narrow window or large text scrolls the hint, not them.
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton(Icons.Default.PanTool, description = holdDescription, label = holdLabel,
                        active = dragging, state = holdState, modifier = Modifier.testTag("precision-pad-drag"),
                        onClick = onToggleDrag)
                    PanelButton(Icons.Default.Speed, description = speedDescription,
                        label = stringResource(R.string.precision_pad_speed, speedText),
                        modifier = Modifier.testTag("precision-pad-speed")) {
                        onSpeedChange(PrecisionPad.nextSpeed(speed))
                    }
                    PanelButton(Icons.Default.TouchApp, description = exitDescription,
                        label = stringResource(R.string.precision_pad_exit),
                        modifier = Modifier.testTag("precision-pad-exit"), onClick = onExit)
                }
                Text(stringResource(R.string.precision_pad_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** How close the cursor may come to the bar before the bar moves away from it. */
private val BAR_MARGIN = 16.dp

/** Where the bar was last laid out: at which edge, and the span it covered there. */
private data class LaidOutBar(val atTop: Boolean, val top: Float, val bottom: Float)
