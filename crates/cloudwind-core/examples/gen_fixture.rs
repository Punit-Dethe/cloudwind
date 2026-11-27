//! Writes a small multi-chunk fixture the Android (Kotlin) tests must decrypt.
//! Key = bytes 0..32, plaintext = bytes (i*31 % 251) for i in 0..1000, chunk = 64.
//! Run: cargo run -p cloudwind-core --example gen_fixture -- <out.cwnd>
use cloudwind_core::{encrypt_with_meta, hash_reader, LibraryKey, Meta};

fn main() {
    let out = std::env::args().nth(1).expect("output path");
    let key = LibraryKey::from_base64("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8").unwrap();
    let data: Vec<u8> = (0..1000usize).map(|i| (i * 31 % 251) as u8).collect();
    let (sha256, size) = hash_reader(&data[..]).unwrap();
    let meta = Meta { name: "fixture ü.jpg".into(), size, sha256, modified: Some(1_700_000_000) };
    let mut f = std::fs::File::create(out).unwrap();
    encrypt_with_meta(&key, &meta, &data[..], &mut f, 64).unwrap();
}
