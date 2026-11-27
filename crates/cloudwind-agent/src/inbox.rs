use std::{
    fs::{self, File},
    io::Read,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};

/// Proof that the PC has the file; the phone reads this to show "safe to delete".
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Receipt {
    pub sha256: String,
    pub synced_at: u64,
}

/// Where encrypted objects wait. A local folder for testing, or the Cloudflare Worker.
pub trait Inbox {
    /// Ids of objects that have not been synced yet.
    fn pending(&self) -> Result<Vec<String>>;
    /// Download the ciphertext to `dest`. If `dest` already holds a partial download, continue it.
    fn fetch(&self, id: &str, dest: &Path) -> Result<()>;
    fn put_receipt(&self, id: &str, receipt: &Receipt) -> Result<()>;
}

pub struct DirInbox {
    pub root: PathBuf,
}

const EXT: &str = "cwnd";

impl DirInbox {
    fn obj(&self, id: &str) -> PathBuf {
        self.root.join(format!("{id}.{EXT}"))
    }
    fn receipt(&self, id: &str) -> PathBuf {
        self.root.join(format!("{id}.synced.json"))
    }
    /// Store an object under a fresh random id (used by the phone simulator).
    pub fn put(&self, bytes: &[u8]) -> Result<String> {
        fs::create_dir_all(&self.root)?;
        let id = random_id();
        let tmp = self.root.join(format!(".{id}.part"));
        fs::write(&tmp, bytes)?;
        fs::rename(&tmp, self.obj(&id))?; // appears atomically
        Ok(id)
    }
}

pub fn random_id() -> String {
    let mut b = [0u8; 16];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut b))
        .unwrap_or_else(|_| b = (std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos())
        .to_le_bytes());
    b.iter().map(|x| format!("{x:02x}")).collect()
}

impl Inbox for DirInbox {
    fn pending(&self) -> Result<Vec<String>> {
        let mut ids = Vec::new();
        for e in fs::read_dir(&self.root).with_context(|| format!("reading {:?}", self.root))? {
            let name = e?.file_name().to_string_lossy().into_owned();
            if let Some(id) = name.strip_suffix(&format!(".{EXT}")) {
                if !id.starts_with('.') && !self.receipt(id).exists() {
                    ids.push(id.to_string());
                }
            }
        }
        ids.sort();
        Ok(ids)
    }
    fn fetch(&self, id: &str, dest: &Path) -> Result<()> {
        fs::copy(self.obj(id), dest)?;
        Ok(())
    }
    fn put_receipt(&self, id: &str, r: &Receipt) -> Result<()> {
        fs::write(self.receipt(id), serde_json::to_vec_pretty(r)?)?;
        Ok(())
    }
}
