//! Roadmap item 66, test-list item 4 (the quit half): quitting is explicit. `stt-app quit` asks the
//! running service to stop — `POST /api/quit` with the per-install secret, answered `202` before the
//! stop begins — and reports that no service runs when none answers.

mod support;

use std::net::TcpListener;
use std::sync::mpsc;

use stt_client::{ServiceDirs, quit};

#[test]
fn quit_asks_the_running_service_to_stop_with_its_secret() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let (seen, requests) = mpsc::channel();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let mut stream = stream.unwrap();
            let request = support::read(&mut stream);
            if request.line.starts_with("POST /api/quit") {
                seen.send(request.header("Authorization").map(str::to_owned))
                    .unwrap();
                support::respond(&mut stream, "202 Accepted", "");
            } else {
                support::respond(&mut stream, "200 OK", r#"{"status":"ok"}"#);
            }
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
    let dirs = ServiceDirs {
        config_dir: dir.path().to_path_buf(),
        runtime_dir: dir.path().to_path_buf(),
    };

    quit(&dirs).unwrap();

    assert_eq!(
        requests
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap()
            .as_deref(),
        Some("Bearer the-install-secret")
    );
}

#[test]
fn quit_reports_no_service_when_none_answers() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = ServiceDirs {
        config_dir: dir.path().to_path_buf(),
        runtime_dir: dir.path().to_path_buf(),
    };

    let error = quit(&dirs).unwrap_err();

    assert_eq!(error.kind(), std::io::ErrorKind::NotFound, "{error}");
}
