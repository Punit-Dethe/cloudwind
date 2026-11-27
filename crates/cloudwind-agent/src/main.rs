mod inbox;
mod remote;
mod state;
mod sync;

use std::{fs, path::PathBuf, time::UNIX_EPOCH};

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
    /// Show what is waiting in the inbox and whether the phone is still backing up.
    Status,
    /// Print the key for pairing the phone.
    Pair,
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
            let cfg = Config { key: key.to_base64(), library: library.clone(), inbox: inbox.clone(), worker };
            fs::create_dir_all(path.parent().unwrap())?;
            write_private(&path, &serde_json::to_vec_pretty(&cfg)?)?;
            println!("Created {path:?}\nLibrary key (keep a copy somewhere safe): {}", cfg.key);
        }
        Cmd::Pair => {
            let (cfg, _) = load(&path)?;
            // This payload is what the QR code will carry: key + Worker URL + phone token.
            let payload = serde_json::json!({
                "v": 1,
                "key": cfg.key,
                "url": cfg.worker.as_ref().map(|w| &w.url),
                "token": cfg.worker.as_ref().and_then(|w| w.phone_token.as_ref()),
            });
            use cloudwind_core::base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
            if cfg.worker.as_ref().and_then(|w| w.phone_token.as_ref()).is_none() {
                bail!("no phone token in config: re-run init with --phone-token (or CLOUDWIND_PHONE_TOKEN)");
            }
            let code = format!("cloudwind://pair/{}", URL_SAFE_NO_PAD.encode(payload.to_string()));
            let qr = qrcode::QrCode::new(code.as_bytes())?
                .render::<qrcode::render::unicode::Dense1x2>()
                .quiet_zone(true)
                .build();
            println!("{qr}\nScan this with the Cloudwind app. It contains your encryption key: don't share a screenshot.\n\nOr paste this code into the app:\n{code}");
        }
        Cmd::Sync => {
            let (cfg, key) = load(&path)?;
            let mut st = state::State::load(&path.parent().context("config has no parent dir")?.join("state"))?;
            let r = sync::run(open_inbox(&cfg)?.as_ref(), &key, &cfg.library, &mut st)?;
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
