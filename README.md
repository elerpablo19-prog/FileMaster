# FileMaster — Gerenciador de Arquivos Moderno (Rust Core + Jetpack Compose)

Arquitetura: **Core Rust** (I/O de sistema de arquivos) exposto via **UniFFI**, consumido por
um app **Kotlin + Jetpack Compose (Material 3)** com MVVM (StateFlow + Coroutines).

```
┌────────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                    │
│                                                            │
│  UI Layer (Compose M3)          ViewModel (StateFlow)      │
│  ├─ FileListScreen              └─ FileListViewModel       │
│  ├─ NavGraph (Compose Nav)             │ collectAsState()   │
│  └─ Theme (M3 + dynamic color)         │                   │
│                                        ▼                   │
│  Domain: FileSystemRepository (Dispatchers.IO wrapper)     │
│                                        │                   │
├──────────────────────── JNI / JNA (UniFFI) ────────────────┤
│                                        │                   │
│  Core Rust (libfilemaster.so)          ▼                   │
│  ├─ FileEngine   (facade uniffi::Object, runtime Tokio)    │
│  ├─ ops.rs       (list/copy/move/delete/search chunked)    │
│  ├─ model.rs     (FileSystemEntry, VolumeInfo, SortMode)   │
│  └─ error.rs     (FsError -> exceção tipada no Kotlin)     │
└────────────────────────────────────────────────────────────┘
```

## Estrutura de pastas

```
FileMaster/
├── README.md
├── rust/
│   └── core/                      # Crate Rust (cdylib + lib)
│       ├── Cargo.toml
│       ├── uniffi.toml            # package_name dos bindings Kotlin
│       └── src/
│           ├── lib.rs             # scaffolding + ProgressListener (callback)
│           ├── engine.rs          # FileEngine: API exposta ao Kotlin
│           ├── ops.rs             # implementação pura de I/O (testável)
│           ├── model.rs           # Records/Enums UniFFI
│           └── error.rs           # FsError (thiserror + uniffi::Error)
├── app/
│   ├── build.gradle.kts           # tasks cargo-ndk + uniffi-bindgen
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml    # MAES (API 30+) + legacy (API 26-29)
│       ├── res/values/{strings,themes}.xml
│       └── kotlin/com/filemaster/
│           ├── MainActivity.kt                    # gate de permissões + Compose root
│           ├── FileMasterApplication.kt
│           ├── core/                              # ← bindings gerados pelo UniFFI
│           ├── di/AppContainer.kt
│           ├── domain/FileSystemRepository.kt
│           ├── navigation/FileMasterNavGraph.kt
│           ├── permissions/StoragePermissionHandler.kt
│           └── ui/
│               ├── theme/{Theme,Color,Type}.kt
│               └── screens/files/{FileListScreen,FileListViewModel}.kt
└── gradle/libs.versions.toml
```

## Contratos de interface (resumo)

**Rust → Kotlin (UniFFI proc-macro):**

| Rust (FileEngine) | Kotlin gerado | Semântica |
|---|---|---|
| `list_dir(path, show_hidden, sort)` | `fun listDir(...): List<FileSystemEntry>` | síncrono, executado no Dispatchers.IO |
| `search(root, query, max)` | `suspend fun search(...)` | `async_runtime = "tokio"` → suspend nativo |
| `copy/move_entry/delete(target, listener)` | `fun ... (listener: ProgressListener)` | `spawn_blocking` + callbacks de progresso |
| `read_chunk(path, offset, size)` | `fun readChunk(...): List<Byte>` | streaming de binários grandes |
| `FsError` (14 variantes) | `FsException` tipada | try/catch idiomático |

**Kotlin interno:** `Repository → ViewModel (StateFlow<FileListUiState>) → Composable (collectAsState)`.

## Configuração do ambiente (cross-compilation)

### 1. Toolchain Rust + targets Android

```bash
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk uniffi-bindgen
```

### 2. Android NDK

```bash
# Instale o NDK (27+) via Android Studio ou sdkmanager:
sdkmanager "ndk;27.0.12077973"
export ANDROID_HOME=~/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
export PATH=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH
```

> O `cargo-ndk` descobre o linker LLVM do NDK automaticamente a partir de `ANDROID_NDK_HOME`.

### 3. Build do core (manual, para validar)

```bash
cd rust/core
# Debug para os 3 ABIs, copiando .so para o jniLibs do app:
cargo ndk -o ../../app/src/main/jniLibs -t arm64-v8a -t armeabi-v7a -t x86_64 build
# Gerar bindings Kotlin (usa o cdylib de host):
cargo build && uniffi-bindgen generate \
  --library target/debug/libfilemaster.so \
  --language kotlin \
  --out-dir ../../app/src/main/kotlin \
  --config uniffi.toml
```

### 4. Build completo do app

```bash
./gradlew assembleDebug   # o task preBuild já encadeia Rust + bindings + Kotlin
```

## Decisões de arquitetura

1. **I/O 100% no Rust** — listagem, cópia (chunks de 256 KiB), exclusão em árvore
   (`contents_first`) e busca (`walkdir`) rodam em `spawn_blocking` no runtime Tokio
   multi-thread; a thread JNI nunca bloqueia.
2. **Progresso honesto** — a cópia pré-computa o total de bytes da árvore e reporta
   progresso incremental via callback `ProgressListener` (ponte UniFFI), renderizado
   na UI como `LinearProgressIndicator`.
3. **Assíncrono de ponta a ponta** — `search` é marcado com
   `#[uniffi::export(async_runtime = "tokio")]` e vira `suspend fun` no Kotlin,
   integrando-se ao `viewModelScope` sem callbacks manuais.
4. **MVVM sem framework de DI** — `AppContainer` manual: o `FileEngine` é singleton
   (um runtime Tokio por processo), ViewModels recebem o repository via factory.
5. **Permissões em camadas** — MAES (All files access) para API 30+ com fallback
   para a permissão runtime clássica em API 26-29; o kernel filtra por UID do
   processo, portanto o core Rust herda automaticamente o acesso concedido.
6. **Virtualização leve** — `LazyColumn`/`LazyVerticalGrid` com `key = { it.path }`
   garante O(n) em diffs e reuso de composables; itens são data classes imutáveis,
   permitindo pular recomposição por structural equality.
