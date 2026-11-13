package com.scrcpy.remote.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.scrcpy.remote.R
import com.scrcpy.remote.ui.screenshot.PendingScreenshot
import com.scrcpy.remote.ui.screenshot.ScreenshotException
import com.scrcpy.remote.ui.screenshot.ScreenshotExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScreenshotState(
    val busy: Boolean = false,
    val pendingToken: String? = null,
    val suggestedName: String? = null,
    val pickerLaunched: Boolean = false,
    val notice: String? = null,
    /**
     * A staged capture waiting to be handed to another app.
     *
     * Kept apart from the picker fields rather than folded into them: sharing and saving both own
     * the same staged file, and a single "what happens next" flag would make it possible to
     * export and share the same capture at once, with whichever finished second finding the file
     * already discarded.
     */
    val shareFile: java.io.File? = null,
)

/** Restores staged ownership and its save/share destination after Activity or process recreation. */
class ScreenshotViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val exporter = ScreenshotExporter(application)
    private val _state = MutableStateFlow(ScreenshotState())
    val state = _state.asStateFlow()
    private var pending: PendingScreenshot? = null

    init {
        val token = saved.get<String>(KEY_TOKEN)
        if (token != null) {
            pending = exporter.restore(token)
            val restored = pending
            val sharing = saved.get<Boolean>(KEY_SHARING) == true
            val shareFile = if (sharing && restored != null) exporter.stagedFile(restored) else null
            if (restored != null && saved.get<Boolean>(KEY_SAVING) != true &&
                (!sharing || shareFile != null)
            ) {
                _state.value = ScreenshotState(
                    busy = true,
                    pendingToken = restored.token,
                    suggestedName = restored.suggestedName,
                    pickerLaunched = sharing || saved.get<Boolean>(KEY_PICKER) == true,
                    shareFile = shareFile,
                )
            } else {
                val interrupted = saved.get<Boolean>(KEY_SAVING) == true
                clearPendingState()
                _state.value = ScreenshotState(notice = application.getString(
                    if (interrupted) R.string.screenshot_error_save else R.string.screenshot_error_expired))
                if (restored != null) discardAfterClear(restored)
            }
        }
    }

    fun capture(surface: SurfaceView?) {
        if (_state.value.busy) return
        if (surface == null) {
            _state.value = ScreenshotState(notice = getApplication<Application>().getString(R.string.screenshot_error_not_ready))
            return
        }
        _state.value = ScreenshotState(busy = true)
        viewModelScope.launch {
            try {
                val image = exporter.capture(surface)
                pending = image
                saved[KEY_TOKEN] = image.token
                saved[KEY_PICKER] = false
                saved[KEY_SAVING] = false
                saved[KEY_SHARING] = false
                _state.value = ScreenshotState(true, image.token, image.suggestedName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Screenshot", "Capture failed", e)
                _state.value = ScreenshotState(notice = (e as? ScreenshotException)?.message
                    ?: getApplication<Application>().getString(R.string.screenshot_error_prepare))
            }
        }
    }

    /** Called before launching, so recreation never opens a second picker for the same image. */
    fun markPickerLaunched(): String? {
        val current = _state.value
        if (current.pendingToken == null || current.pickerLaunched) return null
        saved[KEY_PICKER] = true
        _state.value = current.copy(pickerLaunched = true)
        return current.suggestedName
    }

    /**
     * Captures and offers the result to another app instead of to the document picker.
     *
     * A separate entry point rather than a mode on [capture]: the picker is launched
     * automatically the moment a capture becomes pending, so a shared capture has to be marked as
     * already handled or it would be saved and shared at the same time.
     */
    fun shareCapture(surface: SurfaceView?) {
        if (_state.value.busy) return
        if (surface == null) {
            _state.value = ScreenshotState(notice = getApplication<Application>()
                .getString(R.string.screenshot_error_not_ready))
            return
        }
        _state.value = ScreenshotState(busy = true)
        viewModelScope.launch {
            try {
                val image = exporter.capture(surface)
                val file = exporter.stagedFile(image)
                if (file == null) {
                    discardAfterClear(image)
                    _state.value = ScreenshotState(notice = getApplication<Application>()
                        .getString(R.string.screenshot_error_expired))
                    return@launch
                }
                pending = image
                saved[KEY_TOKEN] = image.token
                // Marked as launched so the picker collector leaves this capture alone.
                saved[KEY_PICKER] = true
                saved[KEY_SAVING] = false
                saved[KEY_SHARING] = true
                _state.value = ScreenshotState(
                    busy = true,
                    pendingToken = image.token,
                    suggestedName = image.suggestedName,
                    pickerLaunched = true,
                    shareFile = file,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Screenshot", "Capture failed", e)
                _state.value = ScreenshotState(notice = (e as? ScreenshotException)?.message
                    ?: getApplication<Application>().getString(R.string.screenshot_error_prepare))
            }
        }
    }

    /**
     * Forgets a capture once the chooser has been shown.
     *
     * The staged file is deliberately **not** deleted here. The receiving app opens it through
     * its grant after the chooser resolves — which may be seconds later, or after this process
     * has gone — so discarding it now would hand out a URI to a file that no longer exists. It is
     * left for the cache's own expiry sweep, which runs before each new capture.
     */
    fun consumeShare() {
        pending = null
        clearPendingState()
        _state.value = ScreenshotState()
    }

    fun saveTo(destination: Uri?) {
        val image = pending
        if (image == null) {
            clearPendingState()
            _state.value = ScreenshotState(notice = getApplication<Application>().getString(R.string.screenshot_error_expired))
            return
        }
        // A restored document result or duplicate callback must not start a second export.
        if (saved.get<Boolean>(KEY_SAVING) == true) return
        saved[KEY_SAVING] = true
        viewModelScope.launch {
            try {
                val notice = if (destination == null) {
                    exporter.discard(image)
                    getApplication<Application>().getString(R.string.capture_cancelled)
                } else {
                    exporter.export(image, destination)
                    getApplication<Application>().getString(R.string.capture_saved)
                }
                // Saved state must already be consistent when the terminal state is published:
                // anything reacting to busy = false, including a save-state at that instant,
                // would otherwise see a token whose cached image has just been discarded and
                // report a spurious "expired" error on the next restore.
                clearPendingState()
                _state.value = ScreenshotState(notice = notice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScreenshotException) {
                Log.w("Screenshot", "Export failed", e)
                clearPendingState()
                _state.value = ScreenshotState(notice = e.message)
            } finally {
                clearPendingState()
            }
        }
    }

    fun pickerUnavailable() {
        pending?.let(::discardAfterClear)
        clearPendingState()
        _state.value = ScreenshotState(notice = getApplication<Application>().getString(R.string.capture_picker_unavailable))
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null) }

    private fun clearPendingState() {
        pending = null
        saved.remove<String>(KEY_TOKEN)
        saved.remove<Boolean>(KEY_PICKER)
        saved.remove<Boolean>(KEY_SAVING)
        saved.remove<Boolean>(KEY_SHARING)
    }

    private fun discardAfterClear(image: PendingScreenshot) {
        CoroutineScope(Dispatchers.IO).launch {
            try { exporter.discard(image) }
            catch (e: ScreenshotException) { Log.w("Screenshot", "Pending screenshot cleanup failed", e) }
        }
    }

    override fun onCleared() {
        pending?.let(::discardAfterClear)
        clearPendingState()
        super.onCleared()
    }

    private companion object {
        const val KEY_TOKEN = "screenshot.token"
        const val KEY_PICKER = "screenshot.pickerLaunched"
        const val KEY_SAVING = "screenshot.saving"
        const val KEY_SHARING = "screenshot.sharing"
    }
}
