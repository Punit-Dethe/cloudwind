use std::{
    fs::{self, File},
    io::BufWriter,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::Result;
use cloudwind_core::{decrypt, LibraryKey};

use crate::inbox::{Inbox, Receipt};

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Report {
    pub synced: Vec<PathBuf>,
    pub failed: Vec<(String, String)>,
}

/// Keep only the final path component so a hostile name can't escape `dir`.
fn safe_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    match base.trim() {
        "" | "." | ".." => "file".into(),
        b => b.to_string(),
    }
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

/// Pull everything pending: decrypt, verify the hash, move into place, then
/// write a receipt. A file is never visible in `library` until it verifies.
pub fn run(inbox: &dyn Inbox, key: &LibraryKey, library: &Path) -> Result<Report> {
    fs::create_dir_all(library)?;
    let mut report = Report::default();
    for id in inbox.pending()? {
        match pull_one(inbox, key, library, &id) {
            Ok(p) => report.synced.push(p),
            Err(e) => report.failed.push((id, format!("{e:#}"))),
        }
    }
    Ok(report)
}

fn pull_one(inbox: &dyn Inbox, key: &LibraryKey, library: &Path, id: &str) -> Result<PathBuf> {
    let tmp = library.join(format!(".cloudwind-{id}.part"));
    let result = (|| {
        let out = BufWriter::new(File::create(&tmp)?);
        Ok::<_, anyhow::Error>(decrypt(key, inbox.open(id)?, out)?)
    })();
    let meta = match result {
        Ok(m) => m,
        Err(e) => {
            let _ = fs::remove_file(&tmp);
            return Err(e);
        }
    };
    let dest = unique_path(library, &safe_name(&meta.name));
    fs::rename(&tmp, &dest)?;
    if let Some(t) = meta.modified {
        let when = UNIX_EPOCH + std::time::Duration::from_secs(t);
        if let Ok(f) = File::options().write(true).open(&dest) {
            let _ = f.set_modified(when);
        }
    }
    let synced_at = SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
    inbox.put_receipt(id, &Receipt { sha256: meta.sha256, synced_at })?;
    Ok(dest)
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

    #[test]
    fn syncs_verifies_and_is_idempotent() {
        let d = tempfile::tempdir().unwrap();
        let inbox = DirInbox { root: d.path().join("inbox") };
        let lib = d.path().join("photos");
        let key = LibraryKey::generate();
        let id = upload(&inbox, &key, "IMG_1.jpg", b"pixels");
        upload(&inbox, &key, "IMG_1.jpg", b"other pixels"); // same name, different file

        let r = run(&inbox, &key, &lib).unwrap();
        assert_eq!(r.synced.len(), 2);
        assert!(r.failed.is_empty());
        assert_eq!(fs::read(lib.join("IMG_1.jpg")).unwrap().len() + fs::read(lib.join("IMG_1 (1).jpg")).unwrap().len(), 6 + 12);
        assert!(inbox.root.join(format!("{id}.synced.json")).exists());

        let again = run(&inbox, &key, &lib).unwrap();
        assert_eq!(again, Report::default());
    }

    #[test]
    fn bad_file_leaves_nothing_behind() {
        let d = tempfile::tempdir().unwrap();
        let inbox = DirInbox { root: d.path().join("inbox") };
        let lib = d.path().join("photos");
        let id = upload(&inbox, &LibraryKey::generate(), "a.jpg", b"x");
        let r = run(&inbox, &LibraryKey::generate(), &lib).unwrap();
        assert_eq!(r.failed.len(), 1);
        assert!(fs::read_dir(&lib).unwrap().next().is_none());
        assert!(!inbox.root.join(format!("{id}.synced.json")).exists());
    }

    #[test]
    fn hostile_names_stay_inside_library() {
        assert_eq!(safe_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_name("..\\x.jpg"), "x.jpg");
        assert_eq!(safe_name(".."), "file");
    }
}
