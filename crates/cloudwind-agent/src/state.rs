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

struct Inner {
    seen: HashSet<String>,
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
        let seen = fs::read_to_string(dir.join("seen.txt"))
            .map(|t| t.lines().map(str::trim).filter(|l| !l.is_empty()).map(String::from).collect())
            .unwrap_or_default();
        let failures = fs::read(dir.join("failures.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
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
        self.inner.lock().unwrap().seen.contains(sha256)
    }

    /// Atomically claim a hash. True if it was new (the caller now owns storing it); false if some
    /// other route (cloud pull vs direct) already stored or is storing it.
    pub fn claim(&self, sha256: &str) -> Result<bool> {
        let mut g = self.inner.lock().unwrap();
        if !g.seen.insert(sha256.to_string()) {
            return Ok(false);
        }
        let mut f = OpenOptions::new().create(true).append(true).open(self.dir.join("seen.txt"))?;
        writeln!(f, "{sha256}")?;
        Ok(true)
    }

    /// Undo a claim when storing failed after it, so the file can be retried.
    pub fn unclaim(&self, sha256: &str) {
        let mut g = self.inner.lock().unwrap();
        if g.seen.remove(sha256) {
            let text: String = g.seen.iter().map(|h| format!("{h}\n")).collect();
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
