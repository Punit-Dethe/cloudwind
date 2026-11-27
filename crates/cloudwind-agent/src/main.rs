mod inbox;
mod lan;
mod remote;
mod state;
mod sync;

use std::{fs, path::PathBuf, sync::Arc, time::{Duration, UNIX_EPOCH}};

use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use cloudwind_core::{encrypt, LibraryKey};
use serde::{Deserialize, Serialize};

use inbox::{DirInbox, Inbox};
use remote::RemoteInbox;

#[derive(Parser)]
#[command(name = "cloudwind", version, about = "Cloudwind PC agent")]
struct Cli {
    /// Config file (default: ~/.config/cloudwind/config.json)
    #[arg(long, global = true)]
    config: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Create a library key and config.
    Init {
        /// Folder where decrypted photos are written (your photo app watches this).
        #[arg(long)]
        library: PathBuf,
        /// Use a local folder as the inbox (for testing).
        #[arg(long, conflicts_with = "worker_url")]
        inbox: Option<PathBuf>,
        /// Cloudwind Worker URL, e.g. https://cloudwind.you.workers.dev
        #[arg(long, requires = "agent_token")]
        worker_url: Option<String>,
        /// Token this agent uses with the Worker.
        #[arg(long, env = "CLOUDWIND_AGENT_TOKEN", hide_env_values = true)]
        agent_token: Option<String>,
        /// Token the phone uses (goes into the pairing code).
        #[arg(long, env = "CLOUDWIND_PHONE_TOKEN", hide_env_values = true)]
        phone_token: Option<String>,
    },
    /// Pull new photos from the inbox into the library.
    Sync,
    /// Run continuously: receive directly from the phone on the home network, pull from the cloud,
    /// and keep the phone able to find this PC. This is what to leave running (tray/start-at-login later).
    Run {
        /// Address to listen on for the phone.
        #[arg(long, default_value = "0.0.0.0")]
        bind: String,
        /// Port for the direct receiver (default: the one in the pairing code).
        #[arg(long)]
        port: Option<u16>,
        /// Seconds between cloud checks and address announcements.
        #[arg(long, default_value_t = 60)]
        interval: u64,
    },
    /// Show what is waiting in the inbox and whether the phone is still backing up.
    Status,
    /// Show the pairing QR code for the phone.
    Pair {
        /// Your terminal has a light background (the default assumes a dark one).
        #[arg(long)]
        light: bool,
        /// Also write the QR code as an image (open it in a browser) for consoles that garble it.
        #[arg(long, value_name = "FILE")]
        svg: Option<PathBuf>,
    },
    /// Encrypt a file into the inbox, as the phone will (for testing).
    Upload { file: PathBuf },
}

#[derive(Serialize, Deserialize)]
struct Config {
    key: String,
    library: PathBuf,
    /// Local-folder inbox (testing).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    inbox: Option<PathBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    worker: Option<WorkerCfg>,
    /// Port for the direct (LAN) receiver; also what the pairing QR advertises.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    lan_port: Option<u16>,
}

#[derive(Serialize, Deserialize)]
struct WorkerCfg {
    url: String,
    agent_token: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    phone_token: Option<String>,
}

fn open_inbox(cfg: &Config) -> Result<Box<dyn Inbox>> {
    match (&cfg.worker, &cfg.inbox) {
        (Some(w), _) => Ok(Box::new(RemoteInbox::new(&w.url, &w.agent_token))),
        (None, Some(d)) => Ok(Box::new(DirInbox { root: d.clone() })),
        _ => bail!("config has no inbox"),
    }
}

fn config_path(cli: &Cli) -> Result<PathBuf> {
    if let Some(p) = &cli.config {
        return Ok(p.clone());
    }
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
    Ok(PathBuf::from(home.context("no home directory")?).join(".config/cloudwind/config.json"))
}

fn load(path: &PathBuf) -> Result<(Config, LibraryKey)> {
    let raw = fs::read_to_string(path)
        .with_context(|| format!("no config at {path:?}; run `cloudwind init` first"))?;
    let cfg: Config = serde_json::from_str(&raw)?;
    let key = LibraryKey::from_base64(&cfg.key)?;
    Ok((cfg, key))
}

fn state_dir(config: &PathBuf) -> Result<PathBuf> {
    Ok(config.parent().context("config has no parent dir")?.join("state"))
}

/// Private IPv4 addresses of this machine: what the phone should try on the home network.
fn local_addrs() -> Vec<String> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .filter_map(|i| match i.ip() {
            std::net::IpAddr::V4(v4) if v4.is_private() => Some(v4.to_string()),
            _ => None,
        })
        .collect()
}

/// Encrypted with the library key, so the cloud only ever sees an opaque blob.
fn lan_hint(key: &LibraryKey, port: u16) -> Result<String> {
    use cloudwind_core::base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
    let now = std::time::SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs();
    let json = serde_json::json!({ "addrs": local_addrs(), "port": port, "at": now }).to_string();
    let mut ct = Vec::new();
    encrypt(key, "lan", None, || Ok(json.as_bytes()), &mut ct)?;
    Ok(URL_SAFE_NO_PAD.encode(ct))
}

fn daemon(cfg: Config, key: LibraryKey, state_dir: PathBuf, bind: &str, port: Option<u16>, interval: u64) -> Result<()> {
    use std::io::Write;
    let state = Arc::new(state::State::load(&state_dir)?);
    let identity = lan::load_or_create_identity(&state_dir)?;
    let remote: Option<Arc<RemoteInbox>> = cfg.worker.as_ref().map(|w| Arc::new(RemoteInbox::new(&w.url, &w.agent_token)));
    let link: Option<Arc<dyn lan::CloudLink>> = remote.clone().map(|r| r as Arc<dyn lan::CloudLink>);

    let port = port.or(cfg.lan_port).unwrap_or(lan::DEFAULT_PORT);
    let (server, addr) = lan::start(&identity, bind, port)?;
    println!("listening on {addr} (certificate {})", identity.fingerprint);
    std::io::stdout().flush()?;

    let receiver = Arc::new(lan::Lan::new(cfg.library.clone(), state.clone(), &key, link));
    std::thread::spawn(move || lan::serve(receiver, server));

    let inbox = open_inbox(&cfg)?;
    loop {
        // Cloud route: pull whatever the phone uploaded while it was away.
        match sync::run(inbox.as_ref(), &key, &cfg.library, &state) {
            Ok(r) => {
                for p in &r.synced {
                    println!("synced {}", p.display());
                }
                for (id, e) in &r.failed {
                    eprintln!("FAILED {id}: {e}");
                }
            }
            Err(e) => eprintln!("cloud check failed: {e:#}"),
        }
        // Let the phone find us. Best effort: the LAN route also works from the last-known address.
        if let Some(r) = &remote {
            if let Err(e) = lan_hint(&key, addr.port()).and_then(|h| r.put_lan_hint(&h)) {
                eprintln!("could not announce address: {e:#}");
            }
        }
        std::thread::sleep(Duration::from_secs(interval));
    }
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let path = config_path(&cli)?;
    match &cli.cmd {
        Cmd::Init { library, inbox, worker_url, agent_token, phone_token } => {
            if path.exists() {
                bail!("{path:?} already exists; refusing to overwrite the key");
            }
            let key = LibraryKey::generate();
            let worker = match (worker_url, agent_token) {
                (Some(url), Some(t)) => Some(WorkerCfg {
                    url: url.trim_end_matches('/').to_string(),
                    agent_token: t.clone(),
                    phone_token: phone_token.clone(),
                }),
                _ => None,
            };
            if worker.is_none() && inbox.is_none() {
                bail!("give --worker-url (with an agent token) or --inbox");
            }
            let cfg = Config { key: key.to_base64(), library: library.clone(), inbox: inbox.clone(), worker, lan_port: None };
            fs::create_dir_all(path.parent().unwrap())?;
            write_private(&path, &serde_json::to_vec_pretty(&cfg)?)?;
            println!("Created {path:?}\nLibrary key (keep a copy somewhere safe): {}", cfg.key);
        }
        Cmd::Pair { light, svg } => {
            let (cfg, _) = load(&path)?;
            // This payload is what the QR code will carry: key + Worker URL + phone token.
            let identity = lan::load_or_create_identity(&state_dir(&path)?)?;
            let payload = serde_json::json!({
                "v": 1,
                "key": cfg.key,
                "fp": identity.fingerprint,
                "port": cfg.lan_port.unwrap_or(lan::DEFAULT_PORT),
                "url": cfg.worker.as_ref().map(|w| &w.url),
                "token": cfg.worker.as_ref().and_then(|w| w.phone_token.as_ref()),
            });
            use cloudwind_core::base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
            if cfg.worker.as_ref().and_then(|w| w.phone_token.as_ref()).is_none() {
                bail!("no phone token in config: re-run init with --phone-token (or CLOUDWIND_PHONE_TOKEN)");
            }
            let code = format!("cloudwind://pair/{}", URL_SAFE_NO_PAD.encode(payload.to_string()));
            // Scanners expect dark modules on a light background. On a dark terminal that means drawing
            // the *light* parts with block characters and leaving the dark parts as the terminal colour.
            use qrcode::render::unicode::Dense1x2;
            let (dark, bg) = if *light { (Dense1x2::Dark, Dense1x2::Light) } else { (Dense1x2::Light, Dense1x2::Dark) };
            let qr = qrcode::QrCode::new(code.as_bytes())?
                .render::<Dense1x2>()
                .dark_color(dark)
                .light_color(bg)
                .quiet_zone(true)
                .build();
            if let Some(path) = svg {
                let image = qrcode::QrCode::new(code.as_bytes())?
                    .render::<qrcode::render::svg::Color>()
                    .min_dimensions(400, 400)
                    .build();
                fs::write(path, image)?;
                eprintln!("Wrote {} (it contains your key: delete it after pairing).", path.display());
            }
            println!("{qr}\nScan this with the Cloudwind app (if it won't scan, try `cloudwind pair --light`). It contains your encryption key: don't share a screenshot.\n\nOr paste this code into the app:\n{code}");
        }
        Cmd::Sync => {
            let (cfg, key) = load(&path)?;
            let st = state::State::load(&state_dir(&path)?)?;
            let r = sync::run(open_inbox(&cfg)?.as_ref(), &key, &cfg.library, &st)?;
            for p in &r.synced {
                println!("synced {}", p.display());
            }
            for (id, e) in &r.failed {
                eprintln!("FAILED {id}: {e}");
            }
            println!(
                "{} synced, {} duplicate(s) skipped, {} waiting to retry, {} failed",
                r.synced.len(), r.duplicates, r.backed_off, r.failed.len()
            );
            if let Some(w) = &cfg.worker {
                // Best effort: a stalled phone is worth a line even when the sync itself went fine.
                if let Ok(h) = RemoteInbox::new(&w.url, &w.agent_token).health() {
                    if phone_stalled(&h) {
                        eprintln!("warning: {}", describe_phone(&h).0);
                    }
                }
            }
            if !r.failed.is_empty() {
                std::process::exit(1);
            }
        }
        Cmd::Run { bind, port, interval } => {
            let (cfg, key) = load(&path)?;
            daemon(cfg, key, state_dir(&path)?, bind, *port, *interval)?;
        }
        Cmd::Status => {
            let (cfg, _) = load(&path)?;
            let w = cfg.worker.context("status needs a Worker inbox (run init with --worker-url)")?;
            let h = RemoteInbox::new(&w.url, &w.agent_token).health()?;
            for line in report_lines(&h) {
                println!("{line}");
            }
        }
        Cmd::Upload { file } => {
            let (cfg, key) = load(&path)?;
            let modified = fs::metadata(file)?
                .modified()?
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|d| d.as_secs());
            let name = file.file_name().context("no file name")?.to_string_lossy();
            let tmp = std::env::temp_dir().join(format!(".cloudwind-up-{}", inbox::random_id()));
            let enc = encrypt(&key, &name, modified, || fs::File::open(file), std::io::BufWriter::new(fs::File::create(&tmp)?));
            let id = inbox::random_id();
            let res = enc.map_err(anyhow::Error::from).and_then(|_| match (&cfg.worker, &cfg.inbox) {
                (Some(w), _) => {
                    let t = w.phone_token.as_deref().context("no phone_token in config (needed to simulate the phone)")?;
                    RemoteInbox::new(&w.url, t).upload(&id, &tmp)
                }
                (None, Some(d)) => DirInbox { root: d.clone() }.put(&fs::read(&tmp)?).map(|_| ()),
                _ => bail!("config has no inbox"),
            });
            let _ = fs::remove_file(&tmp);
            res?;
            println!("uploaded {name} as {id}");
        }
    }
    Ok(())
}

fn write_private(path: &PathBuf, data: &[u8]) -> Result<()> {
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt};
        let mut f = fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(path)?;
        f.write_all(data)?;
    }
    #[cfg(not(unix))]
    fs::write(path, data)?;
    Ok(())
}

const PHONE_QUIET_WARN_SECS: u64 = 48 * 3600;

fn ago(secs: u64) -> String {
    match secs {
        0..=59 => "just now".into(),
        60..=3599 => format!("{} min ago", secs / 60),
        3600..=86399 => format!("{} h ago", secs / 3600),
        _ => format!("{} day{} ago", secs / 86400, if secs / 86400 == 1 { "" } else { "s" }),
    }
}

/// True when the phone has not contacted the inbox for two days. Quiet is only worrying if the
/// phone has been seen before; a fresh install has simply never connected.
fn phone_stalled(h: &remote::Health) -> bool {
    h.phone_last_seen.map_or(false, |t| h.now.saturating_sub(t) > PHONE_QUIET_WARN_SECS)
}

fn describe_phone(h: &remote::Health) -> (String, bool) {
    match h.phone_last_seen {
        None => ("Phone has not contacted the inbox yet.".into(), false),
        Some(t) if phone_stalled(h) => (
            format!(
                "Phone last contacted the inbox {}. Backups may have stopped: open Cloudwind on the phone, and check its battery settings.",
                ago(h.now.saturating_sub(t))
            ),
            true,
        ),
        Some(t) => (format!("Phone last contacted the inbox {}.", ago(h.now.saturating_sub(t))), false),
    }
}

fn mb(b: u64) -> String {
    format!("{:.1} MB", b as f64 / 1_048_576.0)
}

fn report_lines(h: &remote::Health) -> Vec<String> {
    vec![
        format!("Waiting for this PC: {} item(s), {}", h.pending_count, mb(h.pending_bytes)),
        format!("Inbox: {} of {} used", mb(h.inbox_bytes), mb(h.cap_bytes)),
        describe_phone(h).0,
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn health(last_seen: Option<u64>, now: u64) -> remote::Health {
        remote::Health { phone_last_seen: last_seen, pending_count: 3, pending_bytes: 5_242_880, inbox_bytes: 0, cap_bytes: 8 << 30, now }
    }

    #[test]
    fn phone_quiet_for_two_days_is_flagged() {
        let day = 86_400;
        assert!(!phone_stalled(&health(Some(1000), 1000 + day)));
        assert!(phone_stalled(&health(Some(1000), 1000 + 2 * day + 1)));
        assert!(!phone_stalled(&health(None, 9_999_999)), "never seen is not 'stalled'");
    }

    #[test]
    fn report_reads_naturally() {
        let l = report_lines(&health(Some(1000), 1000 + 3 * 3600));
        assert_eq!(l[0], "Waiting for this PC: 3 item(s), 5.0 MB");
        assert!(l[2].contains("3 h ago"));
        assert!(describe_phone(&health(None, 5)).0.contains("not contacted"));
        let stalled = describe_phone(&health(Some(0), 3 * 86_400));
        assert!(stalled.1 && stalled.0.contains("3 days ago") && stalled.0.contains("battery"));
    }
}
