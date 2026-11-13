package com.scrcpy.remote.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.scrcpy.remote.R
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import com.scrcpy.remote.data.ProfileDocuments
import com.scrcpy.remote.data.ProfileImportPlan
import com.scrcpy.remote.data.planImport
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileTransferState(
    val busy: Boolean = false,
    val preview: ProfileImportPlan? = null,
    val replaceExisting: Boolean = false,
    val importDefaults: Boolean = false,
    val notice: String? = null,
)

sealed interface ProfilePicker {
    data object Import : ProfilePicker
    data class Export(val suggestedName: String) : ProfilePicker
}

/** Only explicit confirmation commits a validated import. Instance state stores opaque cache IDs. */
class ProfileTransferViewModel @JvmOverloads constructor(
    app: Application,
    private val saved: SavedStateHandle,
    private val repository: DeviceRepository = DeviceRepository.get(app),
) : AndroidViewModel(app) {
    private val documents = ProfileDocuments(app)
    private val _state = MutableStateFlow(ProfileTransferState())
    val state = _state.asStateFlow()
    private var incoming: ConfigurationSnapshot? = null
    private var pickerReady = false
    private var operation: Job? = null
    private val previewRevision = AtomicLong()

    init {
        when (saved.get<String>(STAGE)) {
            CHOOSE_IMPORT -> {
                pickerReady = true
                _state.value = ProfileTransferState(busy = true)
            }
            CHOOSE_EXPORT -> restorePendingExport()
            PREVIEW, APPLYING -> restorePreview()
            null -> Unit
            else -> {
                val token = saved.get<String>(TOKEN)
                clearPending()
                token?.let(::discardAfterClear)
                _state.value = ProfileTransferState(notice = app.getString(R.string.transfer_interrupted))
            }
        }
    }

    fun beginImport() {
        if (!canStart()) return
        saved[STAGE] = CHOOSE_IMPORT
        saved[PICKER_LAUNCHED] = false
        pickerReady = true
        _state.value = ProfileTransferState(busy = true)
    }

    /** Call only after the export privacy warning has been explicitly accepted. */
    fun beginExport(snapshot: ConfigurationSnapshot? = null) {
        if (!canStart()) return
        val supplied = snapshot?.frozenCopy()
        saved[STAGE] = PREPARING
        _state.value = ProfileTransferState(busy = true)
        operation = viewModelScope.launch {
            var created: String? = null
            var adopted = false
            try {
                val value = supplied ?: repository.mutate { configurationForExport() }
                withContext(Dispatchers.IO) {
                    created = documents.cache(value)
                }
                saved[TOKEN] = checkNotNull(created)
                saved[STAGE] = CHOOSE_EXPORT
                saved[PICKER_LAUNCHED] = false
                pickerReady = true
                adopted = true
                // A distinct emission wakes the Activity collector after the export is staged.
                _state.value = _state.value.copy(notice = getApplication<Application>().getString(R.string.transfer_choose_destination))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failure(e)
            } catch (e: SecurityException) {
                failure(e)
            } finally {
                if (!adopted) created?.let { withContext(NonCancellable + Dispatchers.IO) { discardLogged(it) } }
            }
        }
    }

    fun takePickerRequest(): ProfilePicker? {
        if (!pickerReady || saved.get<Boolean>(PICKER_LAUNCHED) == true) return null
        val request = when (saved.get<String>(STAGE)) {
            CHOOSE_IMPORT -> ProfilePicker.Import
            CHOOSE_EXPORT -> {
                if (saved.get<String>(TOKEN) == null) return null
                ProfilePicker.Export("scrcpy-profiles-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json")
            }
            else -> return null
        }
        saved[PICKER_LAUNCHED] = true
        return request
    }

    fun importSelected(uri: Uri?) {
        if (saved.get<String>(STAGE) != CHOOSE_IMPORT) return
        if (uri == null) { cancel(); return }
        saved[STAGE] = LOADING
        loadPreview { documents.readDocument(uri) }
    }

    /** History uses the same validated cache and explicit import confirmation as a selected file. */
    fun previewSnapshot(snapshot: ConfigurationSnapshot) {
        if (!canStart()) return
        saved[STAGE] = LOADING
        _state.value = ProfileTransferState(busy = true)
        val frozen = snapshot.frozenCopy()
        loadPreview { frozen }
    }

    private fun loadPreview(load: suspend () -> ConfigurationSnapshot) {
        val revision = previewRevision.incrementAndGet()
        operation = viewModelScope.launch {
            var created: String? = null
            var adopted = false
            try {
                val value = load()
                withContext(Dispatchers.IO) { created = documents.cache(value) }
                val preview = calculatePreview(revision, value) ?: return@launch
                if (previewRevision.get() != revision) return@launch
                incoming = value
                saved[TOKEN] = checkNotNull(created)
                saved[STAGE] = PREVIEW
                saved[REPLACE] = false
                saved[DEFAULTS] = false
                _state.value = ProfileTransferState(preview = preview)
                adopted = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (previewRevision.get() == revision) failure(e)
            } catch (e: SecurityException) {
                if (previewRevision.get() == revision) failure(e)
            } catch (e: IllegalArgumentException) {
                if (previewRevision.get() == revision) failure(e)
            } finally {
                if (!adopted) created?.let { withContext(NonCancellable + Dispatchers.IO) { discardLogged(it) } }
            }
        }
    }

    fun exportSelected(uri: Uri?) {
        if (saved.get<String>(STAGE) != CHOOSE_EXPORT) return
        if (uri == null) { cancel(); return }
        val token = saved.get<String>(TOKEN) ?: run { pickerUnavailable(); return }
        operation?.cancel()
        saved[STAGE] = WRITING
        operation = viewModelScope.launch {
            try {
                documents.writeDocument(token, uri)
                clearPending()
                _state.value = ProfileTransferState(notice = getApplication<Application>().getString(R.string.transfer_exported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                failure(e, export = true)
            } catch (e: SecurityException) {
                failure(e, export = true)
            } catch (e: IllegalArgumentException) {
                failure(e, export = true)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { discardLogged(token) }
            }
        }
    }

    fun updateOptions(replace: Boolean, defaults: Boolean) {
        val value = incoming ?: return
        val current = _state.value
        if (saved.get<String>(STAGE) != PREVIEW || current.preview == null) return
        val revision = previewRevision.incrementAndGet()
        operation?.cancel()
        _state.value = current.copy(busy = true, notice = null)
        operation = viewModelScope.launch {
            try {
                val preview = calculatePreview(revision, value, replace, defaults) ?: return@launch
                if (previewRevision.get() != revision) return@launch
                saved[REPLACE] = replace
                saved[DEFAULTS] = defaults
                _state.value = ProfileTransferState(preview = preview, replaceExisting = replace, importDefaults = defaults)
            } catch (e: IOException) {
                if (previewRevision.get() == revision) _state.value = current.copy(busy = false, notice = e.message)
            }
        }
    }

    fun confirmImport() {
        val current = _state.value
        val preview = current.preview ?: return
        val value = incoming ?: return
        if (current.busy) return
        val revision = previewRevision.incrementAndGet()
        saved[STAGE] = APPLYING
        _state.value = current.copy(busy = true)
        operation = viewModelScope.launch {
            val success = try {
                repository.mutate {
                    importConfiguration(value, preview.before, current.replaceExisting, current.importDefaults)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // failure() clears the staged import and resets the state, so nothing stays APPLYING/busy.
                if (previewRevision.get() == revision) failure(e)
                return@launch
            }
            if (previewRevision.get() != revision) return@launch
            if (success) {
                val token = saved.get<String>(TOKEN)
                clearPending()
                _state.value = ProfileTransferState(notice = getApplication<Application>().getString(
                    R.string.transfer_imported, preview.added, preview.replaced, preview.skipped))
                token?.let(::discardAfterClear)
            } else {
                saved[STAGE] = PREVIEW
                try {
                    val refreshed = calculatePreview(revision, value, current.replaceExisting, current.importDefaults)
                        ?: return@launch
                    if (previewRevision.get() != revision) return@launch
                    _state.value = current.copy(busy = false,
                        preview = refreshed,
                        notice = getApplication<Application>().getString(R.string.transfer_import_failed))
                } catch (e: IOException) { if (previewRevision.get() == revision) failure(e) }
            }
        }
    }

    fun cancel() {
        if (saved.get<String>(STAGE) in setOf(APPLYING, WRITING)) return
        operation?.cancel()
        val token = saved.get<String>(TOKEN)
        clearPending()
        _state.value = ProfileTransferState()
        token?.let(::discardAfterClear)
    }

    fun pickerUnavailable() {
        operation?.cancel()
        val token = saved.get<String>(TOKEN)
        clearPending()
        _state.value = ProfileTransferState(notice = getApplication<Application>().getString(R.string.transfer_picker_unavailable))
        token?.let(::discardAfterClear)
    }

    fun clearNotice() { _state.value = _state.value.copy(notice = null) }

    private fun canStart() = saved.get<String>(STAGE) == null && !_state.value.busy

    private fun restorePendingExport() {
        _state.value = ProfileTransferState(busy = true)
        operation = viewModelScope.launch {
            try {
                val token = saved.get<String>(TOKEN) ?: throw IOException("Missing pending export")
                withContext(Dispatchers.IO) { documents.restore(token) }
                pickerReady = true
                _state.value = _state.value.copy(notice = getApplication<Application>().getString(R.string.transfer_choose_destination))
            } catch (e: IOException) { failure(e) }
            catch (e: SecurityException) { failure(e) }
        }
    }

    private fun restorePreview() {
        val revision = previewRevision.incrementAndGet()
        _state.value = ProfileTransferState(busy = true)
        operation = viewModelScope.launch {
            try {
                val token = saved.get<String>(TOKEN) ?: throw IOException("Missing pending import")
                val value = withContext(Dispatchers.IO) { documents.restore(token) }
                val replace = saved.get<Boolean>(REPLACE) == true
                val defaults = saved.get<Boolean>(DEFAULTS) == true
                val preview = calculatePreview(revision, value, replace, defaults) ?: return@launch
                if (previewRevision.get() != revision) return@launch
                incoming = value
                saved[STAGE] = PREVIEW
                _state.value = ProfileTransferState(preview = preview,
                    replaceExisting = replace, importDefaults = defaults,
                    notice = getApplication<Application>().getString(R.string.transfer_review_again))
            } catch (e: IOException) { if (previewRevision.get() == revision) failure(e) }
            catch (e: SecurityException) { if (previewRevision.get() == revision) failure(e) }
        }
    }

    private suspend fun calculatePreview(
        revision: Long,
        value: ConfigurationSnapshot,
        replace: Boolean = false,
        defaults: Boolean = false,
    ): ProfileImportPlan? {
        val (before, repairsStorage) = repository.mutate {
            if (previewRevision.get() == revision) importPreviewBaseline() else null
        } ?: return null
        // Validation serializes three potentially large snapshots. Keep it off Main and do not
        // hold the storage writer while computing; confirmation still checks the committed baseline.
        return withContext(Dispatchers.Default) {
            if (previewRevision.get() == revision) {
                planImport(before, value, replace, defaults).copy(repairsStorage = repairsStorage)
            } else null
        }
    }

    private fun failure(error: Exception, export: Boolean = false) {
        Log.w("ProfileTransfer", "Configuration transfer failed", error)
        val token = saved.get<String>(TOKEN)
        clearPending()
        _state.value = ProfileTransferState(notice = getApplication<Application>().getString(
            if (export) R.string.transfer_export_failed else R.string.transfer_failed, error.message ?: error.javaClass.simpleName))
        token?.let(::discardAfterClear)
    }

    private fun clearPending() {
        previewRevision.incrementAndGet()
        incoming = null
        pickerReady = false
        listOf(STAGE, TOKEN, PICKER_LAUNCHED, REPLACE, DEFAULTS).forEach { saved.remove<Any>(it) }
    }

    private fun discardLogged(token: String) {
        try { documents.discard(token) }
        catch (e: IOException) { Log.w("ProfileTransfer", "Cannot remove cached backup", e) }
        catch (e: SecurityException) { Log.w("ProfileTransfer", "Cannot remove cached backup", e) }
    }

    private fun discardAfterClear(token: String) {
        CoroutineScope(Dispatchers.IO).launch { discardLogged(token) }
    }

    override fun onCleared() {
        previewRevision.incrementAndGet()
        saved.get<String>(TOKEN)?.let(::discardAfterClear)
        super.onCleared()
    }

    private companion object {
        const val STAGE = "profileTransfer.stage"
        const val TOKEN = "profileTransfer.token"
        const val PICKER_LAUNCHED = "profileTransfer.picker"
        const val REPLACE = "profileTransfer.replace"
        const val DEFAULTS = "profileTransfer.defaults"
        const val CHOOSE_IMPORT = "chooseImport"
        const val CHOOSE_EXPORT = "chooseExport"
        const val PREVIEW = "preview"
        const val PREPARING = "preparing"
        const val LOADING = "loading"
        const val WRITING = "writing"
        const val APPLYING = "applying"
    }
}
