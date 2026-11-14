//! Ponto de entrada da crate. Gera o scaffolding UniFFI que permite ao
//! `uniffi-bindgen` produzir os bindings Kotlin a partir das macros
//! (`uniffi::Record`, `uniffi::Enum`, `uniffi::Object`, `uniffi::export`).
//!
//! Não há UDL: toda a interface é declarada via proc-macros nos módulos.

mod error;
mod engine;
mod model;
mod ops;

pub use engine::FileEngine;
pub use error::FsError;
pub use model::{EntryKind, FileSystemEntry, SortMode, VolumeInfo};

/// Callback de progresso invocado do Rust para o Kotlin durante operações
/// longas (cópia, movimentação, exclusão, busca). O Kotlin implementa esta
/// interface e o UniFFI faz a ponte JNI automaticamente.
#[uniffi::export(callback_interface)]
pub trait ProgressListener: Send + Sync {
    /// Chamado periodicamente. Para cópia/movimentação, `done/total` são bytes;
    /// para exclusão em árvore, são itens processados.
    fn on_progress(&self, done: u64, total: u64, current_file: String);
    /// Chamado uma única vez ao final, com sucesso ou falha.
    fn on_finished(&self, success: bool, message: String);
}

// Nome do scaffolding deve bater com o nome do cdylib (`libfilemaster.so`)
// para que o uniffi-bindgen resolva os símbolos na geração dos bindings.
uniffi::setup_scaffolding!("filemaster");
