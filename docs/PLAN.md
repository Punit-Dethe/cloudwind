# Cloudwind — offline-friendly photo inbox: product plan

Nov 27, 2026 · @Punit Dethe

## Summary

Cloudwind lets your phone back up photos even when your home PC is off. The phone uploads encrypted photos to a cheap cloud inbox. A tiny agent on the PC pulls them into your photo folder whenever the PC is on, then empties the inbox.

Your photo app (Immich, PhotoPrism, Nextcloud and others) imports them the next time you start it. Syncing never waits on Docker or a running photo server.

The gap: Immich-style apps only back up while the server is reachable, and Syncthing needs both devices online at once. No existing tool ships the whole loop, including "safe to delete" shown back on the phone.

## Goals and non-goals

Version 1 targets Android phones and Windows or Mac PCs; iOS follows once Android proves the model.

**Goals**

- Photos leave the phone within minutes, whether or not the PC is on.
- PC sync is one small binary: no Docker, no running photo server.
- Works with any folder-based photo app through presets.
- The cloud only ever stores encrypted data.
- The phone shows exactly when each photo is safe to delete.

**Non-goals for now**

- iOS.
- Writing into Immich's database or managed storage folder. The schema changes between versions, so we use external libraries or the public API instead.
- Acting as a long-term cloud archive. The inbox is emptied after sync.
- Browsing photos in the cloud while the PC is off.

## How it works

The cloud is a waiting room, not a library: photos sit there only until the PC collects them.

&#91;embedded content: end-to-end flow · 5 stages, status loop back to the phone\]

Every photo moves through five states, shown on both the phone and the PC dashboard:

1. **On phone:** picked by your upload rules.
2. **Uploaded:** an encrypted copy is in the cloud inbox.
3. **Synced:** the PC has written the file and its hash matches.
4. **Imported:** the photo app has picked it up, where the preset can detect it.
5. **Safe to delete:** the phone offers one-tap cleanup.

The cloud copy is removed after a grace period once the photo is synced.

## Architecture

Three small parts: an Android app, a Cloudflare inbox on the free tier, and one Rust binary on the PC.

&#91;embedded content: architecture · phone, Cloudflare, PC agent, photo app\]

The phone never sends files through the Worker; the Worker only hands out signed links and keeps the status table.

| Component | Built with | Job |
| --- | --- | --- |
| Android app | Kotlin, WorkManager (or Flutter if iOS should share code) | Pick what to upload, encrypt, upload, show status, batch delete |
| R2 inbox bucket | Cloudflare R2 (Backblaze B2 as a bring-your-own option) | Hold encrypted files until the PC collects them |
| Worker + D1 | Cloudflare Worker, D1 database | Sign upload links, store per-photo status, send push notifications |
| PC agent | Rust: tokio, an S3 client, axum for the dashboard | Pull, verify, decrypt, write to the folder; tray icon; start at login |
| Shared crate | Rust, used by the agent and the phone app | One encryption, file format and hashing implementation, so both sides always agree |
| App presets | Config files in the agent | Folder layout and rescan hook for Immich, PhotoPrism, Nextcloud and others |

## Security and privacy

The cloud holds only ciphertext, and the key exists only on your phone and PC.

- **Encrypt on the phone.** Each file is encrypted in chunks (e.g. XChaCha20-Poly1305 streaming) so large videos never load whole into memory. File names and metadata are encrypted too; objects get random IDs.
- **Pair by QR code.** The PC agent generates the library key and shows a QR code with the key and inbox details. The phone scans it, and nothing is typed or sent through a server.
- **Store keys safely.** Android Keystore on the phone; Windows Credential Manager or macOS Keychain on the PC. Offer a printable recovery code.
- **Scope the phone's access.** The phone never holds a full bucket key: either a write-only token for the inbox, or short-lived signed upload links from the Worker.
- **Verify every file.** The phone records a hash of the original; the agent checks it after decrypting and re-downloads on mismatch.
- **Delete safely.** "Safe to delete" appears only after the PC confirms the hash. The cloud copy stays for a grace period (default 7 days) after sync. Phone deletions go through one system prompt per batch.
- **Warn about single copies.** Once the phone copy is gone, the PC is the only copy, so setup recommends a separate backup of the photo folder.

## Platform limits that shape the design

Cloud costs stay near zero because the inbox drains; the hard limits are big videos and Android background killing.

| Limit | Figure | What we do |
| --- | --- | --- |
| R2 free storage | 10 GB-month | Inbox empties after sync; warn when the backlog nears 10 GB (about 3,000 photos, far fewer 4K videos) |
| R2 free operations | 1M writes/lists, 10M reads per month | A few writes per photo; polling once a minute is about 43,000 lists a month |
| R2 egress | Free | PC downloads cost nothing |
| R2 paid storage | $0.015 per GB-month | A 50 GB video backlog costs about $0.75 a month |
| Backblaze B2 (alternative) | 10 GB free, $6.95 per TB-month, egress free up to 3× storage | Offer as a bring-your-own option |
| Google Drive (alternative) | 750 GB upload per user per day; consumer 15 GB shared with Gmail | Possible later backend |
| Workers request body | 100 MB on Free and Pro plans | Never send files through the Worker; the phone uploads straight to R2 with signed multipart links |
| Workers free plan | 100,000 requests a day, 10 ms CPU each | Worker only signs links and records status; no encryption there |
| Android battery savers (Xiaomi, Oppo, Vivo, Samsung) | Kill background jobs | Per-brand setup screen to exempt the app; WorkManager with Wi-Fi and charging rules; foreground service for big backlogs |
| Deleting photos | Android 11+ requires a system confirmation | Batch deletions behind one prompt |
| iOS background time | About 30 seconds after closing; force-quit stops uploads | The main reason iOS waits |

Even Immich's own Android app has open bugs where backup stalls once the app is backgrounded, so reliability here is a selling point.

## Existing tools and how this differs

Each piece exists, but no tool joins the phone, an encrypted inbox and the PC with status flowing back.

| Tool | What it covers | What's missing |
| --- | --- | --- |
| [PhotoSync](https://www.photosync-app.com/) | Android and iOS upload to S3, Backblaze, Wasabi, NAS or PC; Windows and Mac companion | No cloud-to-PC pull, no "synced" status; free version sends low-quality copies |
| [Pho](https://pho.tools/) | Phone upload to SMB, WebDAV, NFS, Google Drive or S3 | Upload only |
| FolderSync | Android sync to S3 and many clouds | Upload only |
| [Immich Folder Watch](https://github.com/VoltKraft/immich-folder-watch) | Windows and Linux app that uploads new files in a folder to Immich | Needs Immich running; no cloud inbox; no Mac |
| [autoupload](https://github.com/jwillikers/autoupload) | Linux service that uploads a folder to S3 or Immich when online | Opposite direction; Linux only |
| Syncthing | Device-to-device sync | [Both devices must be online at the same time](https://forum.syncthing.net/t/do-2-devices-have-to-be-online-at-the-time-in-order-for-syncronisation-to-occur/20289) |
| Immich mobile app | Phone backup to Immich | Server must be reachable; [backgrounded backups stall on Android](https://github.com/immich-app/immich/issues/31042) |

Where Cloudwind differs: the cloud is a temporary inbox, files are encrypted on the phone, a Docker-free agent runs on Windows and Mac, any folder-based photo app works, and status flows back so deleting is safe. A May 2026 Immich request for a [native drop folder](https://github.com/immich-app/immich/discussions/28700) shows the demand.

## Roadmap

Phase 1 is the one to build now: a working pipeline for your own photos before any polish.

&#91;embedded content: roadmap · 5 phases, 4 gates\]

No dates yet; set them once the Phase 0 spike shows how fast the upload and agent pieces come together.

## Risks and open decisions

The biggest risk is Android background reliability; the biggest open choice is who owns the bucket.

**Risks**

- **Background uploads stall on Android.** Fallback: a foreground service with a visible notification during large backlogs, plus clear status in the app.
- **A lost key means unsynced photos are unreadable.** Fallback: a recovery code at pairing, and phone copies kept until synced.
- **Immich external library quirks.** Files stay managed by the folder, not Immich. Fallback: upload through Immich's API when it is running.
- **Small market.** People with an always-on NAS or mini PC don't need this. Fallback: validate before building past the MVP.
- **Hosting cost and liability** if we run the bucket for users. Fallback: bring-your-own bucket first.

**Open decisions**

- Bring-your-own bucket only, or also a hosted inbox?
- Status sync: a status file in the bucket, or a Worker with a D1 database?
- Phone stack: native Kotlin, or Flutter to reach iOS later from one codebase?
- Encryption format: libsodium secretstream, or age?
- Immich preset: external-library folder drop, or API upload when the server is running?
- Final product name.

## Next steps this week

- [ ] Post the flow on r/selfhosted and r/immich and note who would use it, and with which photo app
- [ ] Create an R2 bucket with a write-only token; test a signed multipart upload from an Android phone
- [ ] Spike the Rust agent: list the inbox, download, decrypt, write to a folder
- [ ] Try an Immich external library with file watching on, on Windows
- [ ] Decide bring-your-own versus hosted, and Kotlin versus Flutter

## Sources

- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/)
- [Cloudflare Workers limits](https://developers.cloudflare.com/workers/platform/limits/)
- [Backblaze B2 pricing](https://www.backblaze.com/cloud-storage/pricing)
- [Google Workspace storage and upload limits](https://support.google.com/a/answer/172541)
- [Immich external libraries](https://docs.immich.app/features/libraries/)
- [Immich Android background backup issue #31042](https://github.com/immich-app/immich/issues/31042)
- [iOS background uploads (Gigantti help article)](https://intercom.help/gigantticloud/en/articles/11737962-background-uploads-on-ios)
