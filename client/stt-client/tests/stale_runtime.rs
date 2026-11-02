//! Roadmap item 66, test-list D3 (the client half): a `runtime.json` left by a crash is ignored. A
//! client treats it as current only when its endpoint answers `GET /api/health` with `200` to the
//! per-install secret; a refused connection, or a program on that port that does not accept the
//! secret, means no service runs, so `stt-app open` starts one instead of failing on a dead port.

mod support;

use std::net::TcpListener;

use stt_client::{ServiceDirs, locate};

#[test]
fn a_runtime_json_whose_endpoint_does_not_answer_as_the_service_is_ignored() {
    // A crash: the port the leftover names is closed.
    let closed = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let crashed = bootstrap_files(closed);
    let error = locate(&crashed.dirs()).unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::NotFound, "{error}");

    // Another program took the port: it answers, but refuses the secret as the service never would.
    let foreign = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = foreign.local_addr().unwrap().port();
    let stand_in = std::thread::spawn(move || {
        let (mut stream, _) = foreign.accept().unwrap();
        let request = support::read(&mut stream);
        support::respond(&mut stream, "401 Unauthorized", "");
        request
    });
    let taken = bootstrap_files(port);
    let error = locate(&taken.dirs()).unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::NotFound, "{error}");
    let request = stand_in.join().unwrap();
    assert!(
        request.line.starts_with("GET /api/health "),
        "{:?}",
        request.head
    );
}

struct BootstrapFiles {
    config: tempfile::TempDir,
    runtime: tempfile::TempDir,
}

impl BootstrapFiles {
    fn dirs(&self) -> ServiceDirs {
        ServiceDirs {
            config_dir: self.config.path().to_path_buf(),
            runtime_dir: self.runtime.path().to_path_buf(),
        }
    }
}

/// `config.json` and a `runtime.json` advertising `port`, shaped as the service writes them.
fn bootstrap_files(port: u16) -> BootstrapFiles {
    let files = BootstrapFiles {
        config: tempfile::tempdir().unwrap(),
        runtime: tempfile::tempdir().unwrap(),
    };
    support::write(
        files.config.path(),
        "config.json",
        r#"{"port":28763,"secret":"the-install-secret"}"#,
    );
    support::write(
        files.runtime.path(),
        "runtime.json",
        &format!(r#"{{"pid":4242,"endpoint":"http://127.0.0.1:{port}"}}"#),
    );
    files
}
