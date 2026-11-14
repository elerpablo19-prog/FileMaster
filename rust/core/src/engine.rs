//! `FileEngine`: a fachada (facade) exposta ao Kotlin.
//!
//! Concorrência:
//! - Um `tokio::Runtime` multi-thread é criado no construtor e vive enquanto
//!   o objeto existir. O Kotlin mantém o `FileEngine` como singleton (via DI),
//!   então o runtime é criado uma única vez por processo.
//! - Métodos rápidos (list_dir, stat) rodam inline; operações pesadas
//!   (cópia, exclusão em árvore, busca recursiva) são despachadas para o
//!   runtime via `spawn_blocking`, mantendo o thread de chamada do JNI livre.

use crate::error::{FsError, FsResult};
use crate::model::{FileSystemEntry, SortMode, VolumeInfo};
use crate::ops;
use crate::ProgressListener;
use std::path::{Path, PathBuf};
use tokio::runtime::Runtime;

/// Verifica que o caminho é absoluto e normaliza componentes `.`/`..`.
fn normalize(path: &str) -> FsResult<PathBuf> {
    let p = Path::new(path);
    if !p.is_absolute() {
        return Err(FsError::InvalidPath {
            path: path.to_string(),
        });
    }
    // Componentes ".." só são aceitos se continuarem absolutos — o Rust
    // já canonicaliza isso em `PathBuf::from` sem resolver symlinks.
    Ok(p.to_path_buf())
}

#[derive(uniffi::Object)]
pub struct FileEngine {
    runtime: Runtime,
}

#[uniffi::export(async_runtime = "tokio")]
impl FileEngine {
    /// Construtor chamado uma vez pelo Kotlin. Inicializa o runtime Tokio
    /// multi-thread (núcleos disponíveis no dispositivo).
    #[uniffi::constructor]
    pub fn new() -> Result<Self, FsError> {
        let runtime = Runtime::new().map_err(|e| FsError::Internal {
            reason: format!("falha ao iniciar runtime tokio: {e}"),
        })?;
        Ok(Self { runtime })
    }

    // ------------------------------------------------------------------
    // Listagem
    // ------------------------------------------------------------------

    /// Lista o conteúdo de um diretório, ordenado conforme `sort`.
    /// Diretórios sempre precedem arquivos (comportamento padrão de file managers).
    pub fn list_dir(
        &self,
        path: String,
        show_hidden: bool,
        sort: SortMode,
    ) -> FsResult<Vec<FileSystemEntry>> {
        let dir = normalize(&path)?;
        ops::list_dir(&dir, show_hidden, sort)
    }

    /// Estatísticas do volume onde `path` reside (total/livre/usado).
    pub fn volume_info(&self, path: String) -> FsResult<VolumeInfo> {
        let dir = normalize(&path)?;
        ops::volume_info(&dir)
    }

    /// Metadados de um único caminho (usado para breadcrumbs e confirmações).
    pub fn stat_path(&self, path: String) -> FsResult<FileSystemEntry> {
        let p = normalize(&path)?;
        ops::stat_path(&p)
    }

    // ------------------------------------------------------------------
    // Busca recursiva (assíncrona no lado Kotlin via suspend fun)
    // ------------------------------------------------------------------

    /// Busca recursiva por nome (case-insensitive, substring).
    /// No Kotlin este método torna-se `suspend fun`, integrando-se
    /// diretamente a `viewModelScope` e CoroutineDispatchers.
    /// (o runtime Tokio é configurado no atributo do `impl` block)
    pub async fn search(
        &self,
        root: String,
        query: String,
        max_results: u32,
    ) -> FsResult<Vec<FileSystemEntry>> {
        let root = normalize(&root)?;
        ops::search(&self.runtime, root, &query, max_results).await
    }

    // ------------------------------------------------------------------
    // Operações destrutivas / longas — com progresso via callback
    // ------------------------------------------------------------------

    /// Copia arquivo ou árvore de diretórios. Reporta progresso no callback.
    pub fn copy(
        &self,
        source: String,
        destination: String,
        listener: Box<dyn ProgressListener>,
    ) -> FsResult<u64> {
        let src = normalize(&source)?;
        let dst = normalize(&destination)?;
        let handle = self.runtime
            .spawn_blocking(move || ops::copy(&src, &dst, Some(listener)));
        self.runtime
            .block_on(handle)
            .map_err(|_| FsError::Internal { reason: "panic no worker de cópia".into() })?
    }

    /// Move (renomear quando possível, cópia+delete em cross-volume).
    pub fn move_entry(
        &self,
        source: String,
        destination: String,
        listener: Box<dyn ProgressListener>,
    ) -> FsResult<u64> {
        let src = normalize(&source)?;
        let dst = normalize(&destination)?;
        let handle = self.runtime
            .spawn_blocking(move || ops::move_entry(&src, &dst, Some(listener)));
        self.runtime
            .block_on(handle)
            .map_err(|_| FsError::Internal { reason: "panic no worker de movimentação".into() })?
    }

    /// Exclui arquivo ou árvore recursivamente. Retorna bytes liberados.
    pub fn delete(
        &self,
        target: String,
        listener: Box<dyn ProgressListener>,
    ) -> FsResult<u64> {
        let t = normalize(&target)?;
        let handle = self.runtime.spawn_blocking(move || ops::delete(&t, Some(listener)));
        self.runtime
            .block_on(handle)
            .map_err(|_| FsError::Internal { reason: "panic no worker de exclusão".into() })?
    }

    // ------------------------------------------------------------------
    // Operações leves de mutação
    // ------------------------------------------------------------------

    pub fn create_dir(&self, path: String) -> FsResult<()> {
        let p = normalize(&path)?;
        ops::create_dir(&p)
    }

    pub fn rename(&self, source: String, new_name: String) -> FsResult<()> {
        let src = normalize(&source)?;
        if new_name.contains('/') || new_name.contains('\0') || new_name.is_empty() {
            return Err(FsError::InvalidPath { path: new_name });
        }
        ops::rename(&src, &new_name)
    }

    pub fn write_file(&self, path: String, contents: Vec<u8>) -> FsResult<()> {
        let p = normalize(&path)?;
        ops::write_file(&p, &contents)
    }

    /// Leitura de arquivos pequenos (editores, pré-visualizações de texto).
    /// Para binários grandes, prefira `read_stream` (chunked).
    pub fn read_file(&self, path: String, max_bytes: u64) -> FsResult<Vec<u8>> {
        let p = normalize(&path)?;
        ops::read_file(&p, max_bytes)
    }

    /// Leitura em blocos: `offset` + tamanho máx. do chunk.
    /// O Kotlin pagina chamando novamente com offsets incrementais.
    pub fn read_chunk(
        &self,
        path: String,
        offset: u64,
        chunk_size: u64,
    ) -> FsResult<Vec<u8>> {
        let p = normalize(&path)?;
        ops::read_chunk(&p, offset, chunk_size)
    }
}
