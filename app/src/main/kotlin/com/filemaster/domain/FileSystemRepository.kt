package com.filemaster.domain

import com.filemaster.core.FileEngine
import com.filemaster.core.FileSystemEntry
import com.filemaster.core.SortMode
import com.filemaster.core.VolumeInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Único ponto de acesso do app ao Core Rust. Isola a UI e os ViewModels
 * dos detalhes de UniFFI/JNI, e centraliza a política de dispatch (IO).
 */
class FileSystemRepository(
    private val engine: FileEngine,
    private val io: CoroutineDispatcher,
) {

    suspend fun list(path: String, showHidden: Boolean, sort: SortMode): List<FileSystemEntry> =
        withContext(io) { engine.listDir(path, showHidden, sort) }

    suspend fun search(root: String, query: String, maxResults: Int = 500): List<FileSystemEntry> =
        withContext(io) { engine.search(root, query, maxResults.toUInt()) }

    suspend fun volumeInfo(path: String): VolumeInfo =
        withContext(io) { engine.volumeInfo(path) }

    suspend fun delete(target: String, onProgress: (Long, Long, String) -> Unit = { _, _, _ -> }): Unit =
        withContext(io) {
            engine.delete(target, ProgressBridge(onProgress))
        }

    suspend fun copy(source: String, destination: String, onProgress: (Long, Long, String) -> Unit = { _, _, _ -> }): Unit =
        withContext(io) {
            engine.copy(source, destination, ProgressBridge(onProgress))
        }

    suspend fun move(source: String, destination: String, onProgress: (Long, Long, String) -> Unit = { _, _, _ -> }): Unit =
        withContext(io) {
            engine.moveEntry(source, destination, ProgressBridge(onProgress))
        }

    suspend fun createDir(path: String): Unit = withContext(io) { engine.createDir(path) }

    suspend fun rename(source: String, newName: String): Unit =
        withContext(io) { engine.rename(source, newName) }

    suspend fun readText(path: String, maxBytes: Long = 1_000_000): String =
        withContext(io) {
            // ByteArray direto do binding (UniFFI mapeia Vec<u8> -> ByteArray)
            engine.readFile(path, maxBytes.toULong()).decodeToString()
        }

    /**
     * Ponte entre o callback Rust (`ProgressListener`) e um lambda Kotlin.
     * Implementa a interface gerada pelo UniFFI automaticamente.
     */
    private class ProgressBridge(
        private val onProgress: (Long, Long, String) -> Unit,
    ) : com.filemaster.core.ProgressListener {
        override fun onProgress(done: ULong, total: ULong, currentFile: String) {
            onProgress(done.toLong(), total.toLong(), currentFile)
        }

        override fun onFinished(success: Boolean, message: String) {
            // onFinished é sinal final; log simples é suficiente aqui
        }
    }
}
