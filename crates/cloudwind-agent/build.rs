//! Generates a crisp multi-size Windows `.ico` for Cloudwind and embeds it
//! along with Windows application metadata into the binaries.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");

    #[cfg(windows)]
    {
        let out_dir = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
        let ico_path = out_dir.join("cloudwind.ico");
        let ico = build_ico(&[32, 48, 64, 128]);
        std::fs::write(&ico_path, &ico).expect("write cloudwind.ico");

        let mut res = winresource::WindowsResource::new();
        res.set_icon(ico_path.to_str().unwrap());
        res.set("ProductName", "Cloudwind");
        res.set("FileDescription", "Cloudwind — Private Photo Backup");
        res.set("CompanyName", "Cloudwind");
        res.set("InternalName", "Cloudwind");
        if let Err(e) = res.compile() {
            println!("cargo:warning=could not embed Windows icon/metadata: {e}");
        }
    }
}

#[cfg(windows)]
fn render_bgra_bottom_up(size: u32) -> Vec<u8> {
    let s = size as f32;
    let mut out = Vec::with_capacity((size * size * 4) as usize);
    for y_rev in 0..size {
        let y = size - 1 - y_rev; // ICO DIB rows are stored bottom-up
        for x in 0..size {
            // Normalize coordinates into 0..32 design space so every size scales identically.
            let fx = (x as f32 + 0.5) * (32.0 / s);
            let fy = (y as f32 + 0.5) * (32.0 / s);
            let px = 32.0 / s;

            let bx = (fx - 16.0).abs() - 7.5;
            let by = (fy - 16.0).abs() - 7.5;
            let bg_dist = (bx.max(0.0).hypot(by.max(0.0)) + bx.max(by).min(0.0)) - 7.0;
            let bg_alpha = (0.5 - bg_dist / px).clamp(0.0, 1.0);

            let c1 = (fx - 15.0).hypot(fy - 14.5) - 5.4;
            let c2 = (fx - 10.2).hypot(fy - 18.0) - 4.0;
            let c3 = (fx - 21.0).hypot(fy - 17.6) - 4.4;
            let rx = (fx - 15.8).abs() - 5.4;
            let ry = (fy - 19.6).abs() - 2.4;
            let rect = rx.max(0.0).hypot(ry.max(0.0)) + rx.max(ry).min(0.0);
            let cloud_dist = c1.min(c2).min(c3).min(rect);
            let cloud_alpha = (0.5 - cloud_dist / px).clamp(0.0, 1.0);

            let r = (37.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let g = (99.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let b = (235.0 * (1.0 - cloud_alpha) + 255.0 * cloud_alpha) as u8;
            let a = (bg_alpha * 255.0) as u8;

            out.extend_from_slice(&[b, g, r, a]);
        }
    }
    out
}

#[cfg(windows)]
fn build_ico(sizes: &[u32]) -> Vec<u8> {
    let mut images: Vec<(u32, Vec<u8>)> = Vec::with_capacity(sizes.len());
    for &sz in sizes {
        let mut img = Vec::new();
        // BITMAPINFOHEADER (40 bytes)
        img.extend_from_slice(&40u32.to_le_bytes());
        img.extend_from_slice(&(sz as i32).to_le_bytes());
        img.extend_from_slice(&((sz * 2) as i32).to_le_bytes()); // XOR + AND height
        img.extend_from_slice(&1u16.to_le_bytes()); // planes
        img.extend_from_slice(&32u16.to_le_bytes()); // bpp
        img.extend_from_slice(&0u32.to_le_bytes()); // BI_RGB
        img.extend_from_slice(&0u32.to_le_bytes()); // size image
        img.extend_from_slice(&0i32.to_le_bytes()); // xppm
        img.extend_from_slice(&0i32.to_le_bytes()); // yppm
        img.extend_from_slice(&0u32.to_le_bytes()); // clr used
        img.extend_from_slice(&0u32.to_le_bytes()); // clr important
        img.extend_from_slice(&render_bgra_bottom_up(sz));
        // 1bpp AND mask (all 0: transparency comes from 32-bit alpha channel)
        let mask_stride = ((sz + 31) / 32) * 4;
        img.resize(img.len() + (mask_stride * sz) as usize, 0);
        images.push((sz, img));
    }

    let mut out = Vec::new();
    // ICONDIR
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&(images.len() as u16).to_le_bytes());

    let mut offset = 6 + (images.len() as u32) * 16;
    for (sz, img) in &images {
        let dim = if *sz >= 256 { 0u8 } else { *sz as u8 };
        out.push(dim);
        out.push(dim);
        out.push(0); // color count
        out.push(0); // reserved
        out.extend_from_slice(&1u16.to_le_bytes()); // planes
        out.extend_from_slice(&32u16.to_le_bytes()); // bpp
        out.extend_from_slice(&(img.len() as u32).to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        offset += img.len() as u32;
    }
    for (_, img) in images {
        out.extend_from_slice(&img);
    }
    out
}
