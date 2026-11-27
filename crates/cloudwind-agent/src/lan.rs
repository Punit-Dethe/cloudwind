//! Direct (LAN) receiver: phone -> PC over HTTPS with a pinned self-signed certificate.
//!
//! The PC is the arbiter. The phone describes what it has (`plan`); the PC answers which files it
//! already has, which it will pull from the cloud itself, and which it wants sent (with the byte
//! offset to resume from). Files arrive raw over TLS and are verified by SHA-256 before being placed.

use std::{
    collections::HashSet,
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

use anyhow::{anyhow, bail, Context, Result};
use cloudwind_core::{base64, hash_reader, LibraryKey};
use serde::{Deserialize, Serialize};

use crate::{state::State, sync};

pub const DEFAULT_PORT: u16 = 47615;
const MAX_JSON_BODY: u64 = 8 << 20;

/// What the receiver needs from the cloud route (so it can be faked in tests).
pub trait CloudLink: Send + Sync {
    /// Object ids waiting in the cloud that the PC has not pulled yet.
    fn pending_ids(&self) -> Result<HashSet<String>>;
    /// Tell the cloud this object is done, so its copy can be evicted.
    fn mark_synced(&self, id: &str) -> Result<()>;
}

// ---- identity (certificate) ------------------------------------------------------------------

pub struct Identity {
    pub cert_pem: String,
    pub key_pem: String,
    /// SHA-256 of the certificate (DER), lowercase hex. Goes in the pairing QR; the phone pins it.
    pub fingerprint: String,
}

fn pem_to_der(pem: &str) -> Result<Vec<u8>> {
    use base64::{engine::general_purpose::STANDARD, Engine};
    let body: String = pem.lines().filter(|l| !l.starts_with("-----")).collect();
    Ok(STANDARD.decode(body.trim())?)
}

/// Load the PC's certificate, creating it on first use. It never changes unless deleted.
pub fn load_or_create_identity(dir: &Path) -> Result<Identity> {
    let (cp, kp) = (dir.join("lan-cert.pem"), dir.join("lan-key.pem"));
    if cp.exists() && kp.exists() {
        let cert_pem = fs::read_to_string(&cp)?;
        let fingerprint = hash_reader(&pem_to_der(&cert_pem)?[..])?.0;
        return Ok(Identity { cert_pem, key_pem: fs::read_to_string(&kp)?, fingerprint });
    }
    fs::create_dir_all(dir)?;
    let ck = rcgen::generate_simple_self_signed(vec!["cloudwind.local".to_string()])?;
    let (cert_pem, key_pem) = (ck.cert.pem(), ck.signing_key.serialize_pem());
    let fingerprint = hash_reader(ck.cert.der().as_ref())?.0;
    fs::write(&cp, &cert_pem)?;
    write_private(&kp, key_pem.as_bytes())?;
    Ok(Identity { cert_pem, key_pem, fingerprint })
}

fn write_private(path: &Path, data: &[u8]) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut f = OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
        f.write_all(data)?;
    }
    #[cfg(not(unix))]
    fs::write(path, data)?;
    Ok(())
}

// ---- protocol types --------------------------------------------------------------------------

#[derive(Deserialize, Debug)]
pub struct PlanItem {
    pub id: String,
    pub sha256: String,
    pub size: u64,
    /// The cloud object this file was already uploaded as, if any.
    #[serde(default)]
    pub object_id: Option<String>,
}

#[derive(Deserialize)]
struct PlanRequest {
    items: Vec<PlanItem>,
}

#[derive(Serialize, Debug, PartialEq, Eq, Default)]
pub struct Want {
    pub id: String,
    pub offset: u64,
}

#[derive(Serialize, Debug, PartialEq, Eq, Default)]
pub struct PlanReply {
    /// The PC already has these bytes: the phone can mark them safe to delete.
    pub have: Vec<String>,
    /// Already in the cloud; the PC will pull them itself, the phone sends nothing.
    pub cloud: Vec<String>,
    /// Send these, starting at `offset`.
    pub want: Vec<Want>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Received {
    /// Stored so far, waiting for more bytes.
    Progress { have: u64 },
    /// Complete and verified. `path` is None when the PC already had these bytes.
    Stored { path: Option<PathBuf> },
    /// The offset didn't match what the PC holds; resume from `have`.
    Conflict { have: u64 },
    /// Hash mismatch: the partial file was discarded, start again from 0.
    BadHash,
    /// Another transfer of the same file is running.
    Busy,
}

fn valid_sha(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

// ---- the receiver ----------------------------------------------------------------------------

pub struct Lan {
    library: PathBuf,
    state: Arc<State>,
    token: String,
    link: Option<Arc<dyn CloudLink>>,
    in_flight: Mutex<HashSet<String>>,
}

struct Flight<'a>(&'a Mutex<HashSet<String>>, String);
impl Drop for Flight<'_> {
    fn drop(&mut self) {
        self.0.lock().unwrap().remove(&self.1);
    }
}

impl Lan {
    pub fn new(library: PathBuf, state: Arc<State>, key: &LibraryKey, link: Option<Arc<dyn CloudLink>>) -> Self {
        Self { library, state, token: key.lan_auth_token(), link, in_flight: Mutex::new(HashSet::new()) }
    }

    fn part_len(&self, sha: &str) -> u64 {
        fs::metadata(self.state.lan_part_path(sha)).map(|m| m.len()).unwrap_or(0)
    }

    fn cloud_done(&self, object: Option<&str>) {
        if let (Some(link), Some(id)) = (&self.link, object) {
            let _ = link.mark_synced(id); // best effort: the daily cleanup still removes it
        }
    }

    pub fn plan(&self, items: &[PlanItem]) -> PlanReply {
        let in_cloud = self.link.as_ref().and_then(|l| l.pending_ids().ok()).unwrap_or_default();
        let mut r = PlanReply::default();
        for it in items {
            if !valid_sha(&it.sha256) {
                continue;
            }
            if self.state.has_seen(&it.sha256) {
                self.cloud_done(it.object_id.as_deref());
                r.have.push(it.id.clone());
            } else if it.object_id.as_ref().is_some_and(|o| in_cloud.contains(o)) {
                r.cloud.push(it.id.clone());
            } else {
                let offset = self.part_len(&it.sha256);
                r.want.push(Want { id: it.id.clone(), offset: if offset <= it.size { offset } else { 0 } });
            }
        }
        r
    }

    /// Append `body` to the partial file for `sha256` at `offset`; when complete, verify and place it.
    pub fn receive(
        &self,
        sha256: &str,
        size: u64,
        offset: u64,
        name: &str,
        modified: Option<u64>,
        object: Option<&str>,
        body: &mut dyn Read,
    ) -> Result<Received> {
        if !valid_sha(sha256) {
            bail!("bad hash");
        }
        if self.state.has_seen(sha256) {
            self.cloud_done(object);
            return Ok(Received::Stored { path: None });
        }
        if !self.in_flight.lock().unwrap().insert(sha256.to_string()) {
            return Ok(Received::Busy);
        }
        let _flight = Flight(&self.in_flight, sha256.to_string());

        let part = self.state.lan_part_path(sha256);
        let have = self.part_len(sha256);
        if offset == 0 {
            File::create(&part)?; // start (or restart) from the beginning
        } else if offset != have || offset > size {
            return Ok(Received::Conflict { have: if have <= size { have } else { 0 } });
        }

        let mut out = OpenOptions::new().append(true).open(&part)?;
        let mut limited = body.take(size.saturating_sub(offset));
        let mut buf = vec![0u8; 256 * 1024];
        loop {
            match limited.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => out.write_all(&buf[..n])?,
                Err(_) => break, // the phone dropped: keep what arrived, it can resume
            }
        }
        out.flush()?;
        drop(out);

        let len = self.part_len(sha256);
        if len < size {
            return Ok(Received::Progress { have: len });
        }
        let (got, _) = hash_reader(File::open(&part)?)?;
        if got != sha256 {
            let _ = fs::remove_file(&part);
            return Ok(Received::BadHash);
        }
        let stored = sync::place(&self.library, &self.state, &part, name, modified, sha256)?;
        self.cloud_done(object);
        Ok(Received::Stored { path: stored })
    }

    // ---- HTTP -----------------------------------------------------------------------------

    fn authorized(&self, req: &tiny_http::Request) -> bool {
        let want = format!("Bearer {}", self.token);
        req.headers()
            .iter()
            .find(|h| h.field.equiv("Authorization"))
            .is_some_and(|h| constant_time_eq(h.value.as_str().as_bytes(), want.as_bytes()))
    }

    pub fn handle(&self, mut req: tiny_http::Request) {
        let (path, query) = match req.url().split_once('?') {
            Some((p, q)) => (p.to_string(), q.to_string()),
            None => (req.url().to_string(), String::new()),
        };
        let method = req.method().clone();
        let result = if !self.authorized(&req) {
            (401, r#"{"error":"unauthorized"}"#.to_string())
        } else {
            match (&method, path.as_str()) {
                (tiny_http::Method::Get, "/lan/v1/hello") => (200, r#"{"v":1,"service":"cloudwind"}"#.to_string()),
                (tiny_http::Method::Post, "/lan/v1/plan") => {
                    let mut buf = String::new();
                    match req.as_reader().take(MAX_JSON_BODY).read_to_string(&mut buf).ok().and_then(|_| serde_json::from_str::<PlanRequest>(&buf).ok()) {
                        Some(p) => (200, serde_json::to_string(&self.plan(&p.items)).unwrap()),
                        None => (400, r#"{"error":"bad_request"}"#.to_string()),
                    }
                }
                (tiny_http::Method::Put, p) if p.starts_with("/lan/v1/file/") => {
                    let sha = p.trim_start_matches("/lan/v1/file/").to_string();
                    self.handle_put(&mut req, &sha, &query)
                }
                _ => (404, r#"{"error":"not_found"}"#.to_string()),
            }
        };
        let _ = req.respond(
            tiny_http::Response::from_string(result.1)
                .with_status_code(result.0)
                .with_header("Content-Type: application/json".parse::<tiny_http::Header>().unwrap()),
        );
    }

    fn handle_put(&self, req: &mut tiny_http::Request, sha: &str, query: &str) -> (u16, String) {
        let q = parse_query(query);
        let get = |k: &str| q.iter().find(|(a, _)| a == k).map(|(_, v)| v.as_str());
        let (Some(size), Some(offset)) = (get("size").and_then(|v| v.parse().ok()), get("offset").and_then(|v| v.parse().ok())) else {
            return (400, r#"{"error":"size_and_offset_required"}"#.into());
        };
        let name = get("name").unwrap_or("file");
        let modified = get("modified").and_then(|v| v.parse().ok());
        match self.receive(sha, size, offset, name, modified, get("object"), req.as_reader()) {
            Ok(Received::Progress { have }) => (202, format!(r#"{{"have":{have}}}"#)),
            Ok(Received::Stored { path }) => (200, format!(r#"{{"stored":{}}}"#, path.is_some())),
            Ok(Received::Conflict { have }) => (409, format!(r#"{{"error":"offset_mismatch","have":{have}}}"#)),
            Ok(Received::BadHash) => (422, r#"{"error":"hash_mismatch"}"#.into()),
            Ok(Received::Busy) => (423, r#"{"error":"busy"}"#.into()),
            Err(e) => (500, format!(r#"{{"error":"{}"}}"#, e.to_string().replace('"', "'"))),
        }
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'+' => { out.push(b' '); i += 1; }
            b'%' if b.len() >= i + 3 => match u8::from_str_radix(&s[i + 1..i + 3], 16) {
                Ok(v) => { out.push(v); i += 3; }
                Err(_) => { out.push(b'%'); i += 1; }
            },
            c => { out.push(c); i += 1; }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn parse_query(q: &str) -> Vec<(String, String)> {
    q.split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let (k, v) = p.split_once('=').unwrap_or((p, ""));
            (percent_decode(k), percent_decode(v))
        })
        .collect()
}

/// Start the TLS listener. `bind` like "0.0.0.0", port 0 picks a free one. Returns the server and its address.
pub fn start(identity: &Identity, bind: &str, port: u16) -> Result<(Arc<tiny_http::Server>, std::net::SocketAddr)> {
    let ssl = tiny_http::SslConfig {
        certificate: identity.cert_pem.clone().into_bytes(),
        private_key: identity.key_pem.clone().into_bytes(),
    };
    let server = tiny_http::Server::https(format!("{bind}:{port}"), ssl).map_err(|e| anyhow!("cannot listen on {bind}:{port}: {e}"))?;
    let addr = server.server_addr().to_ip().context("not an IP listener")?;
    Ok((Arc::new(server), addr))
}

/// Serve requests until the server is dropped; one thread per request.
pub fn serve(lan: Arc<Lan>, server: Arc<tiny_http::Server>) {
    for req in server.incoming_requests() {
        let lan = lan.clone();
        std::thread::spawn(move || lan.handle(req));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    struct FakeCloud {
        pending: Mutex<HashSet<String>>,
        synced: Mutex<Vec<String>>,
    }
    impl CloudLink for FakeCloud {
        fn pending_ids(&self) -> Result<HashSet<String>> {
            Ok(self.pending.lock().unwrap().clone())
        }
        fn mark_synced(&self, id: &str) -> Result<()> {
            self.synced.lock().unwrap().push(id.to_string());
            Ok(())
        }
    }

    struct Env {
        _d: tempfile::TempDir,
        lan: Lan,
        lib: PathBuf,
        cloud: Arc<FakeCloud>,
    }

    fn env() -> Env {
        let d = tempfile::tempdir().unwrap();
        let state = Arc::new(State::load(&d.path().join("state")).unwrap());
        let cloud = Arc::new(FakeCloud { pending: Mutex::new(HashSet::new()), synced: Mutex::new(vec![]) });
        let lib = d.path().join("photos");
        fs::create_dir_all(&lib).unwrap();
        let lan = Lan::new(lib.clone(), state, &LibraryKey::generate(), Some(cloud.clone()));
        Env { _d: d, lan, lib, cloud }
    }

    fn sha(data: &[u8]) -> String {
        hash_reader(data).unwrap().0
    }

    #[test]
    fn plan_separates_have_cloud_and_want() {
        let e = env();
        let (a, b, c) = (b"already here".to_vec(), b"in the cloud".to_vec(), b"only on the phone".to_vec());
        e.lan.receive(&sha(&a), a.len() as u64, 0, "a.jpg", None, None, &mut Cursor::new(&a)).unwrap();
        e.cloud.pending.lock().unwrap().insert("obj-b".into());

        let items = vec![
            PlanItem { id: "1".into(), sha256: sha(&a), size: a.len() as u64, object_id: Some("obj-a".into()) },
            PlanItem { id: "2".into(), sha256: sha(&b), size: b.len() as u64, object_id: Some("obj-b".into()) },
            PlanItem { id: "3".into(), sha256: sha(&c), size: c.len() as u64, object_id: None },
            PlanItem { id: "4".into(), sha256: "../../etc".into(), size: 1, object_id: None },
        ];
        let r = e.lan.plan(&items);
        assert_eq!(r.have, vec!["1"]);
        assert_eq!(r.cloud, vec!["2"]);
        assert_eq!(r.want, vec![Want { id: "3".into(), offset: 0 }]);
        assert_eq!(*e.cloud.synced.lock().unwrap(), vec!["obj-a"], "PC already had it, so the cloud copy is released");
    }

    #[test]
    fn receives_verifies_places_and_releases_the_cloud_copy() {
        let e = env();
        let data: Vec<u8> = (0..500_000u32).map(|i| (i % 253) as u8).collect();
        let r = e.lan.receive(&sha(&data), data.len() as u64, 0, "IMG_1.jpg", Some(1_700_000_000), Some("obj-1"), &mut Cursor::new(&data)).unwrap();
        let Received::Stored { path: Some(p) } = r else { panic!("{r:?}") };
        assert_eq!(fs::read(&p).unwrap(), data);
        assert_eq!(p.parent().unwrap(), e.lib);
        assert_eq!(*e.cloud.synced.lock().unwrap(), vec!["obj-1"]);
    }

    #[test]
    fn dropped_transfer_resumes_from_the_byte_the_pc_holds() {
        let e = env();
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let s = sha(&data);
        // Phone sends 100k then the connection dies (body ends early).
        let r = e.lan.receive(&s, 300_000, 0, "v.mp4", None, None, &mut Cursor::new(&data[..100_000])).unwrap();
        assert_eq!(r, Received::Progress { have: 100_000 });
        // The plan now tells the phone where to continue.
        let plan = e.lan.plan(&[PlanItem { id: "9".into(), sha256: s.clone(), size: 300_000, object_id: None }]);
        assert_eq!(plan.want, vec![Want { id: "9".into(), offset: 100_000 }]);
        // A wrong offset is refused with the truth.
        let r = e.lan.receive(&s, 300_000, 50_000, "v.mp4", None, None, &mut Cursor::new(&data[50_000..])).unwrap();
        assert_eq!(r, Received::Conflict { have: 100_000 });
        // Resuming at the right offset completes and verifies the whole file.
        let r = e.lan.receive(&s, 300_000, 100_000, "v.mp4", None, None, &mut Cursor::new(&data[100_000..])).unwrap();
        let Received::Stored { path: Some(p) } = r else { panic!("{r:?}") };
        assert_eq!(fs::read(p).unwrap(), data);
    }

    #[test]
    fn corrupt_bytes_are_discarded_not_stored() {
        let e = env();
        let good = b"the real photo".to_vec();
        let mut bad = good.clone();
        bad[3] ^= 1;
        let r = e.lan.receive(&sha(&good), good.len() as u64, 0, "x.jpg", None, None, &mut Cursor::new(&bad)).unwrap();
        assert_eq!(r, Received::BadHash);
        assert!(fs::read_dir(&e.lib).unwrap().next().is_none());
        assert_eq!(e.lan.part_len(&sha(&good)), 0, "bad partial removed");
    }

    #[test]
    fn same_bytes_twice_store_once() {
        let e = env();
        let d = b"duplicate me".to_vec();
        let s = sha(&d);
        e.lan.receive(&s, d.len() as u64, 0, "a.jpg", None, None, &mut Cursor::new(&d)).unwrap();
        let r = e.lan.receive(&s, d.len() as u64, 0, "b.jpg", None, Some("obj-x"), &mut Cursor::new(&d)).unwrap();
        assert_eq!(r, Received::Stored { path: None });
        assert_eq!(fs::read_dir(&e.lib).unwrap().count(), 1);
        assert_eq!(*e.cloud.synced.lock().unwrap(), vec!["obj-x"]);
    }

    #[test]
    fn empty_file_works() {
        let e = env();
        let r = e.lan.receive(&sha(b""), 0, 0, "empty.txt", None, None, &mut Cursor::new(Vec::<u8>::new())).unwrap();
        assert!(matches!(r, Received::Stored { path: Some(_) }));
    }

    #[test]
    fn query_parsing_decodes_names() {
        let q = parse_query("size=10&name=My%20Photo+%C3%BC.jpg&offset=0");
        assert_eq!(q[1], ("name".to_string(), "My Photo ü.jpg".to_string()));
        assert_eq!(percent_decode("100%"), "100%");
    }

    #[test]
    fn identity_is_created_once_and_stable() {
        let d = tempfile::tempdir().unwrap();
        let a = load_or_create_identity(d.path()).unwrap();
        let b = load_or_create_identity(d.path()).unwrap();
        assert_eq!(a.fingerprint, b.fingerprint);
        assert_eq!(a.fingerprint.len(), 64);
    }
}
