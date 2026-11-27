//! Talks to the Cloudwind Worker over HTTPS.

use std::{
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom},
    path::Path,
    time::Duration,
};

use anyhow::{bail, Context, Result};
use serde::Deserialize;

use crate::inbox::{Inbox, Receipt};

/// Files up to this size go up in one PUT; larger ones use multipart.
pub const SINGLE_MAX: u64 = 64 << 20;
/// Multipart part size. Must be >= 5 MiB (R2) and well under the Worker's 100 MB body limit.
pub const PART_SIZE: u64 = 8 << 20;

const MAX_ATTEMPTS: u32 = 6;

pub struct RemoteInbox {
    base: String,
    token: String,
    agent: ureq::Agent,
    /// First retry delay; doubles each attempt (capped). Tests shrink it.
    pub backoff: Duration,
}

#[derive(Deserialize)]
struct Pending {
    objects: Vec<PendingObj>,
}
#[derive(Deserialize)]
struct PendingObj {
    id: String,
}
/// What the PC can learn about the phone and inbox in one call.
#[derive(Deserialize, Debug)]
pub struct Health {
    pub phone_last_seen: Option<u64>,
    pub pending_count: u64,
    pub pending_bytes: u64,
    pub inbox_bytes: u64,
    pub cap_bytes: u64,
    pub now: u64,
}

#[derive(Deserialize)]
struct Created {
    #[serde(rename = "uploadId")]
    upload_id: String,
}

impl RemoteInbox {
    pub fn new(base: &str, token: &str) -> Self {
        Self::with_agent(base, token, ureq::AgentBuilder::new().try_proxy_from_env(true).build())
    }

    pub fn with_agent(base: &str, token: &str, agent: ureq::Agent) -> Self {
        Self { base: base.trim_end_matches('/').to_string(), token: token.to_string(), agent, backoff: Duration::from_secs(1) }
    }

    fn req(&self, method: &str, path: &str) -> ureq::Request {
        self.agent
            .request(method, &format!("{}{}", self.base, path))
            .set("authorization", &format!("Bearer {}", self.token))
    }

    pub fn health(&self) -> Result<Health> {
        Ok(self.req("GET", "/v1/health").call().context("reading inbox health")?.into_json()?)
    }

    /// Upload an encrypted file as the phone would. Returns the object id.
    pub fn upload(&self, id: &str, file: &Path) -> Result<()> {
        let size = file.metadata()?.len();
        if size <= SINGLE_MAX {
            let f = File::open(file)?;
            self.req("PUT", &format!("/v1/objects/{id}"))
                .set("content-length", &size.to_string())
                .send(f)
                .context("upload failed")?;
            return Ok(());
        }
        // The Worker reserves this much of the inbox cap, so declare the real size.
        let up: Created = self
            .req("POST", &format!("/v1/objects/{id}/multipart"))
            .send_json(serde_json::json!({ "size": size }))?
            .into_json()?;
        let mut f = File::open(file)?;
        let mut parts = Vec::new();
        let (mut n, mut off) = (1u32, 0u64);
        while off < size {
            let len = PART_SIZE.min(size - off);
            f.seek(SeekFrom::Start(off))?;
            let part: serde_json::Value = self
                .req("PUT", &format!("/v1/objects/{id}/multipart/{}/{n}", up.upload_id))
                .set("content-length", &len.to_string())
                .send((&mut f).take(len))
                .with_context(|| format!("part {n} failed"))?
                .into_json()?;
            parts.push(part);
            n += 1;
            off += len;
        }
        self.req("POST", &format!("/v1/objects/{id}/multipart/{}/complete", up.upload_id))
            .send_json(serde_json::json!({ "parts": parts }))
            .context("completing upload failed")?;
        Ok(())
    }
}

impl Inbox for RemoteInbox {
    fn pending(&self) -> Result<Vec<String>> {
        let p: Pending = self.req("GET", "/v1/pending").call().context("listing inbox")?.into_json()?;
        Ok(p.objects.into_iter().map(|o| o.id).collect())
    }
    fn fetch(&self, id: &str, dest: &Path) -> Result<()> {
        // Resume from whatever is already on disk; a dropped connection costs only the tail.
        let mut attempt = 0u32;
        loop {
            let have = dest.metadata().map(|m| m.len()).unwrap_or(0);
            let mut req = self.req("GET", &format!("/v1/objects/{id}"));
            if have > 0 {
                req = req.set("range", &format!("bytes={have}-"));
            }
            let outcome: Result<()> = match req.call() {
                Ok(resp) => {
                    let resumed = resp.status() == 206;
                    let want: Option<u64> = resp.header("content-length").and_then(|v| v.parse().ok());
                    let mut f = if resumed {
                        OpenOptions::new().append(true).open(dest)?
                    } else {
                        File::create(dest)? // server ignored the range: start over
                    };
                    match std::io::copy(&mut resp.into_reader(), &mut f) {
                        Ok(n) if want.map_or(true, |w| w == n) => return Ok(()),
                        Ok(n) => Err(anyhow::anyhow!("short download ({n} of {want:?} bytes)")),
                        Err(e) => Err(e.into()),
                    }
                }
                Err(ureq::Error::Status(416, _)) if have > 0 => {
                    let _ = std::fs::remove_file(dest); // stale partial; start fresh
                    Err(anyhow::anyhow!("stale partial download"))
                }
                Err(ureq::Error::Status(429, r)) => {
                    let body = r.into_string().unwrap_or_default();
                    if body.contains("download_limit") {
                        bail!("the inbox refuses further downloads of {id} (download cap reached)");
                    }
                    Err(anyhow::anyhow!("rate limited"))
                }
                Err(ureq::Error::Status(code, _)) if code >= 500 => Err(anyhow::anyhow!("server error {code}")),
                Err(ureq::Error::Status(code, _)) => bail!("download failed: HTTP {code}"),
                Err(e @ ureq::Error::Transport(_)) => Err(e.into()),
            };
            attempt += 1;
            if attempt >= MAX_ATTEMPTS {
                return outcome.context("download kept failing");
            }
            std::thread::sleep(self.backoff * 2u32.pow(attempt - 1).min(30));
        }
    }
    fn put_receipt(&self, id: &str, _r: &Receipt) -> Result<()> {
        // The cloud only learns *that* it synced, never the file hash.
        let r = self.req("POST", &format!("/v1/objects/{id}/synced")).call();
        match r {
            Ok(_) => Ok(()),
            Err(e) => bail!("could not confirm sync: {e}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{BufRead, BufReader, Write},
        net::TcpListener,
        sync::{Arc, Mutex},
    };

    /// Serves `data` at any path. The first request is cut off halfway through the body; later
    /// requests honor `Range: bytes=N-`. Records every Range header seen.
    fn flaky_server(data: Vec<u8>) -> (String, Arc<Mutex<Vec<Option<String>>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let seen2 = seen.clone();
        std::thread::spawn(move || {
            for (n, stream) in listener.incoming().enumerate() {
                let mut stream = stream.unwrap();
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut range = None;
                loop {
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    if line == "\r\n" || line.is_empty() {
                        break;
                    }
                    if let Some(v) = line.to_lowercase().strip_prefix("range: bytes=") {
                        range = Some(v.trim().trim_end_matches('-').to_string());
                    }
                }
                seen2.lock().unwrap().push(range.clone());
                let start: usize = range.as_deref().map(|r| r.parse().unwrap()).unwrap_or(0);
                let body = &data[start..];
                let head = if start > 0 {
                    format!("HTTP/1.1 206 Partial Content\r\ncontent-length: {}\r\ncontent-range: bytes {}-{}/{}\r\nconnection: close\r\n\r\n", body.len(), start, data.len() - 1, data.len())
                } else {
                    format!("HTTP/1.1 200 OK\r\ncontent-length: {}\r\nconnection: close\r\n\r\n", body.len())
                };
                stream.write_all(head.as_bytes()).unwrap();
                if n == 0 {
                    stream.write_all(&body[..body.len() / 2]).unwrap(); // then hang up
                } else {
                    stream.write_all(body).unwrap();
                }
            }
        });
        (url, seen)
    }

    #[test]
    fn resumes_a_dropped_download_from_where_it_stopped() {
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let (url, seen) = flaky_server(data.clone());
        let mut inbox = RemoteInbox::with_agent(&url, "t", ureq::AgentBuilder::new().build());
        inbox.backoff = Duration::from_millis(1);
        let d = tempfile::tempdir().unwrap();
        let dest = d.path().join("x.dl");
        inbox.fetch("a".repeat(32).as_str(), &dest).unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), data);
        let reqs = seen.lock().unwrap();
        assert_eq!(reqs.len(), 2, "one failed attempt, one resume");
        assert_eq!(reqs[0], None);
        assert_eq!(reqs[1], Some("100000".to_string()), "resumed at the byte where it stopped");
    }
}
