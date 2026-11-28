mod autostart;
mod inbox;
mod install;
mod lan;
mod remote;
mod runtime;
mod state;
mod sync;
mod tray;
mod web;

use std::{
    fs,
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, UNIX_EPOCH},
};

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
    cmd: Option<Cmd>,
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
    /// serve the local web dashboard, and show a system tray icon.
    Run {
        /// Address to listen on for the phone.
        #[arg(long, default_value = "0.0.0.0")]
        bind: String,
        /// Port for the direct receiver (default: the one in the pairing code).
        #[arg(long)]
        port: Option<u16>,
        /// Seconds between cloud checks and address announcements (default: from config or 60).
        #[arg(long)]
        interval: Option<u64>,
        /// Port for the local web dashboard (127.0.0.1).
        #[arg(long)]
        ui_port: Option<u16>,
        /// Do not start the local web dashboard.
        #[arg(long)]
        no_ui: bool,
        /// Do not show the system tray icon (run in terminal only).
        #[arg(long)]
        no_tray: bool,
        /// Open the web dashboard in your browser on startup.
        #[arg(long)]
        open: bool,
    },
    /// Install Cloudwind into Windows Apps & Start Menu (per-user, no admin required).
    Install {
        /// Also enable starting Cloudwind automatically when Windows starts.
        #[arg(long)]
        autostart: bool,
    },
    /// Remove Cloudwind from Windows Apps, Start Menu, and Startup (keeps your photos and config).
    Uninstall,
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
    /// Seconds between cloud checks (defaults to 60).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    sync_interval_secs: Option<u64>,
    /// Local web dashboard port on 127.0.0.1 (defaults to 47616).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    ui_port: Option<u16>,
    /// Place incoming photos into 1-level subfolders (Camera, WhatsApp, etc.). Defaults to true.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    organize_by_folder: Option<bool>,
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

fn default_config_path() -> Result<PathBuf> {
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
    Ok(PathBuf::from(home.context("no home directory")?).join(".config/cloudwind/config.json"))
}

fn config_path(cli: &Cli) -> Result<PathBuf> {
    if let Some(p) = &cli.config {
        return Ok(p.clone());
    }
    default_config_path()
}

fn load(path: &Path) -> Result<(Config, LibraryKey)> {
    let raw = fs::read_to_string(path)
        .with_context(|| format!("no config at {path:?}; run `cloudwind init` first"))?;
    // Notepad and Windows PowerShell often save UTF-8 with a byte-order mark, which serde_json rejects.
    let cfg: Config = serde_json::from_str(raw.trim_start_matches('\u{feff}'))
        .with_context(|| format!("could not read {path:?}"))?;
    let key = LibraryKey::from_base64(&cfg.key)?;
    Ok((cfg, key))
}

/// Atomically persist mutable settings back into `config.json`.
pub(crate) fn update_config_file(
    path: &Path,
    library: Option<&Path>,
    sync_interval_secs: Option<u64>,
    ui_port: Option<u16>,
    organize_by_folder: Option<bool>,
) -> Result<()> {
    let (mut cfg, _) = load(path)?;
    if let Some(lib) = library {
        cfg.library = lib.to_path_buf();
    }
    if let Some(secs) = sync_interval_secs {
        cfg.sync_interval_secs = Some(secs);
    }
    if let Some(port) = ui_port {
        cfg.ui_port = Some(port);
    }
    if let Some(org) = organize_by_folder {
        cfg.organize_by_folder = Some(org);
    }
    let tmp = path.with_extension("json.tmp");
    let data = serde_json::to_vec_pretty(&cfg)?;
    fs::write(&tmp, &data)?;
    #[cfg(windows)]
    {
        // On Windows `fs::rename` fails if the destination exists; write directly after validating tmp.
        fs::write(path, &data)?;
        let _ = fs::remove_file(&tmp);
    }
    #[cfg(not(windows))]
    {
        fs::rename(&tmp, path)?;
    }
    Ok(())
}

fn state_dir(config: &Path) -> Result<PathBuf> {
    Ok(config.parent().context("config has no parent dir")?.join("state"))
}

/// Private IPv4 addresses of this machine: what the phone should try on the home network.
pub(crate) fn local_addrs() -> Vec<String> {
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

fn pairing_code(cfg: &Config, identity: &lan::Identity) -> Option<String> {
    let w = cfg.worker.as_ref()?;
    let token = w.phone_token.as_ref()?;
    let payload = serde_json::json!({
        "v": 1,
        "key": cfg.key,
        "fp": identity.fingerprint,
        "port": cfg.lan_port.unwrap_or(lan::DEFAULT_PORT),
        "url": &w.url,
        "token": token,
    });
    use cloudwind_core::base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
    Some(format!("cloudwind://pair/{}", URL_SAFE_NO_PAD.encode(payload.to_string())))
}

/// Holds a named Windows mutex so double-clicking the app while it's already running
/// simply opens the existing dashboard instead of starting a duplicate instance.
struct SingleInstanceGuard {
    #[cfg(windows)]
    handle: windows_sys::Win32::Foundation::HANDLE,
}

#[cfg(windows)]
impl Drop for SingleInstanceGuard {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            unsafe { windows_sys::Win32::Foundation::CloseHandle(self.handle) };
        }
    }
}

/// Returns `Some(guard)` if we are the primary instance, or `None` if already running.
fn try_acquire_single_instance() -> Option<SingleInstanceGuard> {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::{
            Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS},
            System::Threading::CreateMutexW,
        };
        let name: Vec<u16> = "Local\\CloudwindAgentSingleInstance\0".encode_utf16().collect();
        let h = CreateMutexW(std::ptr::null(), 0, name.as_ptr());
        if h.is_null() {
            return Some(SingleInstanceGuard { handle: std::ptr::null_mut() });
        }
        if GetLastError() == ERROR_ALREADY_EXISTS {
            CloseHandle(h);
            return None;
        }
        Some(SingleInstanceGuard { handle: h })
    }
    #[cfg(not(windows))]
    {
        Some(SingleInstanceGuard {})
    }
}

struct RunOpts<'a> {
    config_path: PathBuf,
    bind: &'a str,
    port: Option<u16>,
    interval: Option<u64>,
    ui_port: Option<u16>,
    no_ui: bool,
    no_tray: bool,
    open: bool,
    enforce_single_instance: bool,
}

fn daemon(cfg: Config, key: LibraryKey, state_dir: PathBuf, opts: RunOpts<'_>) -> Result<()> {
    use std::io::Write;

    let ui_port = opts.ui_port.or(cfg.ui_port).unwrap_or(web::DEFAULT_UI_PORT);
    let interval = opts.interval.or(cfg.sync_interval_secs).unwrap_or(60).clamp(10, 3600);

    let _single_guard = if opts.enforce_single_instance {
        match try_acquire_single_instance() {
            Some(g) => Some(g),
            None => {
                if opts.open && !opts.no_ui {
                    runtime::open_target(&format!("http://127.0.0.1:{ui_port}"));
                }
                return Ok(());
            }
        }
    } else {
        None
    };

    let state = Arc::new(state::State::load(&state_dir)?);
    let identity = lan::load_or_create_identity(&state_dir)?;
    let remote: Option<Arc<RemoteInbox>> = cfg.worker.as_ref().map(|w| Arc::new(RemoteInbox::new(&w.url, &w.agent_token)));
    let link: Option<Arc<dyn lan::CloudLink>> = remote.clone().map(|r| r as Arc<dyn lan::CloudLink>);

    let port = opts.port.or(cfg.lan_port).unwrap_or(lan::DEFAULT_PORT);
    let (lan_server, addr) = lan::start(&identity, opts.bind, port)?;
    println!("listening on {addr} (certificate {})", identity.fingerprint);

    let (ui_server, ui_url) = if opts.no_ui {
        (None, String::new())
    } else {
        match web::start(ui_port) {
            Ok(srv) => {
                let url = format!("http://{}", srv.addr());
                println!("dashboard at {url}");
                (Some(srv), url)
            }
            Err(e) => {
                eprintln!("warning: {e:#}");
                (None, String::new())
            }
        }
    };
    std::io::stdout().flush()?;

    let rt = Arc::new(runtime::Runtime::new(
        Some(opts.config_path),
        cfg.library.clone(),
        addr.port(),
        ui_port,
        interval,
        cfg.organize_by_folder.unwrap_or(true),
        identity.fingerprint.clone(),
        cfg.worker.as_ref().map(|w| w.url.clone()),
        pairing_code(&cfg, &identity),
        ui_url.clone(),
    ));
    rt.record_info(format!("Listening on port {}", addr.port()));

    let receiver = Arc::new(lan::Lan::new(cfg.library.clone(), state.clone(), &key, link).with_runtime(rt.clone()));
    let lan_srv_thread = lan_server.clone();
    std::thread::Builder::new()
        .name("lan-srv".into())
        .stack_size(256 * 1024)
        .spawn(move || lan::serve(receiver, lan_srv_thread))?;

    if let Some(srv) = &ui_server {
        let rt_web = rt.clone();
        let st_web = state.clone();
        let srv_web = srv.clone();
        std::thread::Builder::new()
            .name("web-ui".into())
            .stack_size(256 * 1024)
            .spawn(move || web::serve(rt_web, st_web, srv_web))?;
    }

    if opts.open && !ui_url.is_empty() {
        runtime::open_target(&ui_url);
    }

    // Reuse the single RemoteInbox/ureq::Agent instead of building a second TLS root store + pool.
    let inbox: Box<dyn Inbox> = match (&remote, &cfg.inbox) {
        (Some(r), _) => Box::new(r.clone()),
        (None, Some(d)) => Box::new(DirInbox { root: d.clone() }),
        _ => bail!("config has no inbox"),
    };
    let rt_sync = rt.clone();
    let st_sync = state.clone();
    let lan_port = addr.port();
    let sync_handle = std::thread::Builder::new()
        .name("sync-loop".into())
        .stack_size(256 * 1024)
        .spawn(move || {
            loop {
                rt_sync.set_syncing(true);
                let lib = rt_sync.library_path();
                let organize = rt_sync.organize_by_folder();
                match sync::run(inbox.as_ref(), &key, &lib, &st_sync, organize) {
                    Ok(r) => {
                        rt_sync.set_sync_error(None);
                        for p in &r.synced {
                            println!("synced {}", p.display());
                            let label = p
                                .strip_prefix(&lib)
                                .ok()
                                .map(|rel| rel.to_string_lossy().replace('\\', "/"))
                                .unwrap_or_else(|| {
                                    p.file_name()
                                        .map(|n| n.to_string_lossy().into_owned())
                                        .unwrap_or_else(|| p.display().to_string())
                                });
                            rt_sync.record_cloud(&label);
                        }
                        for (id, e) in &r.failed {
                            eprintln!("FAILED {id}: {e}");
                            rt_sync.record_error(format!("{id}: {e}"));
                        }
                    }
                    Err(e) => {
                        let msg = format!("{e:#}");
                        eprintln!("cloud check failed: {msg}");
                        rt_sync.set_sync_error(Some(msg));
                    }
                }
                if let Some(r) = &remote {
                    if let Ok(h) = r.health() {
                        rt_sync.set_health(h);
                    }
                    match lan_hint(&key, lan_port).and_then(|h| r.put_lan_hint(&h)) {
                        Ok(()) => rt_sync.set_hint_error(None),
                        Err(e) => {
                            let msg = format!("{e:#}");
                            eprintln!("could not announce address: {msg}");
                            rt_sync.set_hint_error(Some(msg));
                        }
                    }
                }
                rt_sync.set_syncing(false);
                runtime::trim_memory();
                let wait = Duration::from_secs(rt_sync.interval_secs());
                if !rt_sync.wait_or_shutdown(wait) {
                    break;
                }
            }
        })?;

    if opts.no_tray {
        let _ = sync_handle.join();
    } else if let Err(e) = tray::run(rt.clone(), ui_server.is_some()) {
        eprintln!("warning: system tray unavailable ({e:#}); continuing without tray");
        let _ = sync_handle.join();
    } else {
        rt.request_shutdown();
        lan_server.unblock();
        if let Some(srv) = &ui_server {
            srv.unblock();
        }
        let _ = sync_handle.join();
    }
    Ok(())
}

/// Entry point for the windowless desktop launcher (`cloudwind-app.exe`).
pub fn run_desktop_app() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "--uninstall") {
        return install::uninstall();
    }
    let open = args.iter().any(|a| a == "--open");
    let path = default_config_path()?;
    let (cfg, key) = load(&path)?;
    daemon(
        cfg,
        key,
        state_dir(&path)?,
        RunOpts {
            config_path: path,
            bind: "0.0.0.0",
            port: None,
            interval: None,
            ui_port: None,
            no_ui: false,
            no_tray: false,
            open,
            enforce_single_instance: true,
        },
    )
}

/// Entry point for the CLI binary (`cloudwind.exe`).
pub fn run_cli() -> Result<()> {
    let cli = Cli::parse();
    let path = config_path(&cli)?;
    let cmd = cli.cmd.unwrap_or(Cmd::Run {
        bind: "0.0.0.0".to_string(),
        port: None,
        interval: None,
        ui_port: None,
        no_ui: false,
        no_tray: false,
        open: false,
    });
    match cmd {
        Cmd::Init { library, inbox, worker_url, agent_token, phone_token } => {
            if path.exists() {
                bail!("{path:?} already exists; refusing to overwrite the key");
            }
            let key = LibraryKey::generate();
            let worker = match (worker_url, agent_token) {
                (Some(url), Some(t)) => Some(WorkerCfg {
                    url: url.trim_end_matches('/').to_string(),
                    agent_token: t,
                    phone_token,
                }),
                _ => None,
            };
            if worker.is_none() && inbox.is_none() {
                bail!("give --worker-url (with an agent token) or --inbox");
            }
            let cfg = Config {
                key: key.to_base64(),
                library,
                inbox,
                worker,
                lan_port: None,
                sync_interval_secs: None,
                ui_port: None,
                organize_by_folder: Some(true),
            };
            fs::create_dir_all(path.parent().unwrap())?;
            write_private(&path, &serde_json::to_vec_pretty(&cfg)?)?;
            println!("Created {path:?}\nLibrary key (keep a copy somewhere safe): {}", cfg.key);
        }
        Cmd::Install { autostart } => {
            install::install(autostart)?;
        }
        Cmd::Uninstall => {
            install::uninstall()?;
        }
        Cmd::Pair { light, svg } => {
            let (cfg, _) = load(&path)?;
            let identity = lan::load_or_create_identity(&state_dir(&path)?)?;
            let code = pairing_code(&cfg, &identity)
                .context("no phone token in config: re-run init with --phone-token (or CLOUDWIND_PHONE_TOKEN)")?;
            use qrcode::render::unicode::Dense1x2;
            let (dark, bg) = if light { (Dense1x2::Dark, Dense1x2::Light) } else { (Dense1x2::Light, Dense1x2::Dark) };
            let qr = qrcode::QrCode::new(code.as_bytes())?
                .render::<Dense1x2>()
                .dark_color(dark)
                .light_color(bg)
                .quiet_zone(true)
                .build();
            if let Some(svg_path) = svg {
                let image = qrcode::QrCode::new(code.as_bytes())?
                    .render::<qrcode::render::svg::Color>()
                    .min_dimensions(400, 400)
                    .build();
                fs::write(&svg_path, image)?;
                eprintln!("Wrote {} (it contains your key: delete it after pairing).", svg_path.display());
            }
            println!("{qr}\nScan this with the Cloudwind app (if it won't scan, try `cloudwind pair --light`). It contains your encryption key: don't share a screenshot.\n\nOr paste this code into the app:\n{code}");
        }
        Cmd::Sync => {
            let (cfg, key) = load(&path)?;
            let st = state::State::load(&state_dir(&path)?)?;
            let r = sync::run(open_inbox(&cfg)?.as_ref(), &key, &cfg.library, &st, cfg.organize_by_folder.unwrap_or(true))?;
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
        Cmd::Run { bind, port, interval, ui_port, no_ui, no_tray, open } => {
            let (cfg, key) = load(&path)?;
            let custom_ports = port.is_some() || ui_port.is_some();
            daemon(
                cfg,
                key,
                state_dir(&path)?,
                RunOpts {
                    config_path: path,
                    bind: &bind,
                    port,
                    interval,
                    ui_port,
                    no_ui,
                    no_tray,
                    open,
                    enforce_single_instance: !custom_ports,
                },
            )?;
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
            let modified = fs::metadata(&file)?
                .modified()?
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|d| d.as_secs());
            let name = file.file_name().context("no file name")?.to_string_lossy();
            let tmp = std::env::temp_dir().join(format!(".cloudwind-up-{}", inbox::random_id()));
            let enc = encrypt(&key, &name, modified, || fs::File::open(&file), std::io::BufWriter::new(fs::File::create(&tmp)?));
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

pub(crate) fn describe_phone(h: &remote::Health) -> (String, bool) {
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
