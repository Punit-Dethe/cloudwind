//! Native system tray icon (Windows). Degrades cleanly on non-Windows platforms.

use std::sync::Arc;

use anyhow::Result;

use crate::runtime::Runtime;

#[cfg(windows)]
pub fn run(runtime: Arc<Runtime>, has_ui: bool) -> Result<()> {
    use tray_icon::{
        menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem},
        Icon, MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent,
    };
    use windows_sys::Win32::{
        System::Threading::GetCurrentThreadId,
        UI::WindowsAndMessaging::{
            DispatchMessageW, GetMessageW, PostQuitMessage, TranslateMessage, MSG,
        },
    };

    use crate::runtime::open_target;

    let menu = Menu::new();
    let item_header = MenuItem::new("Cloudwind — Running", false, None);
    let item_open = MenuItem::new("Open Dashboard", has_ui, None);
    let item_sync = MenuItem::new("Sync Now", true, None);
    let item_folder = MenuItem::new("Open Photo Folder", true, None);
    let item_autostart = CheckMenuItem::new(
        "Start with Windows",
        true,
        crate::autostart::is_enabled(),
        None,
    );
    let item_quit = MenuItem::new("Quit Cloudwind", true, None);

    menu.append(&item_header)?;
    menu.append(&PredefinedMenuItem::separator())?;
    menu.append(&item_open)?;
    menu.append(&item_sync)?;
    menu.append(&item_folder)?;
    menu.append(&PredefinedMenuItem::separator())?;
    menu.append(&item_autostart)?;
    menu.append(&PredefinedMenuItem::separator())?;
    menu.append(&item_quit)?;

    let id_open = item_open.id().clone();
    let id_sync = item_sync.id().clone();
    let id_folder = item_folder.id().clone();
    let id_autostart = item_autostart.id().clone();
    let id_quit = item_quit.id().clone();

    runtime.register_tray_thread(unsafe { GetCurrentThreadId() });

    let (rgba, w, h) = make_icon_rgba();
    let icon = Icon::from_rgba(rgba, w, h)?;

    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("Cloudwind — Photo Backup")
        .with_icon(icon)
        .build()?;
    crate::runtime::trim_memory();

    let rt_menu = runtime.clone();
    MenuEvent::set_event_handler(Some(Arc::new(move |ev: MenuEvent| {
        if ev.id == id_open {
            if has_ui {
                open_target(&rt_menu.ui_url);
            }
        } else if ev.id == id_sync {
            rt_menu.trigger_sync();
        } else if ev.id == id_folder {
            open_target(&rt_menu.library_path().display().to_string());
        } else if ev.id == id_autostart {
            let want = !crate::autostart::is_enabled();
            let _ = crate::autostart::set_enabled(want);
            rt_menu.notify_tray_autostart_changed();
        } else if ev.id == id_quit {
            rt_menu.request_shutdown();
            unsafe { PostQuitMessage(0) };
        }
    })));

    let rt_click = runtime.clone();
    TrayIconEvent::set_event_handler(Some(move |ev: TrayIconEvent| {
        if let TrayIconEvent::Click {
            button: MouseButton::Left,
            button_state: MouseButtonState::Up,
            ..
        } = ev
        {
            if has_ui {
                open_target(&rt_click.ui_url);
            }
        }
    }));

    unsafe {
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
            if runtime.take_tray_autostart_dirty() {
                item_autostart.set_checked(crate::autostart::is_enabled());
            }
            if runtime.is_shutdown() {
                break;
            }
        }
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn run(runtime: Arc<Runtime>, _has_ui: bool) -> Result<()> {
    // Non-Windows platforms run headless until terminated.
    while !runtime.is_shutdown() {
        std::thread::sleep(std::time::Duration::from_millis(250));
    }
    Ok(())
}

/// Generate a crisp 32x32 RGBA Cloudwind tray icon (rounded blue badge + white cloud)
/// so the binary stays 100% self-contained with no external asset files.
#[cfg(windows)]
fn make_icon_rgba() -> (Vec<u8>, u32, u32) {
    const SIZE: usize = 32;
    let mut buf = vec![0u8; SIZE * SIZE * 4];

    for y in 0..SIZE {
        for x in 0..SIZE {
            let fx = x as f32 + 0.5;
            let fy = y as f32 + 0.5;

            // Rounded-square background: center (16, 16), half-size 14.5, corner radius 7.
            let bx = (fx - 16.0).abs() - 7.5;
            let by = (fy - 16.0).abs() - 7.5;
            let bg_dist = (bx.max(0.0).hypot(by.max(0.0)) + bx.max(by).min(0.0)) - 7.0;
            let bg_alpha = (0.5 - bg_dist).clamp(0.0, 1.0);

            // Cloud silhouette: union of 3 circles + flat base pill.
            let c1 = (fx - 15.0).hypot(fy - 14.5) - 5.4;
            let c2 = (fx - 10.2).hypot(fy - 18.0) - 4.0;
            let c3 = (fx - 21.0).hypot(fy - 17.6) - 4.4;
            let rx = (fx - 15.8).abs() - 5.4;
            let ry = (fy - 19.6).abs() - 2.4;
            let rect = rx.max(0.0).hypot(ry.max(0.0)) + rx.max(ry).min(0.0);
            let cloud_dist = c1.min(c2).min(c3).min(rect);
            let cloud_alpha = (0.5 - cloud_dist).clamp(0.0, 1.0);

            // Blend white cloud over Cloudwind blue (#2563EB).
            let r = (37.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let g = (99.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let b = (235.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let a = (bg_alpha * 255.0) as u8;

            let idx = (y * SIZE + x) * 4;
            buf[idx] = r;
            buf[idx + 1] = g;
            buf[idx + 2] = b;
            buf[idx + 3] = a;
        }
    }
    (buf, SIZE as u32, SIZE as u32)
}
