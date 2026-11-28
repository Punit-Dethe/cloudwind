//! Per-user Windows app installation:
//! - Copies `cloudwind-app.exe` and `cloudwind.exe` to `%LOCALAPPDATA%\Programs\Cloudwind\`
//! - Creates a Start Menu shortcut (`Cloudwind`) with the embedded app icon
//! - Registers in Windows Settings -> Apps -> Installed apps (`HKCU\...\Uninstall\Cloudwind`)
//! - Zero admin privileges required.

use std::path::PathBuf;

use anyhow::{Context, Result};

pub fn install_dir() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA").map(|d| PathBuf::from(d).join("Programs").join("Cloudwind"))
}

pub fn installed_app_exe() -> Option<PathBuf> {
    install_dir().map(|d| d.join("cloudwind-app.exe"))
}

pub fn start_menu_shortcut() -> Option<PathBuf> {
    std::env::var_os("APPDATA")
        .map(|d| PathBuf::from(d).join("Microsoft").join("Windows").join("Start Menu").join("Programs").join("Cloudwind.lnk"))
}

pub fn is_installed() -> bool {
    installed_app_exe().is_some_and(|p| p.exists())
}

#[cfg(windows)]
pub fn install(enable_autostart: bool) -> Result<()> {
    use std::fs;
    use windows_sys::Win32::{
        Foundation::ERROR_SUCCESS,
        System::Registry::{RegCloseKey, RegCreateKeyExW, HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE},
    };

    let dir = install_dir().context("LOCALAPPDATA not set")?;
    fs::create_dir_all(&dir)?;

    let cur_exe = std::env::current_exe()?;
    let src_dir = cur_exe.parent().context("current executable has no parent directory")?;

    let src_app = src_dir.join("cloudwind-app.exe");
    let src_cli = src_dir.join("cloudwind.exe");
    if !src_app.exists() {
        anyhow::bail!("{} not found; build with `cargo build --release -p cloudwind-agent` first", src_app.display());
    }

    let dst_app = dir.join("cloudwind-app.exe");
    let dst_cli = dir.join("cloudwind.exe");

    copy_replacing(&src_app, &dst_app)?;
    if src_cli.exists() {
        copy_replacing(&src_cli, &dst_cli)?;
    }

    // Create Start Menu shortcut pointing to `cloudwind-app.exe --open`
    if let Some(lnk) = start_menu_shortcut() {
        if let Some(parent) = lnk.parent() {
            let _ = fs::create_dir_all(parent);
        }
        create_shortcut(&lnk, &dst_app, "--open", &dir, "Cloudwind — Private Photo Backup")?;
    }

    // Register in Windows "Installed apps" (HKCU Uninstall key)
    let subkey = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Cloudwind");
    let mut hkey: HKEY = std::ptr::null_mut();
    let rc = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            std::ptr::null_mut(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            std::ptr::null(),
            &mut hkey,
            std::ptr::null_mut(),
        )
    };
    if rc == ERROR_SUCCESS {
        let uninstall_cmd = if dst_cli.exists() {
            format!("\"{}\" uninstall", dst_cli.display())
        } else {
            format!("\"{}\" --uninstall", dst_app.display())
        };
        let size_kb = ((fs::metadata(&dst_app).map(|m| m.len()).unwrap_or(0)
            + fs::metadata(&dst_cli).map(|m| m.len()).unwrap_or(0))
            / 1024) as u32;

        set_reg_sz(hkey, "DisplayName", "Cloudwind");
        set_reg_sz(hkey, "DisplayVersion", env!("CARGO_PKG_VERSION"));
        set_reg_sz(hkey, "Publisher", "Cloudwind");
        set_reg_sz(hkey, "DisplayIcon", &format!("{},0", dst_app.display()));
        set_reg_sz(hkey, "InstallLocation", &dir.display().to_string());
        set_reg_sz(hkey, "UninstallString", &uninstall_cmd);
        set_reg_dword(hkey, "EstimatedSize", size_kb);
        set_reg_dword(hkey, "NoModify", 1);
        set_reg_dword(hkey, "NoRepair", 1);
        unsafe { RegCloseKey(hkey) };
    }

    if enable_autostart {
        crate::autostart::set_enabled(true)?;
    }

    println!("Installed Cloudwind to {}", dir.display());
    println!("Start Menu: Search for \"Cloudwind\" to launch.");
    if enable_autostart {
        println!("Startup: Enabled (Cloudwind will start quietly in the tray when Windows starts).");
    }
    Ok(())
}

#[cfg(windows)]
pub fn uninstall() -> Result<()> {
    use std::fs;
    use windows_sys::Win32::System::Registry::{RegDeleteKeyW, HKEY_CURRENT_USER};

    let _ = crate::autostart::set_enabled(false);
    if let Some(lnk) = start_menu_shortcut() {
        let _ = fs::remove_file(lnk);
    }
    unsafe {
        let subkey = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Cloudwind");
        RegDeleteKeyW(HKEY_CURRENT_USER, subkey.as_ptr());
    }
    if let Some(dir) = install_dir() {
        if dir.exists() {
            let _ = fs::remove_file(dir.join("cloudwind-app.exe"));
            let _ = fs::remove_file(dir.join("cloudwind.exe"));
            let _ = fs::remove_dir(&dir);
        }
    }
    println!("Uninstalled Cloudwind app shortcuts and startup entry. Your photos and config were NOT touched.");
    Ok(())
}

#[cfg(windows)]
fn copy_replacing(src: &std::path::Path, dst: &std::path::Path) -> Result<()> {
    if src == dst {
        return Ok(());
    }
    // If the target exe is currently running, Windows allows renaming it aside before copying the new one.
    if dst.exists() {
        let old = dst.with_extension("old.exe");
        let _ = std::fs::remove_file(&old);
        let _ = std::fs::rename(dst, &old);
    }
    std::fs::copy(src, dst)
        .with_context(|| format!("copying {} to {}", src.display(), dst.display()))?;
    Ok(())
}

#[cfg(windows)]
fn create_shortcut(
    lnk: &std::path::Path,
    target: &std::path::Path,
    args: &str,
    work_dir: &std::path::Path,
    desc: &str,
) -> Result<()> {
    let esc = |s: &str| s.replace('\'', "''");
    let script = format!(
        "$ws = New-Object -ComObject WScript.Shell; \
         $s = $ws.CreateShortcut('{}'); \
         $s.TargetPath = '{}'; \
         $s.Arguments = '{}'; \
         $s.WorkingDirectory = '{}'; \
         $s.IconLocation = '{},0'; \
         $s.Description = '{}'; \
         $s.Save()",
        esc(&lnk.display().to_string()),
        esc(&target.display().to_string()),
        esc(args),
        esc(&work_dir.display().to_string()),
        esc(&target.display().to_string()),
        esc(desc),
    );
    let status = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", &script])
        .status()
        .context("creating Start Menu shortcut")?;
    if !status.success() {
        anyhow::bail!("failed to create Start Menu shortcut");
    }
    Ok(())
}

#[cfg(windows)]
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
fn set_reg_sz(hkey: windows_sys::Win32::System::Registry::HKEY, name: &str, value: &str) {
    use windows_sys::Win32::System::Registry::{RegSetValueExW, REG_SZ};
    let n = wide(name);
    let v = wide(value);
    unsafe {
        let bytes = std::slice::from_raw_parts(v.as_ptr() as *const u8, v.len() * 2);
        RegSetValueExW(hkey, n.as_ptr(), 0, REG_SZ, bytes.as_ptr(), bytes.len() as u32);
    }
}

#[cfg(windows)]
fn set_reg_dword(hkey: windows_sys::Win32::System::Registry::HKEY, name: &str, value: u32) {
    use windows_sys::Win32::System::Registry::{RegSetValueExW, REG_DWORD};
    let n = wide(name);
    unsafe {
        let bytes = value.to_le_bytes();
        RegSetValueExW(hkey, n.as_ptr(), 0, REG_DWORD, bytes.as_ptr(), bytes.len() as u32);
    }
}

#[cfg(not(windows))]
pub fn install(_enable_autostart: bool) -> Result<()> {
    anyhow::bail!("app installation is currently implemented for Windows")
}

#[cfg(not(windows))]
pub fn uninstall() -> Result<()> {
    anyhow::bail!("app uninstallation is currently implemented for Windows")
}
