//! Cloudwind shared core: key handling, the encrypted file format and hashing.
//!
//! File layout (all integers big-endian):
//!
//! ```text
//! magic "CWND" | version u8 | chunk_size u32 | stream nonce (19 bytes)
//! then STREAM-encrypted chunks (XChaCha20-Poly1305), each chunk_size plaintext
//! bytes (+16 tag); the last chunk may be shorter.
//! plaintext = meta_len u32 | meta JSON | file bytes
//! ```
//!
//! The metadata (original name, size, SHA-256) lives inside the ciphertext so the
//! cloud never sees file names. Chunks are processed one at a time, so large
//! videos are never held in memory.

use std::io::{self, Read, Write};

use chacha20poly1305::{
    aead::{stream, KeyInit},
    XChaCha20Poly1305,
};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub use base64;

const MAGIC: &[u8; 4] = b"CWND";
const VERSION: u8 = 1;
const NONCE_LEN: usize = 19;
const TAG_LEN: usize = 16;
/// Default plaintext chunk size: 1 MiB.
pub const DEFAULT_CHUNK: u32 = 1 << 20;
const MAX_CHUNK: u32 = 64 << 20;
const MAX_META: u32 = 1 << 20;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("io error: {0}")]
    Io(#[from] io::Error),
    #[error("not a Cloudwind file")]
    BadMagic,
    #[error("unsupported format version {0}")]
    BadVersion(u8),
    #[error("invalid header or metadata")]
    Malformed,
    #[error("decryption failed (wrong key or corrupted data)")]
    Crypto,
    #[error("hash mismatch: file is corrupted")]
    HashMismatch,
    #[error("invalid key: {0}")]
    BadKey(String),
}
pub type Result<T> = std::result::Result<T, Error>;

/// The 32-byte library key shared by phone and PC.
#[derive(Clone)]
pub struct LibraryKey([u8; 32]);

impl LibraryKey {
    pub fn generate() -> Self {
        let mut k = [0u8; 32];
        rand::rngs::OsRng.fill_bytes(&mut k);
        Self(k)
    }
    /// Bearer token for the LAN link, derived from the library key so there is nothing extra to pair.
    /// Only a phone that holds the key can compute it. Hex, 64 chars.
    pub fn lan_auth_token(&self) -> String {
        use hmac::{Hmac, Mac};
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(&self.0).expect("hmac accepts any key length");
        mac.update(b"cloudwind/lan-auth/v1");
        hex(&mac.finalize().into_bytes())
    }

    pub fn to_base64(&self) -> String {
        use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
        URL_SAFE_NO_PAD.encode(self.0)
    }
    pub fn from_base64(s: &str) -> Result<Self> {
        use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
        let v = URL_SAFE_NO_PAD
            .decode(s.trim())
            .map_err(|e| Error::BadKey(e.to_string()))?;
        let a: [u8; 32] = v
            .try_into()
            .map_err(|_| Error::BadKey("expected 32 bytes".into()))?;
        Ok(Self(a))
    }
}

impl std::fmt::Debug for LibraryKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("LibraryKey(..)")
    }
}

/// Metadata stored (encrypted) inside every file.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Meta {
    pub name: String,
    pub size: u64,
    /// Lowercase hex SHA-256 of the original file.
    pub sha256: String,
    /// Original modification time, seconds since the Unix epoch, if known.
    pub modified: Option<u64>,
}

/// Hash a reader with SHA-256, returning lowercase hex and the byte count.
pub fn hash_reader(mut r: impl Read) -> io::Result<(String, u64)> {
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut n = 0u64;
    loop {
        let k = r.read(&mut buf)?;
        if k == 0 {
            break;
        }
        h.update(&buf[..k]);
        n += k as u64;
    }
    Ok((hex(&h.finalize()), n))
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Fill `buf` as far as possible; returns bytes read (less than len only at EOF).
fn read_full(r: &mut impl Read, buf: &mut [u8]) -> io::Result<usize> {
    let mut t = 0;
    while t < buf.len() {
        let n = r.read(&mut buf[t..])?;
        if n == 0 {
            break;
        }
        t += n;
    }
    Ok(t)
}

/// Encrypt `src` (read twice via `open`, once to hash and once to encrypt) into `dst`.
///
/// Two passes keep memory flat for large files while still recording the hash
/// of the original in the metadata.
pub fn encrypt<R: Read, W: Write>(
    key: &LibraryKey,
    name: &str,
    modified: Option<u64>,
    mut open: impl FnMut() -> io::Result<R>,
    dst: W,
) -> Result<Meta> {
    let (sha256, size) = hash_reader(open()?)?;
    let meta = Meta { name: name.to_string(), size, sha256, modified };
    encrypt_with_meta(key, &meta, open()?, dst, DEFAULT_CHUNK)?;
    Ok(meta)
}

pub fn encrypt_with_meta(
    key: &LibraryKey,
    meta: &Meta,
    mut src: impl Read,
    mut dst: impl Write,
    chunk: u32,
) -> Result<()> {
    assert!(chunk > 0 && chunk <= MAX_CHUNK);
    let mut nonce = [0u8; NONCE_LEN];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    dst.write_all(MAGIC)?;
    dst.write_all(&[VERSION])?;
    dst.write_all(&chunk.to_be_bytes())?;
    dst.write_all(&nonce)?;

    let mut enc =
        stream::EncryptorBE32::from_aead(XChaCha20Poly1305::new((&key.0).into()), (&nonce).into());
    let meta_json = serde_json::to_vec(meta).map_err(|_| Error::Malformed)?;
    // Plaintext stream = meta_len | meta | file bytes, re-chunked at `chunk`.
    let mut head = Vec::with_capacity(4 + meta_json.len());
    head.extend_from_slice(&(meta_json.len() as u32).to_be_bytes());
    head.extend_from_slice(&meta_json);
    let mut input = io::Cursor::new(head).chain(&mut src);

    let mut buf = vec![0u8; chunk as usize];
    let mut cur = read_full(&mut input, &mut buf)?;
    loop {
        // Look ahead one chunk so we know which one is the last.
        let mut next = vec![0u8; chunk as usize];
        let nlen = if cur == chunk as usize { read_full(&mut input, &mut next)? } else { 0 };
        if nlen == 0 {
            let ct = enc.encrypt_last(&buf[..cur]).map_err(|_| Error::Crypto)?;
            dst.write_all(&ct)?;
            break;
        }
        let ct = enc.encrypt_next(&buf[..cur]).map_err(|_| Error::Crypto)?;
        dst.write_all(&ct)?;
        buf = next;
        cur = nlen;
    }
    dst.flush()?;
    Ok(())
}

/// Decrypt into `dst`, verifying the SHA-256 recorded in the metadata.
///
/// Returns the metadata. On `Err`, whatever was written to `dst` must be discarded.
pub fn decrypt(key: &LibraryKey, mut src: impl Read, mut dst: impl Write) -> Result<Meta> {
    let mut hdr = [0u8; 4 + 1 + 4 + NONCE_LEN];
    src.read_exact(&mut hdr).map_err(|_| Error::BadMagic)?;
    if &hdr[..4] != MAGIC {
        return Err(Error::BadMagic);
    }
    if hdr[4] != VERSION {
        return Err(Error::BadVersion(hdr[4]));
    }
    let chunk = u32::from_be_bytes(hdr[5..9].try_into().unwrap());
    if chunk == 0 || chunk > MAX_CHUNK {
        return Err(Error::Malformed);
    }
    let nonce: [u8; NONCE_LEN] = hdr[9..].try_into().unwrap();
    let mut dec =
        stream::DecryptorBE32::from_aead(XChaCha20Poly1305::new((&key.0).into()), (&nonce).into());

    let ct_len = chunk as usize + TAG_LEN;
    let mut buf = vec![0u8; ct_len];
    let mut cur = read_full(&mut src, &mut buf)?;
    let mut sink = Sink::default();

    if cur == 0 {
        return Err(Error::Malformed);
    }
    loop {
        let mut next = vec![0u8; ct_len];
        let nlen = if cur == ct_len { read_full(&mut src, &mut next)? } else { 0 };
        if nlen == 0 {
            let p = dec.decrypt_last(&buf[..cur]).map_err(|_| Error::Crypto)?;
            sink.push(&p, &mut dst)?;
            break;
        }
        let p = dec.decrypt_next(&buf[..cur]).map_err(|_| Error::Crypto)?;
        sink.push(&p, &mut dst)?;
        buf = next;
        cur = nlen;
    }
    dst.flush()?;

    let meta = sink.meta.ok_or(Error::Malformed)?;
    if sink.written != meta.size || hex(&sink.hasher.finalize()) != meta.sha256 {
        return Err(Error::HashMismatch);
    }
    Ok(meta)
}

/// Splits decrypted plaintext into metadata (header) and file bytes (body).
#[derive(Default)]
struct Sink {
    head: Vec<u8>,
    meta: Option<Meta>,
    hasher: Sha256,
    written: u64,
}

impl Sink {
    fn push(&mut self, mut data: &[u8], dst: &mut impl Write) -> Result<()> {
        if self.meta.is_none() {
            self.head.extend_from_slice(data);
            data = &[];
            if self.head.len() >= 4 {
                let l = u32::from_be_bytes(self.head[..4].try_into().unwrap());
                if l > MAX_META {
                    return Err(Error::Malformed);
                }
                let l = l as usize;
                if self.head.len() >= 4 + l {
                    self.meta = Some(
                        serde_json::from_slice(&self.head[4..4 + l]).map_err(|_| Error::Malformed)?,
                    );
                    let rest = self.head.split_off(4 + l);
                    self.head = Vec::new();
                    return self.body(&rest, dst);
                }
            }
        }
        self.body(data, dst)
    }

    fn body(&mut self, data: &[u8], dst: &mut impl Write) -> Result<()> {
        self.hasher.update(data);
        self.written += data.len() as u64;
        dst.write_all(data)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(data: &[u8], chunk: u32) -> Result<Vec<u8>> {
        let key = LibraryKey::generate();
        let (sha, size) = hash_reader(data)?;
        let meta = Meta { name: "a.jpg".into(), size, sha256: sha, modified: Some(1) };
        let mut ct = Vec::new();
        encrypt_with_meta(&key, &meta, data, &mut ct, chunk)?;
        let mut out = Vec::new();
        let m = decrypt(&key, &ct[..], &mut out)?;
        assert_eq!(m, meta);
        Ok(out)
    }

    #[test]
    fn roundtrips_various_sizes() {
        for chunk in [16u32, 64, 1000] {
            for len in [0usize, 1, 15, 16, 17, 100, 1000, 1001, 5000, 64 * 3] {
                let data: Vec<u8> = (0..len).map(|i| (i * 31 % 251) as u8).collect();
                assert_eq!(roundtrip(&data, chunk).unwrap(), data, "chunk {chunk} len {len}");
            }
        }
    }

    #[test]
    fn wrong_key_fails() {
        let (k1, k2) = (LibraryKey::generate(), LibraryKey::generate());
        let meta = Meta { name: "x".into(), size: 3, sha256: hash_reader(&b"abc"[..]).unwrap().0, modified: None };
        let mut ct = Vec::new();
        encrypt_with_meta(&k1, &meta, &b"abc"[..], &mut ct, 64).unwrap();
        assert!(matches!(decrypt(&k2, &ct[..], io::sink()), Err(Error::Crypto)));
    }

    #[test]
    fn tampering_and_truncation_detected() {
        let key = LibraryKey::generate();
        let data = vec![7u8; 500];
        let meta = Meta { name: "x".into(), size: 500, sha256: hash_reader(&data[..]).unwrap().0, modified: None };
        let mut ct = Vec::new();
        encrypt_with_meta(&key, &meta, &data[..], &mut ct, 64).unwrap();
        let mut bad = ct.clone();
        let i = bad.len() / 2;
        bad[i] ^= 1;
        assert!(decrypt(&key, &bad[..], io::sink()).is_err());
        // Dropping the final chunk must not pass as a complete file.
        let cut = &ct[..ct.len() - (64 + TAG_LEN)];
        assert!(decrypt(&key, cut, io::sink()).is_err());
    }

    #[test]
    fn key_base64_roundtrip() {
        let k = LibraryKey::generate();
        assert_eq!(LibraryKey::from_base64(&k.to_base64()).unwrap().0, k.0);
        assert!(LibraryKey::from_base64("short").is_err());
    }

    #[test]
    fn hides_file_name() {
        let key = LibraryKey::generate();
        let meta = Meta { name: "secret-name.jpg".into(), size: 1, sha256: hash_reader(&b"x"[..]).unwrap().0, modified: None };
        let mut ct = Vec::new();
        encrypt_with_meta(&key, &meta, &b"x"[..], &mut ct, 64).unwrap();
        assert!(!ct.windows(6).any(|w| w == b"secret"));
    }
}

#[cfg(test)]
mod lan_token {
    use super::*;

    /// Same vector is asserted in the Kotlin tests: the two sides must derive identical tokens.
    #[test]
    fn lan_token_is_stable_across_implementations() {
        let key = LibraryKey::from_base64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").unwrap();
        assert_eq!(key.lan_auth_token(), "a705ba1efe81f5e88a6bde701f73204c7dade81427253c5881607ff21720cbcc");
        assert_ne!(key.lan_auth_token(), LibraryKey::generate().lan_auth_token());
    }
}

#[cfg(test)]
mod compat {
    use super::*;

    /// Written by the Android (Kotlin) tests; proves phone-encrypted files open on the PC.
    #[test]
    fn decrypts_file_encrypted_by_kotlin() {
        let key = LibraryKey::from_base64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").unwrap();
        let ct = include_bytes!("../tests/fixtures/kotlin.cwnd");
        let mut out = Vec::new();
        let meta = decrypt(&key, &ct[..], &mut out).unwrap();
        assert_eq!(meta.name, "kotlin ü.jpg");
        let want: Vec<u8> = (0..3000usize).map(|i| (i * 7 % 253) as u8).collect();
        assert_eq!(out, want);
    }
}
