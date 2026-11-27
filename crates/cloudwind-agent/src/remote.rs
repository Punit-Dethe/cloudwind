//! Talks to the Cloudwind Worker over HTTPS.

use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::Path,
};

use anyhow::{bail, Context, Result};
use serde::Deserialize;

use crate::inbox::{Inbox, Receipt};

/// Files up to this size go up in one PUT; larger ones use multipart.
pub const SINGLE_MAX: u64 = 64 << 20;
/// Multipart part size. Must be >= 5 MiB (R2) and well under the Worker's 100 MB body limit.
pub const PART_SIZE: u64 = 8 << 20;

pub struct RemoteInbox {
    base: String,
    token: String,
    agent: ureq::Agent,
}

#[derive(Deserialize)]
struct Pending {
    objects: Vec<PendingObj>,
}
#[derive(Deserialize)]
struct PendingObj {
    id: String,
}
#[derive(Deserialize)]
struct Created {
    #[serde(rename = "uploadId")]
    upload_id: String,
}

impl RemoteInbox {
    pub fn new(base: &str, token: &str) -> Self {
        let agent = ureq::AgentBuilder::new().try_proxy_from_env(true).build();
        Self { base: base.trim_end_matches('/').to_string(), token: token.to_string(), agent }
    }

    fn req(&self, method: &str, path: &str) -> ureq::Request {
        self.agent
            .request(method, &format!("{}{}", self.base, path))
            .set("authorization", &format!("Bearer {}", self.token))
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
        let up: Created = self
            .req("POST", &format!("/v1/objects/{id}/multipart"))
            .call()?
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
    fn open(&self, id: &str) -> Result<Box<dyn Read>> {
        let r = self.req("GET", &format!("/v1/objects/{id}")).call().context("download failed")?;
        Ok(Box::new(r.into_reader()))
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
