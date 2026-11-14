//! Implementação pura das operações de I/O, sem conhecimento de UniFFI.
//! Mantida separada para facilitar testes unitários com std::tempfile.

use crate::error::{FsError, FsResult};
use crate::model::{EntryKind, FileSystemEntry, SortMode, VolumeInfo};
use std::fs;
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use walkdir::WalkDir;

use crate::ProgressListener;

// ======================================================================
// Listagem
// ======================================================================

fn entry_kind(md: &fs::Metadata) -> EntryKind {
    let ft = md.file_type();
    if ft.is_dir() {
        EntryKind::Directory
    } else if ft.is_symlink() {
        EntryKind::Symlink
    } else {
        EntryKind::File
    }
}

pub fn stat_path(path: &Path) -> FsResult<FileSystemEntry> {
    let md = fs::metadata(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    let name = path
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| "/".into());
    let is_hidden = name.starts_with('.');
    let permissions = ((md.permissions().mode() & 0o777) as u16);
    Ok(FileSystemEntry {
        name,
        path: path.display().to_string(),
        kind: entry_kind(&md),
        size_bytes: md.len(),
        modified_ms: md
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0),
        is_hidden,
        permissions_octal: permissions,
    })
}

pub fn list_dir(dir: &Path, show_hidden: bool, sort: SortMode) -> FsResult<Vec<FileSystemEntry>> {
    let md = fs::metadata(dir).map_err(|e| FsError::from_io(&e, &PathBuf::from(dir)))?;
    if !md.is_dir() {
        return Err(FsError::InvalidPathType {
            path: dir.display().to_string(),
        });
    }

    let read = fs::read_dir(dir).map_err(|e| FsError::from_io(&e, &PathBuf::from(dir)))?;
    let mut entries = Vec::new();
    for item in read {
        let item = item.map_err(|e| FsError::from_io(&e, &PathBuf::from(dir)))?;
        let path = item.path();
        let name = item.file_name().to_string_lossy().into_owned();
        if !show_hidden && name.starts_with('.') {
            continue;
        }
        // fs::metadata segue symlinks; em symlinks quebrados usamos symlink_metadata
        // para não abortar a listagem inteira.
        let md = match fs::metadata(&path) {
            Ok(md) => md,
            Err(_) => fs::symlink_metadata(&path).map_err(|e| FsError::from_io(&e, &path))?,
        };
        let permissions = ((md.permissions().mode() & 0o777) as u16);
        let is_hidden = name.starts_with('.');
        entries.push(FileSystemEntry {
            path: path.display().to_string(),
            name,
            kind: entry_kind(&md),
            size_bytes: md.len(),
            modified_ms: md
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0),
            is_hidden,
            permissions_octal: permissions,
        });
    }

    // Diretórios primeiro; dentro do grupo, aplica o sort escolhido.
    entries.sort_by(|a, b| {
        let dir_a = a.kind == EntryKind::Directory;
        let dir_b = b.kind == EntryKind::Directory;
        dir_b.cmp(&dir_a).then_with(|| match sort {
            SortMode::NameAsc => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
            SortMode::NameDesc => b.name.to_lowercase().cmp(&a.name.to_lowercase()),
            SortMode::SizeDesc => b.size_bytes.cmp(&a.size_bytes),
            SortMode::ModifiedDesc => b.modified_ms.cmp(&a.modified_ms),
            SortMode::ModifiedAsc => a.modified_ms.cmp(&b.modified_ms),
        })
    });

    Ok(entries)
}

// ======================================================================
// Volume
// ======================================================================

pub fn volume_info(path: &Path) -> FsResult<VolumeInfo> {
    #[cfg(unix)]
    {
        let vfs = nix::sys::statvfs::statvfs(path)
            .map_err(|e| FsError::Internal { reason: e.to_string() })?;
        let total = vfs.blocks() as u64 * vfs.fragment_size() as u64;
        let free = vfs.blocks_available() as u64 * vfs.fragment_size() as u64;
        Ok(VolumeInfo {
            total_bytes: total,
            free_bytes: free,
            used_bytes: total.saturating_sub(free),
        })
    }
    #[cfg(not(unix))]
    {
        Ok(VolumeInfo { total_bytes: 0, free_bytes: 0, used_bytes: 0 })
    }
}

// ======================================================================
// Busca
// ======================================================================

pub async fn search(
    runtime: &tokio::runtime::Runtime,
    root: PathBuf,
    query: &str,
    max_results: u32,
) -> FsResult<Vec<FileSystemEntry>> {
    if !fs::metadata(&root)
        .map_err(|e| FsError::from_io(&e, &root))?
        .is_dir()
    {
        return Err(FsError::InvalidPathType {
            path: root.display().to_string(),
        });
    }
    let needle = query.to_lowercase();
    let limit = max_results.max(1) as usize;

    // spawn_blocking: a travessia é síncrona e CPU/IO-bound; o runtime Tokio
    // não deve ter seu event-loop bloqueado por ela.
    runtime
        .spawn_blocking(move || {
            let mut hits = Vec::with_capacity(limit);
            for entry in WalkDir::new(&root)
                .follow_links(false)
                .into_iter()
                .filter_map(|e| e.ok())
            {
                if hits.len() >= limit {
                    break;
                }
                let name = entry.file_name().to_string_lossy();
                if name.to_lowercase().contains(&needle) {
                    if let Ok(md) = entry.metadata() {
                        let permissions = ((md.permissions().mode() & 0o777) as u16);
                        let is_hidden = name.starts_with('.');
                        hits.push(FileSystemEntry {
                            path: entry.path().display().to_string(),
                            name: name.into_owned(),
                            kind: entry_kind(&md),
                            size_bytes: md.len(),
                            modified_ms: md
                                .modified()
                                .ok()
                                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                                .map(|d| d.as_millis() as i64)
                                .unwrap_or(0),
                            is_hidden,
                            permissions_octal: permissions,
                        });
                    }
                }
            }
            Ok(hits)
        })
        .await
        .map_err(|_| FsError::Internal { reason: "panic na busca".into() })?
}

// ======================================================================
// Cópia / Movimentação / Exclusão (com progresso)
// ======================================================================

fn notify(listener: Option<&Box<dyn ProgressListener>>, done: u64, total: u64, file: &str) {
    if let Some(l) = listener {
        l.on_progress(done, total, file.to_string());
    }
}

/// Cópia recursiva. Estratégia:
/// 1. Pré-computa o total de bytes da árvore (para progresso honesto).
/// 2. Copia arquivo por arquivo em chunks de 256 KiB (baixo uso de memória).
fn copy_tree(src: &Path, dst: &Path, listener: Option<Box<dyn ProgressListener>>) -> FsResult<u64> {
    let src_md = fs::metadata(src).map_err(|e| FsError::from_io(&e, &PathBuf::from(src)))?;

    // Caso arquivo único
    if src_md.is_file() {
        if dst.exists() {
            return Err(FsError::AlreadyExists {
                path: dst.display().to_string(),
            });
        }
        let progress_total = src_md.len();
        copy_file_chunked(src, dst, progress_total, 0, listener.as_ref())?;
        if let Some(l) = &listener {
            l.on_finished(true, format!("copiado: {}", dst.display()));
        }
        return Ok(progress_total);
    }

    // Caso diretório: pré-cálculo do total
    let mut total: u64 = 0;
    for e in WalkDir::new(src).into_iter().filter_map(|e| e.ok()) {
        if let Ok(md) = e.metadata() {
            if md.is_file() {
                total += md.len();
            }
        }
    }

    let mut done: u64 = 0;
    let dst_root = dst.join(src.file_name().unwrap_or_default());
    fs::create_dir_all(&dst_root).map_err(|e| FsError::from_io(&e, &dst_root))?;

    for entry in WalkDir::new(src).into_iter() {
        let entry = entry.map_err(|e| FsError::Io {
            path: src.display().to_string(),
            reason: e.to_string(),
        })?;
        let rel = entry.path().strip_prefix(src).unwrap_or(entry.path());
        let target = dst_root.join(rel);
        let md = entry
            .metadata()
            .map_err(|e| FsError::Io { path: entry.path().display().to_string(), reason: e.to_string() })?;
        if md.is_dir() {
            fs::create_dir_all(&target).map_err(|e| FsError::from_io(&e, &target))?;
        } else if md.is_file() {
            done += copy_file_chunked(entry.path(), &target, total, done, listener.as_ref())?;
        }
    }

    if let Some(l) = &listener {
        l.on_finished(true, format!("copiado: {}", dst_root.display()));
    }
    Ok(done)
}

fn copy_file_chunked(
    src: &Path,
    dst: &Path,
    progress_total: u64,
    already_done: u64,
    listener: Option<&Box<dyn ProgressListener>>,
) -> FsResult<u64> {
    let mut reader = fs::File::open(src).map_err(|e| FsError::from_io(&e, &PathBuf::from(src)))?;
    let mut writer = fs::File::create(dst).map_err(|e| FsError::from_io(&e, &PathBuf::from(dst)))?;

    const CHUNK: usize = 256 * 1024;
    let mut buf = vec![0u8; CHUNK];
    let mut done = 0u64;

    loop {
        let n = reader
            .read(&mut buf)
            .map_err(|e| FsError::from_io(&e, &PathBuf::from(src)))?;
        if n == 0 {
            break;
        }
        writer
            .write_all(&buf[..n])
            .map_err(|e| FsError::from_io(&e, &PathBuf::from(dst)))?;
        done += n as u64;
        notify(listener, already_done + done, progress_total, &src.display().to_string());
    }
    writer
        .sync_all()
        .map_err(|e| FsError::from_io(&e, &PathBuf::from(dst)))?;
    Ok(done)
}

pub fn copy(
    src: &Path,
    dst: &Path,
    listener: Option<Box<dyn ProgressListener>>,
) -> FsResult<u64> {
    copy_tree(src, dst, listener)
}

pub fn move_entry(
    src: &Path,
    dst: &Path,
    listener: Option<Box<dyn ProgressListener>>,
) -> FsResult<u64> {
    if dst.exists() {
        return Err(FsError::AlreadyExists {
            path: dst.display().to_string(),
        });
    }
    // rename(2) é O(1) e atômico — sempre preferível quando funciona
    // (mesmo filesystem). Se falhar, degradamos para copiar + excluir.
    match fs::rename(src, dst) {
        Ok(()) => {
            let size = fs::metadata(dst)
                .map(|m| m.len())
                .unwrap_or(0);
            if let Some(l) = &listener {
                l.on_finished(true, format!("movido: {}", dst.display()));
            }
            Ok(size)
        }
        Err(_) => {
            let copied = copy_tree(src, dst, listener)?;
            delete(src, None)?;
            Ok(copied)
        }
    }
}

pub fn delete(target: &Path, listener: Option<Box<dyn ProgressListener>>) -> FsResult<u64> {
    let md = fs::metadata(target).map_err(|e| FsError::from_io(&e, &PathBuf::from(target)))?;

    if md.is_file() || md.file_type().is_symlink() {
        let size = md.len();
        fs::remove_file(target).map_err(|e| FsError::from_io(&e, &PathBuf::from(target)))?;
        if let Some(l) = &listener {
            l.on_finished(true, format!("excluído: {}", target.display()));
        }
        return Ok(size);
    }

    // Diretório: coleta reversa (arquivos primeiro, diretórios depois) —
    // remove_dir exige que o diretório esteja vazio.
    let mut freed: u64 = 0;
    let mut paths: Vec<PathBuf> = WalkDir::new(target)
        .contents_first(true)
        .into_iter()
        .filter_map(|e| e.ok())
        .map(|e| e.into_path())
        .collect();

    let total_items = paths.len() as u64;
    let mut processed = 0u64;
    for p in paths.drain(..) {
        let md = fs::symlink_metadata(&p).map_err(|e| FsError::from_io(&e, &p))?;
        if md.is_dir() {
            fs::remove_dir(&p).map_err(|e| FsError::from_io(&e, &p))?;
        } else {
            freed += md.len();
            fs::remove_file(&p).map_err(|e| FsError::from_io(&e, &p))?;
        }
        processed += 1;
        notify(listener.as_ref(), processed, total_items, &p.display().to_string());
    }

    if let Some(l) = &listener {
        l.on_finished(true, format!("excluído: {}", target.display()));
    }
    Ok(freed)
}

// ======================================================================
// Mutação leve
// ======================================================================

pub fn create_dir(path: &Path) -> FsResult<()> {
    if path.exists() {
        return Err(FsError::AlreadyExists {
            path: path.display().to_string(),
        });
    }
    fs::create_dir_all(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))
}

pub fn rename(src: &Path, new_name: &str) -> FsResult<()> {
    let parent = src
        .parent()
        .ok_or_else(|| FsError::InvalidPath { path: src.display().to_string() })?;
    let dst = parent.join(new_name);
    if dst.exists() {
        return Err(FsError::AlreadyExists {
            path: dst.display().to_string(),
        });
    }
    fs::rename(src, &dst).map_err(|e| FsError::from_io(&e, &PathBuf::from(src)))
}

pub fn write_file(path: &Path, contents: &[u8]) -> FsResult<()> {
    let mut f = fs::File::create(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    f.write_all(contents).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    f.sync_all().map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))
}

pub fn read_file(path: &Path, max_bytes: u64) -> FsResult<Vec<u8>> {
    let md = fs::metadata(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    if md.len() > max_bytes {
        return Err(FsError::Internal {
            reason: format!(
                "arquivo maior que o limite: {} > {} bytes",
                md.len(),
                max_bytes
            ),
        });
    }
    fs::read(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))
}

pub fn read_chunk(path: &Path, offset: u64, chunk_size: u64) -> FsResult<Vec<u8>> {
    let mut f = fs::File::open(path).map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    f.seek(SeekFrom::Start(offset))
        .map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
    let mut buf = vec![0u8; chunk_size as usize];
    let mut read_total = 0usize;
    loop {
        let n = f
            .read(&mut buf[read_total..])
            .map_err(|e| FsError::from_io(&e, &PathBuf::from(path)))?;
        if n == 0 {
            break;
        }
        read_total += n;
        if read_total == buf.len() {
            break;
        }
    }
    buf.truncate(read_total);
    Ok(buf)
}
