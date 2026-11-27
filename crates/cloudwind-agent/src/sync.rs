use std::{
    fs::{self, File},
    io::BufWriter,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::Result;
use cloudwind_core::{decrypt, LibraryKey};

use crate::{
    inbox::{Inbox, Receipt},
    state::State,
};

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub synced: Vec<PathBuf>,
    /// Already had these bytes (same SHA-256): acknowledged without storing a second copy.
    pub duplicates: usize,
    /// Skipped this run because an earlier attempt failed and its backoff hasn't elapsed.
    pub backed_off: usize,
    pub failed: Vec<(String, String)>,
}

const MAX_NAME_BYTES: usize = 200;
const RESERVED: [&str; 22] = [
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1",
    "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// A file name that is safe on Windows, macOS and Linux and can't escape the library folder.
pub fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base
        .chars()
        .map(|c| if c.is_control() || "<>:\"|?*".contains(c) { '_' } else { c })
        .collect();
    // Windows silently strips trailing dots and spaces, which would make `a.jpg.` collide with `a.jpg`.
    let mut s = cleaned.trim().trim_end_matches(['.', ' ']).to_string();
    if s.is_empty() {
        return "file".into();
    }
    let stem = s.split('.').next().unwrap_or("").to_ascii_uppercase();
    if RESERVED.contains(&stem.as_str()) {
        s = format!("_{s}");
    }
    if s.len() > MAX_NAME_BYTES {
        let (stem, ext) = match s.rsplit_once('.') {
            Some((st, e)) if !st.is_empty() && e.len() <= 16 => (st.to_string(), format!(".{e}")),
            _ => (s.clone(), String::new()),
        };
        let mut cut = MAX_NAME_BYTES.saturating_sub(ext.len());
        while !stem.is_char_boundary(cut) {
            cut -= 1;
        }
        s = format!("{}{}", &stem[..cut], ext);
    }
    s
}

/// Pick a path in `dir` that doesn't exist yet: `a.jpg`, `a (1).jpg`, ...
fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = match name.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s, format!(".{e}")),
        _ => (name, String::new()),
    };
    (1..)
        .map(|i| dir.join(format!("{stem} ({i}){ext}")))
        .find(|p| !p.exists())
        .unwrap()
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// Pull everything pending: download (resumable), decrypt, verify the hash, move into place,
/// then write a receipt. A file is never visible in `library` until it verifies.
pub fn run(inbox: &dyn Inbox, key: &LibraryKey, library: &Path, state: &State) -> Result<Report> {
    fs::create_dir_all(library)?;
    let mut report = Report::default();
    for id in inbox.pending()? {
        if !state.due(&id, now()) {
            report.backed_off += 1;
            continue;
        }
        match pull_one(inbox, key, library, state, &id) {
            Ok(Some(p)) => {
                state.clear_failure(&id);
                report.synced.push(p);
            }
            Ok(None) => {
                state.clear_failure(&id);
                report.duplicates += 1;
            }
            Err(e) => {
                state.record_failure(&id, now());
                report.failed.push((id, format!("{e:#}")));
            }
        }
    }
    Ok(report)
}

/// Move a verified file into the library. Both routes (cloud pull and direct LAN) end here, so the
/// hash claim makes sure exactly one copy is stored even if both deliver the same bytes at once.
/// `Ok(None)` means it was a duplicate and `tmp` was discarded.
pub fn place(library: &Path, state: &State, tmp: &Path, name: &str, modified: Option<u64>, sha256: &str) -> Result<Option<PathBuf>> {
    // Choosing a free name and moving into it must be one step: otherwise a cloud pull and a direct
    // arrival with the same file name could pick the same path and one would replace the other.
    static PLACING: std::sync::Mutex<()> = std::sync::Mutex::new(());
    let _guard = PLACING.lock().unwrap_or_else(|e| e.into_inner());
    if !state.claim(sha256)? {
        let _ = fs::remove_file(tmp);
        return Ok(None);
    }
    let dest = unique_path(library, &safe_name(name));
    if let Err(e) = move_file(tmp, &dest, sha256) {
        state.unclaim(sha256);
        return Err(e);
    }
    if let Some(t) = modified {
        if let Ok(f) = File::options().write(true).open(&dest) {
            let _ = f.set_modified(UNIX_EPOCH + std::time::Duration::from_secs(t));
        }
    }
    Ok(Some(dest))
}

/// Rename when possible (same drive); otherwise copy to a hidden name on the library's drive, then rename,
/// so a photo app never sees a half-copied file.
fn move_file(from: &Path, to: &Path, sha256: &str) -> Result<()> {
    if fs::rename(from, to).is_ok() {
        return Ok(());
    }
    let staging = to.with_file_name(format!(".cloudwind-incoming-{sha256}.tmp"));
    fs::copy(from, &staging)?;
    if let Err(e) = fs::rename(&staging, to) {
        let _ = fs::remove_file(&staging);
        return Err(e.into());
    }
    let _ = fs::remove_file(from);
    Ok(())
}

/// `Ok(Some(path))` stored a new file, `Ok(None)` it was a duplicate (acknowledged, not stored again).
fn pull_one(inbox: &dyn Inbox, key: &LibraryKey, library: &Path, state: &State, id: &str) -> Result<Option<PathBuf>> {
    let dl = state.tmp_path(id);
    inbox.fetch(id, &dl)?;

    let tmp = state.tmp_path(id).with_extension("plain");
    let decrypted = (|| {
        let out = BufWriter::new(File::create(&tmp)?);
        Ok::<_, anyhow::Error>(decrypt(key, File::open(&dl)?, out)?)
    })();
    let meta = match decrypted {
        Ok(m) => m,
        Err(e) => {
            let _ = fs::remove_file(&tmp);
            let _ = fs::remove_file(&dl); // corrupt or wrong key: next attempt downloads fresh
            return Err(e);
        }
    };

    let stored = place(library, state, &tmp, &meta.name, meta.modified, &meta.sha256)?;
    let _ = fs::remove_file(&dl);
    inbox.put_receipt(id, &Receipt { sha256: meta.sha256, synced_at: now() })?;
    Ok(stored)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inbox::DirInbox;
    use cloudwind_core::encrypt;

    fn upload(inbox: &DirInbox, key: &LibraryKey, name: &str, data: &[u8]) -> String {
        let mut ct = Vec::new();
        encrypt(key, name, Some(1_700_000_000), || Ok(data), &mut ct).unwrap();
        inbox.put(&ct).unwrap()
    }

    struct Env {
        _d: tempfile::TempDir,
        inbox: DirInbox,
        lib: PathBuf,
        state: State,
    }
    fn env() -> Env {
        let d = tempfile::tempdir().unwrap();
        let inbox = DirInbox { root: d.path().join("inbox") };
        let state = State::load(&d.path().join("state")).unwrap();
        Env { lib: d.path().join("photos"), inbox, state, _d: d }
    }

    #[test]
    fn syncs_verifies_and_is_idempotent() {
        let e = env();
        let key = LibraryKey::generate();
        let id = upload(&e.inbox, &key, "IMG_1.jpg", b"pixels");
        upload(&e.inbox, &key, "IMG_1.jpg", b"other pixels"); // same name, different file

        let r = run(&e.inbox, &key, &e.lib, &e.state).unwrap();
        assert_eq!(r.synced.len(), 2);
        assert!(r.failed.is_empty());
        let total = fs::read(e.lib.join("IMG_1.jpg")).unwrap().len() + fs::read(e.lib.join("IMG_1 (1).jpg")).unwrap().len();
        assert_eq!(total, 6 + 12);
        assert!(e.inbox.root.join(format!("{id}.synced.json")).exists());

        assert_eq!(run(&e.inbox, &key, &e.lib, &e.state).unwrap(), Report::default());
    }

    #[test]
    fn same_bytes_uploaded_twice_are_stored_once_but_both_acknowledged() {
        let e = env();
        let key = LibraryKey::generate();
        // A re-paired phone or an overlapping "back up older photos" uploads the same photo again.
        let a = upload(&e.inbox, &key, "IMG_7.jpg", b"same pixels");
        let b = upload(&e.inbox, &key, "IMG_7.jpg", b"same pixels");
        let r = run(&e.inbox, &key, &e.lib, &e.state).unwrap();
        assert_eq!((r.synced.len(), r.duplicates), (1, 1));
        let stored: Vec<_> = fs::read_dir(&e.lib).unwrap().collect();
        assert_eq!(stored.len(), 1);
        for id in [a, b] {
            assert!(e.inbox.root.join(format!("{id}.synced.json")).exists(), "both are confirmed so the phone can delete");
        }
        // Remembered across runs, too.
        let c = upload(&e.inbox, &key, "IMG_7 copy.jpg", b"same pixels");
        let again = State::load(&e._d.path().join("state")).unwrap();
        let r = run(&e.inbox, &key, &e.lib, &again).unwrap();
        assert_eq!((r.synced.len(), r.duplicates), (0, 1));
        assert!(e.inbox.root.join(format!("{c}.synced.json")).exists());
    }

    #[test]
    fn bad_file_leaves_nothing_behind_and_backs_off() {
        let e = env();
        let id = upload(&e.inbox, &LibraryKey::generate(), "a.jpg", b"x");
        let r = run(&e.inbox, &LibraryKey::generate(), &e.lib, &e.state).unwrap();
        assert_eq!(r.failed.len(), 1);
        assert!(fs::read_dir(&e.lib).unwrap().next().is_none());
        assert!(!e.inbox.root.join(format!("{id}.synced.json")).exists());
        assert!(!e.state.tmp_path(&id).exists(), "corrupt download is discarded");

        // Immediately re-running must not hammer the same object.
        let r = run(&e.inbox, &LibraryKey::generate(), &e.lib, &e.state).unwrap();
        assert_eq!((r.failed.len(), r.backed_off), (0, 1));
    }

    #[test]
    fn names_are_safe_on_every_platform() {
        assert_eq!(safe_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_name("..\\x.jpg"), "x.jpg");
        assert_eq!(safe_name(".."), "file");
        assert_eq!(safe_name(""), "file");
        assert_eq!(safe_name("a:b*c?.jpg"), "a_b_c_.jpg");
        assert_eq!(safe_name("photo.jpg. "), "photo.jpg");
        assert_eq!(safe_name("CON.jpg"), "_CON.jpg");
        assert_eq!(safe_name("nul"), "_nul");
        assert_eq!(safe_name("Concert.jpg"), "Concert.jpg");
        assert_eq!(safe_name(".hidden"), ".hidden");
        let long = format!("{}.mp4", "é".repeat(300));
        let s = safe_name(&long);
        assert!(s.len() <= MAX_NAME_BYTES && s.ends_with(".mp4"));
        assert!(std::str::from_utf8(s.as_bytes()).is_ok());
    }
}
