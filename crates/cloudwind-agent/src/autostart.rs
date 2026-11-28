//! Start-with-Windows management via the per-user Registry Run key
//! (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
//!
//! Uses Win32 Registry APIs directly (zero subprocess spawns, zero extra RAM),
//! and integrates natively with Windows Settings -> Apps -> Startup and Task Manager.

use std::path::PathBuf;

use anyhow::Result;

const RUN_VALUE_NAME: &str = "Cloudwind";

/// Preferred executable to register for startup:
/// 1. Installed `%LOCALAPPDATA%\Programs\Cloudwind\cloudwind-app.exe` if present
/// 2. Sibling `cloudwind-app.exe` next to the current binary if present
/// 3. Current executable itself (fallback)
pub fn preferred_app_exe() -> Result<PathBuf> {
    if let Some(installed) = crate::install::installed_app_exe() {
        if installed.exists() {
            return Ok(installed);
        }
    }
    let cur = std::env::current_exe()?;
    if let Some(dir) = cur.parent() {
        let app = dir.join(if cfg!(windows) { "cloudwind-app.exe" } else { "cloudwind-app" });
        if app.exists() {
            return Ok(app);
        }
    }
    Ok(cur)
}

#[cfg(windows)]
fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

#[cfg(windows)]
pub fn is_enabled() -> bool {
    use windows_sys::Win32::{
        Foundation::ERROR_SUCCESS,
        System::Registry::{RegCloseKey, RegOpenKeyExW, RegQueryValueExW, HKEY, HKEY_CURRENT_USER, KEY_READ},
    };

    unsafe {
        let subkey = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        let name = wide(RUN_VALUE_NAME);
        let mut hkey: HKEY = std::ptr::null_mut();
        if RegOpenKeyExW(HKEY_CURRENT_USER, subkey.as_ptr(), 0, KEY_READ, &mut hkey) != ERROR_SUCCESS {
            return false;
        }
        let mut size: u32 = 0;
        let ok = RegQueryValueExW(
            hkey,
            name.as_ptr(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            &mut size,
        ) == ERROR_SUCCESS
            && size > 2;
        RegCloseKey(hkey);
        ok
    }
}

#[cfg(windows)]
pub fn set_enabled(enable: bool) -> Result<()> {
    use anyhow::bail;
    use windows_sys::Win32::{
        Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS},
        System::Registry::{
            RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegSetValueExW, HKEY, HKEY_CURRENT_USER,
            KEY_SET_VALUE, REG_OPTION_NON_VOLATILE, REG_SZ,
        },
    };

    unsafe {
        let subkey = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        let name = wide(RUN_VALUE_NAME);
        let mut hkey: HKEY = std::ptr::null_mut();
        let rc = RegCreateKeyExW(
            HKEY_CURRENT_USER,
            subkey.as_ptr(),
            0,
            std::ptr::null_mut(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            std::ptr::null(),
            &mut hkey,
            std::ptr::null_mut(),
        );
        if rc != ERROR_SUCCESS {
            bail!("could not open Windows startup registry key (code {rc})");
        }

        let res = if enable {
            let exe = preferred_app_exe()?;
            let is_cli = exe
                .file_stem()
                .and_then(|s| s.to_str())
                .is_some_and(|s| s.eq_ignore_ascii_case("cloudwind"));
            let cmd = if is_cli {
                format!("\"{}\" run", exe.display())
            } else {
                format!("\"{}\"", exe.display())
            };
            let val = wide(&cmd);
            let bytes = std::slice::from_raw_parts(val.as_ptr() as *const u8, val.len() * 2);
            let rc = RegSetValueExW(hkey, name.as_ptr(), 0, REG_SZ, bytes.as_ptr(), bytes.len() as u32);
            if rc != ERROR_SUCCESS {
                Err(anyhow::anyhow!("could not write startup entry (code {rc})"))
            } else {
                Ok(())
            }
        } else {
            let rc = RegDeleteValueW(hkey, name.as_ptr());
            if rc != ERROR_SUCCESS && rc != ERROR_FILE_NOT_FOUND {
                Err(anyhow::anyhow!("could not remove startup entry (code {rc})"))
            } else {
                Ok(())
            }
        };

        RegCloseKey(hkey);
        res
    }
}

#[cfg(not(windows))]
pub fn is_enabled() -> bool {
    false
}

#[cfg(not(windows))]
pub fn set_enabled(_enable: bool) -> Result<()> {
    anyhow::bail!("start-at-login is currently implemented for Windows")
}
