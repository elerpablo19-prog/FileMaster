package com.filemaster.ui.screens.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filemaster.core.EntryKind
import com.filemaster.core.FileSystemEntry
import com.filemaster.core.SortMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela principal do navegador de arquivos.
 *
 * Performance:
 * - LazyColumn/LazyVerticalGrid virtualizam a lista (milhares de itens OK);
 * - `key = { it.path }` permite reorder/diff O(n) sem recompor tudo;
 * - Items são `data class` imutáveis vindas do StateFlow — o Compose pula
 *   recomposição de itens cujos parâmetros não mudaram (structural equality).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    state: FileListUiState,
    onNavigateTo: (String) -> Unit,
    onGoUp: () -> Unit,
    onLoad: (String) -> Unit,
    onToggleHidden: () -> Unit,
    onToggleGrid: () -> Unit,
    onSortMode: (SortMode) -> Unit,
    onToggleSelect: (FileSystemEntry) -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onCreateFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    val dateFormatter = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            FileTopBar(
                path = state.path,
                asGrid = state.asGrid,
                showHidden = state.showHidden,
                onGoUp = onGoUp,
                onToggleGrid = onToggleGrid,
                onToggleHidden = onToggleHidden,
                onSortMode = onSortMode,
            )
        },
        bottomBar = {
            if (state.selection.isNotEmpty()) {
                SelectionBar(
                    count = state.selection.size,
                    onDelete = onDeleteSelection,
                    onClear = onClearSelection,
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Nova pasta") },
                icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                onClick = { onCreateFolder("Nova pasta ${System.currentTimeMillis() % 1000}") },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {

            state.error?.let { error ->
                ErrorBanner(error)
            }

            // Barra de progresso de operação longa (cópia/exclusão em Rust)
            state.busyOperation?.let { op ->
                LinearProgressIndicator(
                    progress = { state.busyProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = "$op ${(state.busyProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.entries.isEmpty()) {
                EmptyState()
            } else if (state.asGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        GridItem(
                            entry = entry,
                            selected = entry.path in state.selection,
                            onClick = { onEntryClick(entry, state, onNavigateTo, onToggleSelect) },
                            onLongClick = { onToggleSelect(entry) },
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        FileListItem(
                            entry = entry,
                            selected = entry.path in state.selection,
                            dateText = dateFormatter.format(Date(entry.modifiedMs)),
                            onClick = { onEntryClick(entry, state, onNavigateTo, onToggleSelect) },
                            onLongClick = { onToggleSelect(entry) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }
    }
}

private fun onEntryClick(
    entry: FileSystemEntry,
    state: FileListUiState,
    onNavigateTo: (String) -> Unit,
    onToggleSelect: (FileSystemEntry) -> Unit,
) {
    when {
        state.selection.isNotEmpty() -> onToggleSelect(entry)
        entry.kind == EntryKind.DIRECTORY -> onNavigateTo(entry.path)
        // Arquivos: abrir com app externo é tratado no nível da Activity
        else -> onNavigateTo(entry.path)
    }
}

// =====================================================================
// Top bar com breadcrumb, sort e toggles
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileTopBar(
    path: String,
    asGrid: Boolean,
    showHidden: Boolean,
    onGoUp: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHidden: () -> Unit,
    onSortMode: (SortMode) -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text = path.substringAfterLast('/').ifEmpty { "/" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onGoUp) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Subir um nível")
            }
        },
        actions = {
            IconButton(onClick = { /* TODO: tela de busca */ }) {
                Icon(Icons.Filled.Search, contentDescription = "Buscar")
            }
            IconButton(onClick = onToggleHidden) {
                val icon = if (showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                Icon(icon, contentDescription = "Arquivos ocultos")
            }
            IconButton(onClick = onToggleGrid) {
                val icon = if (asGrid) Icons.Filled.ListIcon else Icons.Filled.GridView
                Icon(icon, contentDescription = "Alternar grade/lista")
            }
            IconButton(onClick = { sortMenuOpen = true }) {
                Icon(Icons.Filled.Sort, contentDescription = "Ordenar")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label()) },
                        onClick = {
                            sortMenuOpen = false
                            onSortMode(mode)
                        },
                    )
                }
            }
        },
    )
}

private fun SortMode.label(): String = when (this) {
    SortMode.NAME_ASC -> "Nome (A-Z)"
    SortMode.NAME_DESC -> "Nome (Z-A)"
    SortMode.SIZE_DESC -> "Tamanho (maior)"
    SortMode.MODIFIED_DESC -> "Recentes"
    SortMode.MODIFIED_ASC -> "Antigos"
}

// =====================================================================
// Item de lista
// =====================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    entry: FileSystemEntry,
    selected: Boolean,
    dateText: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.kind == EntryKind.DIRECTORY) dateText else "${entry.sizeBytes} B  ·  $dateText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            // Permissões POSIX em octal (ex.: "755") — UShort precisa converter para formatar
            text = entry.permissionsOctal.toInt().toString(8),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(
    entry: FileSystemEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(entry.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
            )
        }
    }
}

// =====================================================================
// Auxiliares
// =====================================================================

private fun FileSystemEntry.icon(): ImageVector = when (kind) {
    EntryKind.DIRECTORY -> Icons.Filled.Folder
    EntryKind.SYMLINK -> Icons.Filled.Link
    EntryKind.FILE -> when {
        name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp") -> Icons.Filled.Image
        name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".ogg") -> Icons.Filled.MusicNote
        name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".rs") || name.endsWith(".kt") -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }
}

@Composable
private fun SelectionBar(count: Int, onDelete: () -> Unit, onClear: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selecionado(s)", modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = "Limpar seleção")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Pasta vazia", style = MaterialTheme.typography.titleMedium)
        }
    }
}
