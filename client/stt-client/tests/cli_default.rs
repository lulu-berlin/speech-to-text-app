mod support;

use std::net::TcpListener;
use std::process::Command;

/// A stand-in service that answers the client's health checks, `GET /api/settings` with a default frontend and
/// launch-token requests, one connection each, for as long as the test runs, and whose directories hold the
/// registry with that frontend.
fn stand_in() -> (tempfile::TempDir, u16) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    support::answer_forever(listener, |request| {
        let body = if request.line.starts_with("GET /api/health") {
            r#"{"status":"ok"}"#
        } else if request.line.starts_with("GET /api/settings") {
            r#"{"defaultFrontend":"browser"}"#
        } else {
            r#"{"token":"one-time"}"#
        };
        ("200 OK", body.to_string())
    });
    let dir = tempfile::tempdir().unwrap();
    support::write(
        dir.path(),
        "config.json",
        r#"{"port":28763,"secret":"the-install-secret"}"#,
    );
    support::write(
        dir.path(),
        "runtime.json",
        &format!(r#"{{"pid":4242,"endpoint":"http://127.0.0.1:{port}"}}"#),
    );
    let frontends = dir.path().join("frontends");
    std::fs::create_dir(&frontends).unwrap();
    support::write(
        &frontends,
        "browser.json",
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    );
    (dir, port)
}

#[test]
fn open_without_an_id_opens_the_default_frontend_from_the_settings() {
    let (dir, port) = stand_in();
    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args(["open", "--print-url"])
        .env("STT_CONFIG_DIR", dir.path())
        .env("STT_RUNTIME_DIR", dir.path())
        .output()
        .unwrap();

    assert!(
        out.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&out.stdout).trim(),
        format!("http://127.0.0.1:{port}/open?t=one-time")
    );
}
