//! Tipos de dados expostos ao Kotlin via UniFFI.
//! Todos os tipos derivam `uniffi::*` e são serializados automaticamente
//! em data classes / sealed classes Kotlin.

/// Tipo de entrada do sistema de arquivos.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum EntryKind {
    File,
    Directory,
    Symlink,
}

/// Uma entrada listada de um diretório.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FileSystemEntry {
    /// Nome simples do arquivo/pasta (ex.: "Downloads").
    pub name: String,
    /// Caminho absoluto completo.
    pub path: String,
    pub kind: EntryKind,
    /// Tamanho em bytes (0 para diretórios).
    pub size_bytes: u64,
    /// Última modificação em milissegundos desde o epoch.
    pub modified_ms: i64,
    /// Nome começa com "." (exibição condicional na UI).
    pub is_hidden: bool,
    /// Permissões POSIX em octal (ex.: 0o755 -> 493). Exibido como "755" na UI.
    pub permissions_octal: u16,
}

/// Informações de volume/armazenamento.
#[derive(Debug, Clone, uniffi::Record)]
pub struct VolumeInfo {
    pub total_bytes: u64,
    pub free_bytes: u64,
    /// Bytes usados = total - free (calculado na UI para a barra de progresso).
    pub used_bytes: u64,
}

/// Modos de ordenação disponíveis na UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SortMode {
    NameAsc,
    NameDesc,
    SizeDesc,
    ModifiedDesc,
    ModifiedAsc,
}
