//! Erros do Core. UniFFI converte este enum em uma exceção/erro tipado no Kotlin
//! (`FsException`), permitindo `try/catch` idiomático no lado Android.

use std::path::PathBuf;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FsError {
    /// Permissão negada pelo sistema de arquivos (EBUSY/EACCES) ou pelo Scoped Storage.
    #[error("permissão negada: {path}")]
    PermissionDenied { path: String },

    /// Caminho inexistente.
    #[error("caminho não encontrado: {path}")]
    NotFound { path: String },

    /// O caminho existe, mas não é um diretório quando era exigido (ou vice-versa).
    #[error("tipo inválido para o caminho: {path}")]
    InvalidPathType { path: String },

    /// Destino já existente em operação de cópia/movimentação/criação.
    #[error("destino já existe: {path}")]
    AlreadyExists { path: String },

    /// O I/O de disco falhou (leitura, escrita, sync).
    #[error("erro de I/O em '{path}': {reason}")]
    Io { path: String, reason: String },

    /// Caminho malformado ou fora da sandbox permitida.
    #[error("caminho inválido: {path}")]
    InvalidPath { path: String },

    /// Operação abortada (ex.: limite de resultados da busca atingido é um caso
    /// de sucesso, mas cancelamento por callback é reportado aqui).
    #[error("operação cancelada")]
    Cancelled,

    /// Erro inesperado com contexto para debugging remoto.
    #[error("erro interno: {reason}")]
    Internal { reason: String },
}

impl FsError {
    /// Converte um `std::io::Error` contextualizado em `FsError` com melhor semântica.
    pub(crate) fn from_io(err: &std::io::Error, path: &PathBuf) -> Self {
        match err.kind() {
            std::io::ErrorKind::PermissionDenied => FsError::PermissionDenied {
                path: path.display().to_string(),
            },
            std::io::ErrorKind::NotFound => FsError::NotFound {
                path: path.display().to_string(),
            },
            std::io::ErrorKind::AlreadyExists => FsError::AlreadyExists {
                path: path.display().to_string(),
            },
            _ => FsError::Io {
                path: path.display().to_string(),
                reason: err.to_string(),
            },
        }
    }
}

pub(crate) type FsResult<T> = Result<T, FsError>;
