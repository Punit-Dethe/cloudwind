# Cloudwind

Back up phone photos even when your home PC is off. The phone uploads **encrypted**
photos to a cheap cloud inbox; a tiny agent on your PC pulls them into your photo
folder whenever it's on, verifies them, and tells the phone it's safe to delete.
Your photo app (Immich, PhotoPrism, Nextcloud, ...) imports from that folder.

Full plan: [docs/PLAN.md](docs/PLAN.md)

## Status — Phase 1 (pipeline)

- [x] `cloudwind-core`: chunked XChaCha20-Poly1305 file format, encrypted metadata (names never leave the phone), SHA-256 verification
- [x] `cloudwind` agent: `init`, `pair`, `sync`, `upload` (phone simulator), local-folder or Worker inbox
- [x] Cloudflare Worker (`worker/`): R2 inbox + D1 status, phone/agent token roles, multipart uploads, daily purge after the grace period
- [x] Verified end to end on Cloudflare: 300 KB photo and 70 MB video (multipart) round-trip byte-identical
- [x] Android app (Kotlin + Compose): QR/paste pairing, Keystore-wrapped key, MediaStore scan, encrypt + upload, WorkManager (periodic + new-photo trigger + foreground for backlogs), status back from the PC, batch "Free up space" with one system prompt per 500 photos. Compiles to a debug APK; **not yet run on a real phone**
- [x] `cloudwind pair` prints a QR code for the phone
- [ ] Tray icon, start at login, OS keychain, QR pairing, Immich/PhotoPrism presets

## Architecture note: uploads go through the Worker in parts

The plan called for presigned R2 links. Phase 1 instead uploads in ~8 MB multipart
parts through the Worker (each far below the 100 MB body limit) using the R2 binding.
This needs no R2 S3 keys, so setup is one token pair. Cost: one Worker request per part
(free plan: 100,000/day, so roughly 800 GB/day of uploads) and Worker CPU stays tiny
because bodies are streamed. Switching to presigned links later only changes the
phone and agent transport, not the file format.

## Deploy the inbox

```sh
cd worker && npm install
export CLOUDFLARE_API_TOKEN=...   # Workers Scripts, D1, Workers R2 Storage: Edit; Account Settings: Read
npx wrangler r2 bucket create cloudwind-inbox
npx wrangler d1 create cloudwind-status      # put the printed id in wrangler.toml
npx wrangler d1 execute cloudwind-status --remote --file=schema.sql
npx wrangler deploy
openssl rand -hex 24 | tee /dev/stderr | npx wrangler secret put PHONE_TOKEN
openssl rand -hex 24 | tee /dev/stderr | npx wrangler secret put AGENT_TOKEN
BASE=https://cloudwind.<you>.workers.dev PHONE=... AGENT=... ./smoke.sh
```

## Try the agent

```sh
cargo build --release
export CLOUDWIND_AGENT_TOKEN=... CLOUDWIND_PHONE_TOKEN=...
target/release/cloudwind init --library ~/Pictures/Cloudwind --worker-url https://cloudwind.<you>.workers.dev
target/release/cloudwind upload some-photo.jpg   # stands in for the phone
target/release/cloudwind sync                    # download, decrypt, verify, write, confirm
target/release/cloudwind pair                    # pairing payload for the phone (QR later)
```

Add `--inbox /some/folder` instead of `--worker-url` to test with a local folder and no cloud.

## Android app

`android/` is a Gradle project with two modules:

- `:core` — plain Kotlin/JVM: file format, in-house ChaCha20-Poly1305, pairing, Worker client. Builds and tests with only a JDK
  (`cd android && ./gradlew :core:test`). Byte-compatible with the Rust crate in both directions (fixtures in both test suites).
- `:app` — the Android UI/background code. Needs the Android SDK (`ANDROID_HOME` or `local.properties`); build with
  `./gradlew :app:assembleDebug`. minSdk 30 (Android 11, for batch delete with one prompt).

Live end-to-end check (Kotlin client -> Worker -> Rust agent, includes a 70 MB multipart upload):

```sh
CLOUDWIND_E2E_URL=https://cloudwind.<you>.workers.dev CLOUDWIND_E2E_PHONE_TOKEN=... CLOUDWIND_E2E_DIR=<dir with IMG_*/VID_* files> \
  ./gradlew :core:test --tests '*E2eTest*'
```

## Limits and edge cases

R2's 10 GB is a monthly *average*, not a wall: going over costs about $0.015 per GB-month, not an outage.
The hard wall is the Workers free plan's 100,000 requests a day. Cloudwind's guards, all set in `worker/wrangler.toml`
(change a value, then `npx wrangler deploy`):

| Guard | Default | What happens |
| --- | --- | --- |
| `INBOX_CAP_BYTES` | 8 GiB | Uploads that would exceed it get `507 inbox_full` plus the free bytes. In-flight multipart uploads reserve their declared size. 8 GiB is under R2's free 10 GB, so it stays at $0 even if full all month. |
| `SOFT_FRACTION` | 0.6 | Above 60% of the cap, already-synced copies are evicted oldest-first to make room. Unsynced objects are **never** evicted. |
| `GRACE_DAYS` | 7 | Daily cron deletes synced copies after this long; also aborts multipart uploads abandoned for 24 h. |
| `MAX_OBJECT_BYTES` | 5 GiB | Larger files are refused permanently (`413`); the phone sets them aside as "too large" and never retries. |
| `MAX_DOWNLOADS` | 5 | Full downloads allowed per object, so a looping agent or stolen agent token can't hammer egress. Resumed (`Range`) tails don't count. |
| `LIMITER` | 600 req/min per role | Phone and agent are limited separately; clients back off on `429`. |

Rough budget: one photo costs about 3 Worker requests (upload, download, confirm), so the 100,000/day free limit is tens of
thousands of photos a day.

What the clients do around those limits:

- **Phone, inbox full:** skips items that don't fit, keeps uploading smaller ones, shows "Cloud inbox is full", and retries on the next pass. Originals are never touched, and "Free up space" only appears after the PC verified a file.
- **Phone, over-limit file:** set aside permanently and counted in the UI.
- **Phone, newest first:** recent photos are backed up before an old backlog.
- **Phone, file changing while it is read** (a video still recording): the upload is refused and retried later, instead of shipping a hash the PC would reject forever.
- **Phone, low on free space:** big videos are staged as an encrypted temp copy; if there isn't room it skips them with a notice.
- **PC, flaky internet:** downloads resume from the byte where they stopped, with retry backoff.
- **PC, corrupt or undecryptable object:** discarded, retried with growing backoff (1 min doubling to 6 h), never in a tight loop.
- **PC, duplicates:** the agent remembers file hashes (`state/seen.txt`), so a re-paired phone or overlapping "back up older photos" doesn't create a second copy; it still confirms the object so the phone can delete.
- **PC, file names:** sanitized for Windows (reserved names, `: * ? " < > |`, trailing dots/spaces, over-long names).

Recommended: in the Cloudflare dashboard create a billing/usage notification for R2 so a misconfiguration can't surprise you.

Tests: `worker/limits-test.sh` (needs a local Worker with tiny caps, see its header), `worker/smoke.sh`, `cargo test`, `cd android && ./gradlew :core:test`.
