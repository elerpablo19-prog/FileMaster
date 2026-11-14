package com.filemaster.ui.screens.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.filemaster.core.EntryKind
import com.filemaster.core.FileSystemEntry
import com.filemaster.core.SortMode
import com.filemaster.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------
// Estado observável pela UI (imutável; atualizado por copy() atomically)
// ---------------------------------------------------------------------

data class FileListUiState(
    val path: String = "",
    val entries: List<FileSystemEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showHidden: Boolean = false,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val asGrid: Boolean = false,
    // Seleção múltipla
    val selection: Set<String> = emptySet(),
    // Operação longa em andamento (barra de progresso global)
    val busyOperation: String? = null,
    val busyProgress: Float = 0f,
)

class FileListViewModel(
    private val repository: com.filemaster.domain.FileSystemRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Rota inicial: argumento da navegação ou raiz do armazenamento. */
    private val initialPath: String = savedStateHandle["path"] ?: "/storage/emulated/0"

    private val _state = MutableStateFlow(FileListUiState(path = initialPath))
    val state: StateFlow<FileListUiState> = _state.asStateFlow()

    init {
        load(initialPath)
    }

    // -----------------------------------------------------------------
    // Navegação e listagem
    // -----------------------------------------------------------------

    fun load(path: String) = viewModelScope.launch {
        _state.update {
            it.copy(isLoading = true, error = null, path = path, selection = emptySet())
        }
        try {
            val entries = repository.list(path, _state.value.showHidden, _state.value.sortMode)
            _state.update { it.copy(isLoading = false, entries = entries) }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    fun goUp() {
        val current = _state.value.path
        val parent = current.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != current) {
            load(parent.ifEmpty { "/" })
        }
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        load(_state.value.path)
    }

    fun toggleGrid() = _state.update { it.copy(asGrid = !it.asGrid) }

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        load(_state.value.path)
    }

    // -----------------------------------------------------------------
    // Seleção múltipla
    // -----------------------------------------------------------------

    fun toggleSelect(entry: FileSystemEntry) = _state.update { s ->
        val next = if (entry.path in s.selection) s.selection - entry.path else s.selection + entry.path
        s.copy(selection = next)
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // -----------------------------------------------------------------
    // Operações destrutivas (com progresso vindo do callback Rust)
    // -----------------------------------------------------------------

    fun deleteSelected() = viewModelScope.launch {
        runBusy("Excluindo…") { onProgress ->
            for (target in _state.value.selection) {
                repository.delete(target, onProgress)
            }
        }
        load(_state.value.path)
    }

    fun createFolder(name: String) = viewModelScope.launch {
        runBusy("Criando pasta…") { onProgress ->
            repository.createDir(joinPath(_state.value.path, name))
        }
        load(_state.value.path)
    }

    fun rename(entry: FileSystemEntry, newName: String) = viewModelScope.launch {
        runBusy("Renomeando…") { onProgress ->
            repository.rename(entry.path, newName)
        }
        load(_state.value.path)
    }

    fun copySelectedTo(destination: String) = viewModelScope.launch {
        runBusy("Copiando…") { onProgress ->
            for (src in _state.value.selection) {
                repository.copy(src, joinPath(destination, src.substringAfterLast('/')), onProgress)
            }
        }
        load(_state.value.path)
    }

    /** Envolve operação com estado de progresso centralizado (busyOperation). */
    private suspend fun runBusy(
        label: String,
        block: suspend (onProgress: (Long, Long, String) -> Unit) -> Unit,
    ) {
        _state.update { it.copy(busyOperation = label, error = null) }
        try {
            block { done, total, _ ->
                val pct = if (total > 0) done.toFloat() / total else 0f
                _state.update { it.copy(busyProgress = pct) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message) }
        } finally {
            _state.update { it.copy(busyOperation = null, busyProgress = 0f) }
        }
    }

    private fun joinPath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    companion object {
        /** Factory injetando o AppContainer da Application + SavedStateHandle. */
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FileListViewModel(
                    repository = container.repository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}

/** Helper de UI: ícone por tipo de entrada. */
fun FileSystemEntry.isDirectory(): Boolean = kind == EntryKind.DIRECTORY
