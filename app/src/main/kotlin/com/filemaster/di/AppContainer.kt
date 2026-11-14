package com.filemaster.di

import com.filemaster.core.FileEngine
import com.filemaster.domain.FileSystemRepository
import com.filemaster.permissions.StoragePermissionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Contêiner de dependências manual (MVVM sem framework de DI).
 *
 * Grafo:
 *   FileEngine (Rust/UniFFI, singleton)
 *     -> FileSystemRepository
 *          -> FileListViewModel (por navigation destination, via factory)
 */
class AppContainer(val engine: FileEngine) {

    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    val repository: FileSystemRepository by lazy {
        FileSystemRepository(engine, ioDispatcher)
    }

    val permissionHandler: StoragePermissionHandler by lazy {
        StoragePermissionHandler()
    }
}
