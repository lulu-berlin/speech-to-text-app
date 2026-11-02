//! Roadmap item 66, decision (b): `stt-app` starts the service through the runner beside it — the
//! packaged layout puts `stt-service` in the same directory — and says so when that runner is
//! missing, instead of reporting a dead endpoint. This test binary has no sibling runner, which is
//! exactly the missing case.

mod support;

use std::process::Command;

#[test]
fn with_no_service_and_no_runner_beside_it_stt_app_names_the_runner_it_needs() {
    let dir = tempfile::tempdir().unwrap();
    support::write(
        dir.path(),
        "config.json",
        r#"{"port":28763,"secret":"the-install-secret"}"#,
    );
    let frontends = dir.path().join("frontends");
    std::fs::create_dir(&frontends).unwrap();
    support::write(
        &frontends,
        "browser.json",
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    );

    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args(["open", "browser", "--print-url"])
        .env("STT_CONFIG_DIR", dir.path())
        .env("STT_RUNTIME_DIR", dir.path())
        .output()
        .unwrap();

    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(!out.status.success(), "stderr: {stderr}");
    assert!(stderr.contains("stt-service"), "stderr: {stderr}");
    assert!(
        !stderr.contains("no service answers"),
        "a missing runner is not a dead endpoint: {stderr}"
    );
}

#[test]
fn opening_a_terminal_frontend_ensures_the_service_before_the_terminal() {
    let dir = tempfile::tempdir().unwrap();
    support::write(
        dir.path(),
        "config.json",
        r#"{"port":28763,"secret":"the-install-secret"}"#,
    );
    let frontends = dir.path().join("frontends");
    std::fs::create_dir(&frontends).unwrap();
    support::write(
        &frontends,
        "tui.json",
        r#"{"version":1,"id":"tui","kind":"terminal","name":"Terminal TUI","command":"stt-tui","args":[]}"#,
    );

    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args(["open", "tui"])
        .env("STT_CONFIG_DIR", dir.path())
        .env("STT_RUNTIME_DIR", dir.path())
        .env("PATH", dir.path())
        .output()
        .unwrap();

    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(!out.status.success(), "stderr: {stderr}");
    assert!(stderr.contains("stt-service"), "stderr: {stderr}");
    assert!(
        !stderr.contains("no terminal to run"),
        "the service is ensured before the terminal is chosen: {stderr}"
    );
}
