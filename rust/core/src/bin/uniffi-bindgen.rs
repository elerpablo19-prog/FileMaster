//! Binário `uniffi-bindgen` embutido na crate — abordagem recomendada pelo
//! UniFFI para garantir que a versão do bindgen seja sempre a mesma da crate.
//!
//! Uso: cargo run --bin uniffi-bindgen -- generate --library <lib> ...

fn main() {
    uniffi::uniffi_bindgen_main()
}
