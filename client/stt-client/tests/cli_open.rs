mod support;

use std::net::TcpListener;
use std::process::Command;

/// A stand-in service that answers the client's health check and one launch-token request, one
/// connection each, and the directories pointing at it.
fn stand_in() -> (tempfile::TempDir, u16) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in listener.incoming().take(2) {
            let mut stream = stream.unwrap();
            let request = support::read(&mut stream);
            let body = if request.line.starts_with("GET /api/health") {
                r#"{"status":"ok"}"#
            } else {
                r#"{"token":"one-time"}"#
            };
            support::respond(&mut stream, "200 OK", body);
        }
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
fn open_with_a_frontend_id_opens_that_registered_frontend() {
    let (dir, port) = stand_in();
    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args(["open", "browser", "--print-url"])
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

#[test]
fn open_with_an_unknown_frontend_id_fails_and_names_it() {
    let (dir, _) = stand_in();
    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args(["open", "nosuch", "--print-url"])
        .env("STT_CONFIG_DIR", dir.path())
        .env("STT_RUNTIME_DIR", dir.path())
        .output()
        .unwrap();

    assert!(!out.status.success());
    assert!(
        String::from_utf8_lossy(&out.stderr).contains("nosuch"),
        "stderr: {}",
        String::from_utf8_lossy(&out.stderr)
    );
}
