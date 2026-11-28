//! Small persistent state next to the config: which files we've already stored (so a re-paired
//! phone or an overlapping "back up older photos" never creates duplicates) and per-object
//! failure backoff (so one bad object isn't re-downloaded in a tight loop).
//!
//! Shared between the cloud puller and the LAN receiver, so every method takes `&self`.

use std::{
    collections::{HashMap, HashSet},
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
};

use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
struct Failure {
    count: u32,
    retry_at: u64,
}

#[derive(Hash, PartialEq, Eq)]
enum SeenKey {
    /// Real 64-char hex SHA-256 stored inline (32 bytes, zero heap allocation).
    Sha256([u8; 32]),
    /// Fallback for non-SHA strings (e.g. unit tests).
    Other(Box<str>),
}

impl SeenKey {
    fn parse(s: &str) -> Self {
        if let Some(bytes) = decode_sha256_hex(s) {
            Self::Sha256(bytes)
        } else {
            Self::Other(s.into())
        }
    }

    fn to_hex_line(&self) -> String {
        match self {
            Self::Sha256(b) => {
                let mut out = String::with_capacity(65);
                const HEX: &[u8; 16] = b"0123456789abcdef";
                for &byte in b {
                    out.push(HEX[(byte >> 4) as usize] as char);
                    out.push(HEX[(byte & 0x0f) as usize] as char);
                }
                out.push('\n');
                out
            }
            Self::Other(s) => format!("{s}\n"),
        }
    }
}

fn decode_sha256_hex(s: &str) -> Option<[u8; 32]> {
    let b = s.as_bytes();
    if b.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for i in 0..32 {
        let hi = hex_nibble(b[i * 2])?;
        let lo = hex_nibble(b[i * 2 + 1])?;
        out[i] = (hi << 4) | lo;
    }
    Some(out)
}

fn hex_nibble(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

struct Inner {
    seen: HashSet<SeenKey>,
    failures: HashMap<String, Failure>,
}

pub struct State {
    dir: PathBuf,
    inner: Mutex<Inner>,
}

/// Wait 1 min after the first failure, doubling each time, capped at 6 hours.
fn backoff_secs(count: u32) -> u64 {
    (60u64 << count.saturating_sub(1).min(10)).min(6 * 3600)
}

impl State {
    pub fn load(dir: &Path) -> Result<Self> {
        fs::create_dir_all(dir.join("tmp"))?;
        fs::create_dir_all(dir.join("lan"))?;
        let mut seen: HashSet<SeenKey> = fs::read_to_string(dir.join("seen.txt"))
            .map(|t| t.lines().map(str::trim).filter(|l| !l.is_empty()).map(SeenKey::parse).collect())
            .unwrap_or_default();
        seen.shrink_to_fit();
        let mut failures: HashMap<String, Failure> = fs::read(dir.join("failures.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        failures.shrink_to_fit();
        Ok(Self { dir: dir.to_path_buf(), inner: Mutex::new(Inner { seen, failures }) })
    }

    /// Where partially downloaded cloud ciphertext lives (resumable).
    pub fn tmp_path(&self, id: &str) -> PathBuf {
        self.dir.join("tmp").join(format!("{id}.dl"))
    }

    /// Where a partially received direct (LAN) file lives, keyed by its hash.
    pub fn lan_part_path(&self, sha256: &str) -> PathBuf {
        self.dir.join("lan").join(format!("{sha256}.part"))
    }

    pub fn has_seen(&self, sha256: &str) -> bool {
        self.inner.lock().unwrap().seen.contains(&SeenKey::parse(sha256))
    }

    pub fn seen_count(&self) -> usize {
        self.inner.lock().unwrap().seen.len()
    }

    pub fn failure_count(&self) -> usize {
        self.inner.lock().unwrap().failures.len()
    }

    /// Atomically claim a hash. True if it was new (the caller now owns storing it); false if some
    /// other route (cloud pull vs direct) already stored or is storing it.
    pub fn claim(&self, sha256: &str) -> Result<bool> {
        let mut g = self.inner.lock().unwrap();
        if !g.seen.insert(SeenKey::parse(sha256)) {
            return Ok(false);
        }
        let mut f = OpenOptions::new().create(true).append(true).open(self.dir.join("seen.txt"))?;
        writeln!(f, "{sha256}")?;
        Ok(true)
    }

    /// Undo a claim when storing failed after it, so the file can be retried.
    pub fn unclaim(&self, sha256: &str) {
        let mut g = self.inner.lock().unwrap();
        if g.seen.remove(&SeenKey::parse(sha256)) {
            let text: String = g.seen.iter().map(SeenKey::to_hex_line).collect();
            let _ = fs::write(self.dir.join("seen.txt"), text);
        }
    }

    /// False while an object is in its failure backoff window.
    pub fn due(&self, id: &str, now: u64) -> bool {
        self.inner.lock().unwrap().failures.get(id).map_or(true, |f| now >= f.retry_at)
    }

    pub fn record_failure(&self, id: &str, now: u64) {
        let mut g = self.inner.lock().unwrap();
        let count = g.failures.get(id).map_or(0, |f| f.count) + 1;
        g.failures.insert(id.to_string(), Failure { count, retry_at: now + backoff_secs(count) });
        Self::save_failures(&self.dir, &g.failures);
    }

    pub fn clear_failure(&self, id: &str) {
        let mut g = self.inner.lock().unwrap();
        if g.failures.remove(id).is_some() {
            Self::save_failures(&self.dir, &g.failures);
        }
    }

    fn save_failures(dir: &Path, failures: &HashMap<String, Failure>) {
        if let Ok(b) = serde_json::to_vec(failures) {
            let _ = fs::write(dir.join("failures.json"), b);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_grows_then_caps() {
        assert_eq!(backoff_secs(1), 60);
        assert_eq!(backoff_secs(2), 120);
        assert_eq!(backoff_secs(3), 240);
        assert_eq!(backoff_secs(50), 6 * 3600);
    }

    #[test]
    fn persists_seen_and_failures() {
        let d = tempfile::tempdir().unwrap();
        let s = State::load(d.path()).unwrap();
        assert!(!s.has_seen("abc"));
        assert!(s.claim("abc").unwrap());
        s.record_failure("obj", 1000);
        assert!(!s.due("obj", 1030));
        assert!(s.due("obj", 1061));

        let s2 = State::load(d.path()).unwrap();
        assert!(s2.has_seen("abc"));
        assert!(!s2.due("obj", 1030));
        s2.clear_failure("obj");
        assert!(s2.due("obj", 1030));
    }

    #[test]
    fn only_one_route_can_claim_a_hash() {
        let d = tempfile::tempdir().unwrap();
        let s = State::load(d.path()).unwrap();
        assert!(s.claim("h").unwrap());
        assert!(!s.claim("h").unwrap(), "second route must lose");
        s.unclaim("h");
        assert!(s.claim("h").unwrap(), "a failed store can be retried");
        assert!(State::load(d.path()).unwrap().has_seen("h"));
    }
}
