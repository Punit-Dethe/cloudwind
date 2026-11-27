# Direct sync (phone ⇄ PC over the home network) — plan

Cloud is the fallback for when the PC can't be reached. When the phone and PC are on the same network, bytes go
straight across: faster, no size/quota limits, and nothing leaves the house.

## Principles
1. **One route per file at a time.** No racing cloud against LAN on the same file. Parallelism is across *different*
   files: the phone sends phone-only files directly while the PC pulls files already in the cloud.
2. **The PC arbitrates.** The phone asks "I have these; which do you want?" The PC owns the ledger of what it has
   (SHA-256 set), so duplicates, already-pulled and already-in-cloud files cost no bytes.
3. **The cloud copy is a cache.** When a file arrives directly, the PC marks its cloud object synced, so it can be evicted.
4. **Gates only restrain big files.** Small files always go (within cloud limits).

## Protocol (LAN, v1) — HTTPS, pinned self-signed certificate
- The PC generates a certificate once; the pairing QR carries its SHA-256 fingerprint and port. The phone pins it.
  No CA, no trust-on-first-use over the air.
- Auth: `Authorization: Bearer HMAC-SHA256(library_key, "cloudwind/lan-auth/v1")`. Only paired phones have the key.
- Files go raw over TLS (the LAN is not an untrusted store, so no `.cwnd` wrapping); the SHA-256 is verified on arrival.

| Call | Purpose |
| --- | --- |
| `GET /lan/v1/hello` | liveness + protocol version |
| `POST /lan/v1/plan` | phone sends `[{id, sha256, size, name, modified, object_id?}]`; PC replies `have[]`, `cloud[]` (PC will pull these), `want[{id, offset}]` (send me these, resume at `offset`) |
| `PUT /lan/v1/file/<sha256>?offset=N&size=S&name=…&modified=…&object=…` | append bytes; PC verifies hash at the end, places the file atomically, marks the cloud object synced |

Partial files live on the PC keyed by hash, so a dropped connection resumes from the byte the PC actually has.

## Finding the PC
1. last address that worked, 2. an encrypted hint the PC publishes to the Worker every minute (`PUT /v1/lan`), 3. later: mDNS.
Reachability is proven by a successful pinned handshake each time; SSID names are never trusted.

## Route choice (per file, just before it starts)
| Situation | Route |
| --- | --- |
| Home-only folder | LAN, else wait ("waiting for home network") |
| PC reachable | LAN (any size) |
| PC not reachable, small file | cloud, any connection |
| PC not reachable, big file | cloud if gates pass (Wi-Fi if enabled; charging **or** battery ≥ X% if enabled) |
| Too big for the cloud / inbox full | LAN, else wait |
| Already in cloud | nothing from the phone; PC pulls (or asks for it directly if it can't) |

Gates (all user-settable): "big" size (default 50 MB), Wi-Fi-only for big files (default on), battery gate on/off,
battery minimum 30–90 % (default 40). The battery gate also applies to big direct sends; the Wi-Fi gate does not.

## Failures
- Direct send drops: PC keeps the partial; retry ≈3× over ~a minute; then fall back to the cloud unless Home-only.
- Cloud upload in progress and the PC appears: finish if < 64 MB remain, else abort the cloud upload (frees its reserved
  quota) and go direct. A file switches route at most once per few minutes.
- Same bytes arrive by both routes: the PC stores one copy and confirms both (safety net, not the plan).
- PC asleep / phone away: nothing special; progress is saved, the stalled-backup warning still applies.

## Folders
Camera (`DCIM/…`) is cloud-OK by default. With "all folders" on, other folders default to **Home only**; each folder
can be flipped either way.

## Status
Steps 1–3 below are built and tested (Kotlin ⇄ real Rust agent over pinned TLS, plus live checks against the Worker). Not yet verified on a real phone / real home network.

## Build order
1. PC: LAN server + ledger + `cloudwind run` daemon (cloud pull + hint heartbeat).   *(tested against the real agent from Kotlin)*
2. Phone core: pinned LAN client, route planner + gates, cloud-upload abort.
3. App: hashing cache, route integration, gate settings, folder policy.
4. Later: mDNS discovery, optional extra address (Tailscale/WireGuard users), tray + start at login.
5. **Last:** "Send to PC" quick share (share-sheet target + a small floating notification on the PC), built on the same link.
