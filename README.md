# Cloudwind

Back up phone photos even when your home PC is off. The phone uploads **encrypted**
photos to a cheap cloud inbox; a tiny agent on your PC pulls them into your photo
folder whenever it's on, verifies them, and tells the phone it's safe to delete.
Your photo app (Immich, PhotoPrism, Nextcloud, ...) imports from that folder.

Full plan: [docs/PLAN.md](docs/PLAN.md)

## Status — Phase 1 (pipeline)

- [x] `cloudwind-core`: chunked XChaCha20-Poly1305 file format, encrypted metadata (names never leave the phone), SHA-256 verification
- [x] `cloudwind` agent: `init`, `pair`, `sync`, `upload` (phone simulator) against a local inbox folder
- [ ] R2/S3 inbox backend (signed links, grace-period cleanup)
- [ ] Worker + D1 status table
- [ ] Android app
- [ ] Tray icon, start at login, OS keychain, QR pairing, Immich/PhotoPrism presets

## Try it

```sh
cargo build
cloudwind init --library ~/Pictures/Cloudwind --inbox /tmp/cw-inbox
cloudwind upload some-photo.jpg   # stands in for the phone
cloudwind sync                    # decrypt + verify + write to the library
```
