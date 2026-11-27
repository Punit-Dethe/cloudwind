//! Small persistent state next to the config: which files we've already stored (so a re-paired
//! phone or an overlapping "back up older photos" never creates duplicates) and per-object
//! failure backoff (so one bad object isn't re-downloaded in a tight loop).

use std::{
    collections::{HashMap, HashSet},
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
};

use anyhow::Result;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Copy, Debug, PartialEq, Eq)]
struct Failure {
    count: u32,
    retry_at: u64,
}

pub struct State {
    dir: PathBuf,
    seen: HashSet<String>,
    failures: HashMap<String, Failure>,
}

/// Wait 1 min after the first failure, doubling each time, capped at 6 hours.
fn backoff_secs(count: u32) -> u64 {
    (60u64 << count.saturating_sub(1).min(10)).min(6 * 3600)
}

impl State {
    pub fn load(dir: &Path) -> Result<Self> {
        fs::create_dir_all(dir.join("tmp"))?;
        let seen = fs::read_to_string(dir.join("seen.txt"))
            .map(|t| t.lines().map(str::trim).filter(|l| !l.is_empty()).map(String::from).collect())
            .unwrap_or_default();
        let failures = fs::read(dir.join("failures.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        Ok(Self { dir: dir.to_path_buf(), seen, failures })
    }

    /// Where partially downloaded ciphertext lives (resumable).
    pub fn tmp_path(&self, id: &str) -> PathBuf {
        self.dir.join("tmp").join(format!("{id}.dl"))
    }

    pub fn has_seen(&self, sha256: &str) -> bool {
        self.seen.contains(sha256)
    }

    pub fn record_seen(&mut self, sha256: &str) -> Result<()> {
        if self.seen.insert(sha256.to_string()) {
            let mut f = OpenOptions::new().create(true).append(true).open(self.dir.join("seen.txt"))?;
            writeln!(f, "{sha256}")?;
        }
        Ok(())
    }

    /// False while an object is in its failure backoff window.
    pub fn due(&self, id: &str, now: u64) -> bool {
        self.failures.get(id).map_or(true, |f| now >= f.retry_at)
    }

    pub fn record_failure(&mut self, id: &str, now: u64) {
        let count = self.failures.get(id).map_or(0, |f| f.count) + 1;
        self.failures.insert(id.to_string(), Failure { count, retry_at: now + backoff_secs(count) });
        self.save_failures();
    }

    pub fn clear_failure(&mut self, id: &str) {
        if self.failures.remove(id).is_some() {
            self.save_failures();
        }
    }

    fn save_failures(&self) {
        if let Ok(b) = serde_json::to_vec(&self.failures) {
            let _ = fs::write(self.dir.join("failures.json"), b);
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
        let mut s = State::load(d.path()).unwrap();
        assert!(!s.has_seen("abc"));
        s.record_seen("abc").unwrap();
        s.record_failure("obj", 1000);
        assert!(!s.due("obj", 1030));
        assert!(s.due("obj", 1061));

        let s2 = State::load(d.path()).unwrap();
        assert!(s2.has_seen("abc"));
        assert!(!s2.due("obj", 1030));
        let mut s2 = s2;
        s2.clear_failure("obj");
        assert!(s2.due("obj", 1030));
    }
}
