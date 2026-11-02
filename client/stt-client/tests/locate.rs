//! Roadmap item 66, test list item 1 (the client half): a client finds the running service through the
//! bootstrap files — the endpoint the running instance advertises in the ephemeral `runtime.json`, and
//! the per-install secret from the durable `config.json`. The endpoint answers the client's health
//! check as the running service does (test-list D3).

mod support;

use std::net::TcpListener;

use stt_client::{ServiceDirs, locate};

#[test]
fn a_client_reads_the_endpoint_from_runtime_json_and_the_secret_from_config_json() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    support::answer_forever(listener, |_| support::healthy());
    let config_dir = tempfile::tempdir().unwrap();
    let runtime_dir = tempfile::tempdir().unwrap();
    // Shaped as the service writes them (stt.service.ServiceInstance). The durable port and the
    // advertised endpoint's port differ, so the assertion shows which file the endpoint comes from.
    support::write(
        config_dir.path(),
        "config.json",
        r#"{"port":28763,"secret":"secret-from-config"}"#,
    );
    support::write(
        runtime_dir.path(),
        "runtime.json",
        &format!(r#"{{"pid":4242,"endpoint":"http://127.0.0.1:{port}"}}"#),
    );

    let service = locate(&ServiceDirs {
        config_dir: config_dir.path().to_path_buf(),
        runtime_dir: runtime_dir.path().to_path_buf(),
    })
    .unwrap();

    assert_eq!(service.endpoint, format!("http://127.0.0.1:{port}"));
    assert_eq!(service.secret, "secret-from-config");
}
