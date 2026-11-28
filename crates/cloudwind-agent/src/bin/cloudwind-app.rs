#![cfg_attr(windows, windows_subsystem = "windows")]

fn main() {
    if let Err(e) = cloudwind_agent::run_desktop_app() {
        #[cfg(windows)]
        {
            use windows_sys::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK};
            let title: Vec<u16> = "Cloudwind\0".encode_utf16().collect();
            let text: Vec<u16> = format!("Cloudwind could not start:\n\n{e:#}\0").encode_utf16().collect();
            unsafe {
                MessageBoxW(std::ptr::null_mut(), text.as_ptr(), title.as_ptr(), MB_OK | MB_ICONERROR);
            }
        }
        #[cfg(not(windows))]
        eprintln!("Cloudwind error: {e:#}");
        std::process::exit(1);
    }
}
