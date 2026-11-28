//! Minimal 1-thread local-only HTTP/1.1 dashboard server on 127.0.0.1.
//!
//! Uses a plain `TcpListener` instead of `tiny_http` to avoid spawning 5 permanent
//! background threads and their stacks/channels for a low-traffic local UI.

use std::{
    io::{BufRead, BufReader, Read, Write},
    net::{SocketAddr, TcpListener, TcpStream},
    sync::Arc,
    time::Duration,
};

use anyhow::{anyhow, Result};

use crate::{
    runtime::{open_target, Runtime, SettingsPatch},
    state::State,
};

pub const DEFAULT_UI_PORT: u16 = 47616;
const INDEX_HTML: &str = include_str!("ui.html");
const MAX_HEADER_LINES: usize = 64;
const MAX_POST_BODY: usize = 16 * 1024;

pub struct WebServer {
    listener: TcpListener,
    addr: SocketAddr,
}

impl WebServer {
    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    /// Wake the blocking `accept()` loop during shutdown.
    pub fn unblock(&self) {
        let _ = TcpStream::connect_timeout(&self.addr, Duration::from_millis(100));
    }
}

pub fn start(port: u16) -> Result<Arc<WebServer>> {
    let listener = TcpListener::bind(("127.0.0.1", port))
        .map_err(|e| anyhow!("cannot start web dashboard on 127.0.0.1:{port}: {e}"))?;
    let addr = listener.local_addr()?;
    Ok(Arc::new(WebServer { listener, addr }))
}

pub fn serve(runtime: Arc<Runtime>, state: Arc<State>, server: Arc<WebServer>) {
    for conn in server.listener.incoming() {
        if runtime.is_shutdown() {
            break;
        }
        if let Ok(stream) = conn {
            handle_conn(stream, &runtime, &state);
        }
    }
}

fn is_local_origin(origin: Option<&str>) -> bool {
    match origin {
        None => true,
        Some(o) => {
            o.starts_with("http://127.0.0.1:")
                || o.starts_with("http://localhost:")
                || o == "http://127.0.0.1"
                || o == "http://localhost"
        }
    }
}

fn write_response(stream: &mut TcpStream, status: &str, content_type: &str, body: &[u8]) {
    let head = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

fn handle_conn(mut stream: TcpStream, runtime: &Runtime, state: &State) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(3)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(5)));

    let mut reader = BufReader::with_capacity(4096, match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    });

    let mut req_line = String::new();
    if reader.read_line(&mut req_line).unwrap_or(0) == 0 {
        return;
    }
    let mut parts = req_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let raw_target = parts.next().unwrap_or("/");
    let path = raw_target.split('?').next().unwrap_or(raw_target);

    let mut origin: Option<String> = None;
    let mut content_len: usize = 0;
    let mut line = String::new();
    for _ in 0..MAX_HEADER_LINES {
        line.clear();
        let n = reader.read_line(&mut line).unwrap_or(0);
        if n == 0 || line == "\r\n" || line == "\n" {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            if k.eq_ignore_ascii_case("origin") {
                origin = Some(v.trim().to_string());
            } else if k.eq_ignore_ascii_case("content-length") {
                content_len = v.trim().parse().unwrap_or(0);
            }
        }
    }

    let body = if method == "POST" && content_len > 0 && content_len <= MAX_POST_BODY {
        let mut buf = vec![0u8; content_len];
        if reader.read_exact(&mut buf).is_ok() {
            buf
        } else {
            Vec::new()
        }
    } else {
        Vec::new()
    };
    drop(reader);

    match (method, path) {
        ("GET", "/") | ("GET", "/index.html") => {
            write_response(&mut stream, "200 OK", "text/html; charset=utf-8", INDEX_HTML.as_bytes());
        }
        ("GET", "/api/status") => {
            let snap = runtime.snapshot(state.seen_count(), state.failure_count(), crate::local_addrs());
            let body = serde_json::to_vec(&snap).unwrap_or_else(|_| b"{}".to_vec());
            write_response(&mut stream, "200 OK", "application/json; charset=utf-8", &body);
        }
        ("GET", "/api/settings") => {
            let snap = runtime.settings_snapshot();
            let body = serde_json::to_vec(&snap).unwrap_or_else(|_| b"{}".to_vec());
            write_response(&mut stream, "200 OK", "application/json; charset=utf-8", &body);
        }
        ("POST", "/api/settings") => {
            if !is_local_origin(origin.as_deref()) {
                write_response(&mut stream, "403 Forbidden", "application/json; charset=utf-8", br#"{"error":"forbidden_origin"}"#);
                return;
            }
            let patch: SettingsPatch = match serde_json::from_slice(&body) {
                Ok(p) => p,
                Err(_) => {
                    write_response(&mut stream, "400 Bad Request", "application/json; charset=utf-8", br#"{"error":"invalid_json"}"#);
                    return;
                }
            };
            match runtime.update_settings(patch) {
                Ok(snap) => {
                    let out = serde_json::to_vec(&snap).unwrap_or_else(|_| b"{}".to_vec());
                    write_response(&mut stream, "200 OK", "application/json; charset=utf-8", &out);
                }
                Err(e) => {
                    let err = serde_json::json!({ "error": format!("{e:#}") }).to_string();
                    write_response(&mut stream, "400 Bad Request", "application/json; charset=utf-8", err.as_bytes());
                }
            }
        }
        ("GET", "/api/pair") => {
            let Some(code) = &runtime.pairing_code else {
                write_response(&mut stream, "404 Not Found", "application/json; charset=utf-8", br#"{"error":"no_pairing_code"}"#);
                return;
            };
            let svg = qrcode::QrCode::new(code.as_bytes())
                .map(|q| {
                    q.render::<qrcode::render::svg::Color>()
                        .min_dimensions(280, 280)
                        .quiet_zone(true)
                        .build()
                })
                .unwrap_or_default();
            let body = serde_json::json!({ "code": code, "svg": svg }).to_string();
            write_response(&mut stream, "200 OK", "application/json; charset=utf-8", body.as_bytes());
        }
        ("POST", "/api/sync") => {
            if !is_local_origin(origin.as_deref()) {
                write_response(&mut stream, "403 Forbidden", "application/json; charset=utf-8", br#"{"error":"forbidden_origin"}"#);
                return;
            }
            runtime.trigger_sync();
            write_response(&mut stream, "202 Accepted", "application/json; charset=utf-8", br#"{"ok":true}"#);
        }
        ("POST", "/api/open-library") => {
            if !is_local_origin(origin.as_deref()) {
                write_response(&mut stream, "403 Forbidden", "application/json; charset=utf-8", br#"{"error":"forbidden_origin"}"#);
                return;
            }
            open_target(&runtime.library_path().display().to_string());
            write_response(&mut stream, "200 OK", "application/json; charset=utf-8", br#"{"ok":true}"#);
        }
        ("POST", "/api/install") => {
            if !is_local_origin(origin.as_deref()) {
                write_response(&mut stream, "403 Forbidden", "application/json; charset=utf-8", br#"{"error":"forbidden_origin"}"#);
                return;
            }
            match crate::install::install(false) {
                Ok(()) => {
                    let snap = runtime.settings_snapshot();
                    let out = serde_json::to_vec(&snap).unwrap_or_else(|_| b"{}".to_vec());
                    write_response(&mut stream, "200 OK", "application/json; charset=utf-8", &out);
                }
                Err(e) => {
                    let err = serde_json::json!({ "error": format!("{e:#}") }).to_string();
                    write_response(&mut stream, "500 Internal Server Error", "application/json; charset=utf-8", err.as_bytes());
                }
            }
        }
        _ => {
            write_response(&mut stream, "404 Not Found", "application/json; charset=utf-8", br#"{"error":"not_found"}"#);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serves_dashboard_status_settings_and_pair_qr() {
        let d = tempfile::tempdir().unwrap();
        let state = Arc::new(State::load(&d.path().join("state")).unwrap());
        state.claim("a".repeat(64).as_str()).unwrap();
        let rt = Arc::new(Runtime::new(
            None,
            d.path().join("photos"),
            47615,
            47616,
            60,
            true,
            "deadbeef".repeat(8),
            Some("https://cloudwind.example.workers.dev".into()),
            Some("cloudwind://pair/testpayload".into()),
            "http://127.0.0.1:0".into(),
        ));
        rt.record_direct("IMG_0001.jpg", false);

        let server = start(0).unwrap();
        let addr = server.addr();
        let rt2 = rt.clone();
        let st2 = state.clone();
        let srv2 = server.clone();
        std::thread::spawn(move || serve(rt2, st2, srv2));

        let base = format!("http://{addr}");
        let html = ureq::get(&format!("{base}/")).call().unwrap().into_string().unwrap();
        assert!(html.contains("Cloudwind"));

        let status: serde_json::Value = ureq::get(&format!("{base}/api/status")).call().unwrap().into_json().unwrap();
        assert_eq!(status["total_stored"], 1);
        assert_eq!(status["session_direct"], 1);
        assert_eq!(status["recent"][0]["message"], "IMG_0001.jpg");

        let settings: serde_json::Value = ureq::get(&format!("{base}/api/settings")).call().unwrap().into_json().unwrap();
        assert_eq!(settings["sync_interval_secs"], 60);

        let updated: serde_json::Value = ureq::post(&format!("{base}/api/settings"))
            .send_json(serde_json::json!({ "sync_interval_secs": 120 }))
            .unwrap()
            .into_json()
            .unwrap();
        assert_eq!(updated["sync_interval_secs"], 120);
        assert_eq!(rt.interval_secs(), 120);

        let pair: serde_json::Value = ureq::get(&format!("{base}/api/pair")).call().unwrap().into_json().unwrap();
        assert_eq!(pair["code"], "cloudwind://pair/testpayload");
        assert!(pair["svg"].as_str().unwrap().contains("<svg"));

        let sync_resp = ureq::post(&format!("{base}/api/sync")).call().unwrap();
        assert_eq!(sync_resp.status(), 202);

        let bad_origin = ureq::post(&format!("{base}/api/sync")).set("Origin", "https://evil.example").call();
        assert!(matches!(bad_origin, Err(ureq::Error::Status(403, _))));

        rt.request_shutdown();
        server.unblock();
    }
}
