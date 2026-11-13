package com.scrcpy.remote.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.scrcpy.remote.R
import com.scrcpy.remote.data.ConfigurationSnapshot
import com.scrcpy.remote.data.DeviceRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BackupHistoryState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val selectedId: String? = null,
    val deleteId: String? = null,
    val exportId: String? = null,
    val notice: String? = null,
)

/** Only record IDs go into instance state. No settings, endpoints, or raw recovery values are saved. */
class BackupHistoryViewModel @JvmOverloads constructor(
    app: Application,
    private val saved: SavedStateHandle,
    private val repository: DeviceRepository = DeviceRepository.get(app),
) : AndroidViewModel(app) {
    val entries = repository.backupHistory
    private val _state = MutableStateFlow(BackupHistoryState(
        visible = saved[VISIBLE] ?: false,
        selectedId = saved[SELECTED],
        deleteId = saved[DELETE],
        exportId = saved[EXPORT],
    ))
    val state = _state.asStateFlow()

    init {
        if (_state.value.visible) refresh()
    }

    fun open() {
        if (_state.value.busy) return
        saved[VISIBLE] = true
        _state.value = _state.value.copy(visible = true)
        refresh()
    }

    fun close() {
        if (_state.value.busy) return
        clearSelection()
        saved[VISIBLE] = false
        _state.value = BackupHistoryState()
    }

    fun select(id: String?) {
        if (_state.value.busy) return
        saved[SELECTED] = id
        _state.value = _state.value.copy(selectedId = id, notice = null)
    }

    fun askDelete(id: String?) {
        if (_state.value.busy) return
        saved[DELETE] = id
        _state.value = _state.value.copy(deleteId = id, notice = null)
    }

    fun askExport(id: String?) {
        if (_state.value.busy) return
        saved[EXPORT] = id
        _state.value = _state.value.copy(exportId = id, notice = null)
    }

    fun refresh() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val success = repository.mutate { refreshBackupHistory() }
                _state.value = _state.value.copy(busy = false,
                    notice = if (success) null else getApplication<Application>().getString(R.string.history_failed))
            } catch (error: IOException) {
                failed(error)
            } catch (error: SecurityException) {
                failed(error)
            }
        }
    }

    fun confirmDelete() {
        val id = _state.value.deleteId ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val success = repository.mutate { deleteBackupHistory(id) }
                if (success) clearSelection()
                _state.value = _state.value.copy(busy = false,
                    notice = getApplication<Application>().getString(if (success) R.string.history_deleted else R.string.history_failed))
            } catch (error: IOException) {
                failed(error)
            } catch (error: SecurityException) {
                failed(error)
            }
        }
    }

    fun confirmExport(onReady: (ConfigurationSnapshot) -> Unit) {
        val id = _state.value.exportId ?: return
        if (_state.value.busy) return
        saved.remove<String>(EXPORT)
        _state.value = _state.value.copy(exportId = null)
        prepareSnapshot(id, onReady)
    }

    fun prepareSnapshot(id: String, onReady: (ConfigurationSnapshot) -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            try {
                val snapshot = repository.mutate { backupSnapshot(id) }
                // Resumes on Main: transfer state/cache ownership and SavedStateHandle stay on Main.
                onReady(snapshot)
                clearSelection()
                saved[VISIBLE] = false
                _state.value = BackupHistoryState()
            } catch (error: IOException) {
                failed(error)
            } catch (error: SecurityException) {
                failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        Log.w("BackupHistory", "History operation failed", error)
        _state.value = _state.value.copy(busy = false,
            notice = getApplication<Application>().getString(R.string.history_read_failed, error.message ?: error.javaClass.simpleName))
    }

    private fun clearSelection() {
        listOf(SELECTED, DELETE, EXPORT).forEach { saved.remove<String>(it) }
        _state.value = _state.value.copy(selectedId = null, deleteId = null, exportId = null)
    }

    private companion object {
        const val VISIBLE = "backupHistory.visible"
        const val SELECTED = "backupHistory.selected"
        const val DELETE = "backupHistory.delete"
        const val EXPORT = "backupHistory.export"
    }
}
