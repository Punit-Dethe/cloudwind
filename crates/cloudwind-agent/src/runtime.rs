//! Shared live state for the running daemon: recent transfers, cloud health,
//! live settings, sync triggers, and shutdown coordination between the LAN server,
//! cloud loop, local web dashboard, and system tray.

use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
        Condvar, Mutex,
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

use crate::remote::Health;

const MAX_RECENT: usize = 50;

#[derive(Serialize, Clone, Debug)]
pub struct ActivityEvent {
    pub at: u64,
    /// `"direct"`, `"cloud"`, `"error"`, or `"info"`.
    pub kind: String,
    pub message: String,
}

#[derive(Serialize, Clone, Debug)]
pub struct HealthSnapshot {
    pub phone_last_seen: Option<u64>,
    pub pending_count: u64,
    pub pending_bytes: u64,
    pub inbox_bytes: u64,
    pub cap_bytes: u64,
    pub now: u64,
    pub phone_stalled: bool,
    pub phone_description: String,
}

impl HealthSnapshot {
    pub fn from_health(h: &Health) -> Self {
        let (desc, stalled) = crate::describe_phone(h);
        Self {
            phone_last_seen: h.phone_last_seen,
            pending_count: h.pending_count,
            pending_bytes: h.pending_bytes,
            inbox_bytes: h.inbox_bytes,
            cap_bytes: h.cap_bytes,
            now: h.now,
            phone_stalled: stalled,
            phone_description: desc,
        }
    }
}

#[derive(Serialize, Clone, Debug)]
pub struct StatusSnapshot {
    pub started_at: u64,
    pub now: u64,
    pub syncing: bool,
    pub last_check_at: Option<u64>,
    pub last_sync_error: Option<String>,
    pub last_hint_error: Option<String>,
    pub library: String,
    pub lan_port: u16,
    pub lan_ips: Vec<String>,
    pub cert_fingerprint: String,
    pub worker_url: Option<String>,
    pub has_pairing_code: bool,
    pub total_stored: usize,
    pub backed_off: usize,
    pub session_direct: u64,
    pub session_cloud: u64,
    pub health: Option<HealthSnapshot>,
    pub recent: Vec<ActivityEvent>,
}

#[derive(Serialize, Clone, Debug)]
pub struct SettingsSnapshot {
    pub start_on_login: bool,
    pub sync_interval_secs: u64,
    pub organize_by_folder: bool,
    pub library: String,
    pub lan_port: u16,
    pub ui_port: u16,
    pub config_path: String,
    pub installed: bool,
    pub install_dir: Option<String>,
    pub exe_path: String,
    pub version: String,
}

#[derive(Deserialize, Default, Debug)]
pub struct SettingsPatch {
    pub start_on_login: Option<bool>,
    pub sync_interval_secs: Option<u64>,
    pub organize_by_folder: Option<bool>,
    pub library: Option<String>,
    pub ui_port: Option<u16>,
}

struct Live {
    syncing: bool,
    last_check_at: Option<u64>,
    last_sync_error: Option<String>,
    last_hint_error: Option<String>,
    health: Option<HealthSnapshot>,
    session_direct: u64,
    session_cloud: u64,
    recent: VecDeque<ActivityEvent>,
    library: PathBuf,
    ui_port: u16,
}

pub struct Runtime {
    pub started_at: u64,
    pub config_path: Option<PathBuf>,
    pub lan_port: u16,
    pub cert_fingerprint: String,
    pub worker_url: Option<String>,
    pub pairing_code: Option<String>,
    pub ui_url: String,
    interval_secs: AtomicU64,
    organize_by_folder: AtomicBool,
    live: Mutex<Live>,
    tray_thread_id: AtomicU32,
    tray_autostart_dirty: AtomicBool,
    wake_lock: Mutex<bool>,
    wake_cv: Condvar,
    shutdown: AtomicBool,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

impl Runtime {
    pub fn new(
        config_path: Option<PathBuf>,
        library: PathBuf,
        lan_port: u16,
        ui_port: u16,
        interval_secs: u64,
        organize_by_folder: bool,
        cert_fingerprint: String,
        worker_url: Option<String>,
        pairing_code: Option<String>,
        ui_url: String,
    ) -> Self {
        Self {
            started_at: now_secs(),
            config_path,
            lan_port,
            cert_fingerprint,
            worker_url,
            pairing_code,
            ui_url,
            interval_secs: AtomicU64::new(interval_secs.clamp(10, 3600)),
            organize_by_folder: AtomicBool::new(organize_by_folder),
            live: Mutex::new(Live {
                syncing: false,
                last_check_at: None,
                last_sync_error: None,
                last_hint_error: None,
                health: None,
                session_direct: 0,
                session_cloud: 0,
                recent: VecDeque::new(),
                library,
                ui_port,
            }),
            tray_thread_id: AtomicU32::new(0),
            tray_autostart_dirty: AtomicBool::new(false),
            wake_lock: Mutex::new(false),
            wake_cv: Condvar::new(),
            shutdown: AtomicBool::new(false),
        }
    }

    pub fn register_tray_thread(&self, tid: u32) {
        self.tray_thread_id.store(tid, Ordering::Release);
    }

    pub fn notify_tray_autostart_changed(&self) {
        self.tray_autostart_dirty.store(true, Ordering::Release);
        #[cfg(windows)]
        {
            let tid = self.tray_thread_id.load(Ordering::Acquire);
            if tid != 0 {
                unsafe {
                    windows_sys::Win32::UI::WindowsAndMessaging::PostThreadMessageW(
                        tid,
                        windows_sys::Win32::UI::WindowsAndMessaging::WM_APP,
                        0,
                        0,
                    );
                }
            }
        }
    }

    pub fn take_tray_autostart_dirty(&self) -> bool {
        self.tray_autostart_dirty.swap(false, Ordering::AcqRel)
    }

    pub fn library_path(&self) -> PathBuf {
        self.live.lock().unwrap_or_else(|e| e.into_inner()).library.clone()
    }

    pub fn interval_secs(&self) -> u64 {
        self.interval_secs.load(Ordering::Relaxed)
    }

    pub fn organize_by_folder(&self) -> bool {
        self.organize_by_folder.load(Ordering::Relaxed)
    }

    pub fn set_syncing(&self, syncing: bool) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        g.syncing = syncing;
        if !syncing {
            g.last_check_at = Some(now_secs());
        }
    }

    pub fn set_sync_error(&self, err: Option<String>) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        g.last_sync_error = err;
    }

    pub fn set_hint_error(&self, err: Option<String>) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        g.last_hint_error = err;
    }

    pub fn set_health(&self, h: Health) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        g.health = Some(HealthSnapshot::from_health(&h));
    }

    pub fn record_direct(&self, name: &str, duplicate: bool) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        if !duplicate {
            g.session_direct += 1;
        }
        let msg = if duplicate {
            format!("{name} (already in library)")
        } else {
            name.to_string()
        };
        push_event(&mut g.recent, "direct", msg);
    }

    pub fn record_cloud(&self, name: &str) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        g.session_cloud += 1;
        push_event(&mut g.recent, "cloud", name.to_string());
    }

    pub fn record_error(&self, msg: String) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        push_event(&mut g.recent, "error", msg);
    }

    pub fn record_info(&self, msg: String) {
        let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        push_event(&mut g.recent, "info", msg);
    }

    pub fn snapshot(&self, total_stored: usize, backed_off: usize, lan_ips: Vec<String>) -> StatusSnapshot {
        let g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        StatusSnapshot {
            started_at: self.started_at,
            now: now_secs(),
            syncing: g.syncing,
            last_check_at: g.last_check_at,
            last_sync_error: g.last_sync_error.clone(),
            last_hint_error: g.last_hint_error.clone(),
            library: g.library.display().to_string(),
            lan_port: self.lan_port,
            lan_ips,
            cert_fingerprint: self.cert_fingerprint.clone(),
            worker_url: self.worker_url.clone(),
            has_pairing_code: self.pairing_code.is_some(),
            total_stored,
            backed_off,
            session_direct: g.session_direct,
            session_cloud: g.session_cloud,
            health: g.health.clone(),
            recent: g.recent.iter().cloned().collect(),
        }
    }

    pub fn settings_snapshot(&self) -> SettingsSnapshot {
        let g = self.live.lock().unwrap_or_else(|e| e.into_inner());
        SettingsSnapshot {
            start_on_login: crate::autostart::is_enabled(),
            sync_interval_secs: self.interval_secs(),
            organize_by_folder: self.organize_by_folder(),
            library: g.library.display().to_string(),
            lan_port: self.lan_port,
            ui_port: g.ui_port,
            config_path: self
                .config_path
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_default(),
            installed: crate::install::is_installed(),
            install_dir: crate::install::install_dir().map(|p| p.display().to_string()),
            exe_path: std::env::current_exe()
                .map(|p| p.display().to_string())
                .unwrap_or_default(),
            version: env!("CARGO_PKG_VERSION").to_string(),
        }
    }

    pub fn update_settings(&self, patch: SettingsPatch) -> Result<SettingsSnapshot> {
        if let Some(enable) = patch.start_on_login {
            crate::autostart::set_enabled(enable)?;
            self.notify_tray_autostart_changed();
        }

        let mut persist_cfg = false;
        let mut new_interval: Option<u64> = None;
        let mut new_library: Option<PathBuf> = None;
        let mut new_ui_port: Option<u16> = None;
        let mut new_organize: Option<bool> = None;

        if let Some(secs) = patch.sync_interval_secs {
            if !(10..=3600).contains(&secs) {
                bail!("sync interval must be between 10 and 3600 seconds");
            }
            self.interval_secs.store(secs, Ordering::Relaxed);
            new_interval = Some(secs);
            persist_cfg = true;
        }

        if let Some(org) = patch.organize_by_folder {
            self.organize_by_folder.store(org, Ordering::Relaxed);
            new_organize = Some(org);
            persist_cfg = true;
        }

        if let Some(ref lib) = patch.library {
            let trimmed = lib.trim();
            if trimmed.is_empty() {
                bail!("library folder path cannot be empty");
            }
            let p = PathBuf::from(trimmed);
            std::fs::create_dir_all(&p)?;
            new_library = Some(p);
            persist_cfg = true;
        }

        if let Some(port) = patch.ui_port {
            if port < 1024 {
                bail!("dashboard port must be between 1024 and 65535");
            }
            new_ui_port = Some(port);
            persist_cfg = true;
        }

        if persist_cfg {
            if let Some(cfg_path) = &self.config_path {
                crate::update_config_file(cfg_path, new_library.as_deref(), new_interval, new_ui_port, new_organize)?;
            }
            let mut g = self.live.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(p) = new_library {
                g.library = p;
            }
            if let Some(port) = new_ui_port {
                g.ui_port = port;
            }
        }

        if new_interval.is_some() {
            self.trigger_sync();
        }

        Ok(self.settings_snapshot())
    }

    /// Wake the cloud sync loop immediately (e.g. "Sync now" from tray or web UI).
    pub fn trigger_sync(&self) {
        let mut w = self.wake_lock.lock().unwrap_or_else(|e| e.into_inner());
        *w = true;
        self.wake_cv.notify_all();
    }

    /// Wait up to `dur` for either a manual sync trigger or shutdown.
    /// Returns `false` if shutdown was requested.
    pub fn wait_or_shutdown(&self, dur: Duration) -> bool {
        if self.is_shutdown() {
            return false;
        }
        let mut w = self.wake_lock.lock().unwrap_or_else(|e| e.into_inner());
        if *w {
            *w = false;
            return !self.is_shutdown();
        }
        let (mut w, _) = self.wake_cv.wait_timeout(w, dur).unwrap_or_else(|e| e.into_inner());
        *w = false;
        !self.is_shutdown()
    }

    pub fn request_shutdown(&self) {
        self.shutdown.store(true, Ordering::SeqCst);
        let mut w = self.wake_lock.lock().unwrap_or_else(|e| e.into_inner());
        *w = true;
        self.wake_cv.notify_all();
    }

    pub fn is_shutdown(&self) -> bool {
        self.shutdown.load(Ordering::SeqCst)
    }
}

fn push_event(recent: &mut VecDeque<ActivityEvent>, kind: &str, message: String) {
    recent.push_front(ActivityEvent {
        at: now_secs(),
        kind: kind.to_string(),
        message,
    });
    while recent.len() > MAX_RECENT {
        recent.pop_back();
    }
}

/// Compact the heap and trim cold working-set pages back to the OS when idle.
/// On Windows, this dramatically shrinks resident RAM (Working Set) between transfers
/// with zero impact on correctness — hot pages fault back in sub-microseconds if needed.
pub fn trim_memory() {
    #[cfg(windows)]
    unsafe {
        use windows_sys::Win32::System::{
            Memory::{GetProcessHeap, HeapCompact, SetProcessWorkingSetSizeEx},
            Threading::GetCurrentProcess,
        };
        let heap = GetProcessHeap();
        if !heap.is_null() {
            HeapCompact(heap, 0);
        }
        SetProcessWorkingSetSizeEx(GetCurrentProcess(), usize::MAX, usize::MAX, 0);
    }
}

/// Open a URL or folder in the OS default handler (browser / file explorer).
pub fn open_target(target: &str) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        let _ = std::process::Command::new("cmd")
            .args(["/C", "start", "", target])
            .creation_flags(CREATE_NO_WINDOW)
            .spawn();
    }
    #[cfg(target_os = "macos")]
    {
        let _ = std::process::Command::new("open").arg(target).spawn();
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let _ = std::process::Command::new("xdg-open").arg(target).spawn();
    }
}
