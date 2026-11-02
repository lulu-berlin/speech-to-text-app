//! Roadmap item 66, ADR 0064: `stt-tray` as a real process. The supported run is the default build
//! (`cargo test`); with `--features tray-menu` the tray under test is the GUI build, so each test
//! puts a real icon on the desktop until its unit stops - compile and lint that feature, but do not
//! run the suite with it. It watches the service it belongs to,
//! restarts it through the runner beside it when it crashed, and exits when it cannot restart it,
//! so the unit is never left half up.
//!
//! The runner beside the tray is a copy of this test binary: started as `stt-service service`, its
//! test filter runs only the `service` stand-in below, which serves the health answer until the
//! test tells it to stop. Run as a normal test, the stand-in does nothing.

mod support;

use std::env;
use std::fs::{self, File};
use std::io::Write;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::thread;
use std::time::{Duration, Instant};

/// Set on the tray's environment, so the runner copy it starts knows to act as the service.
const STAND_IN: &str = "STT_TEST_SERVICE_STAND_IN";

#[test]
fn the_tray_brings_a_crashed_unit_member_back_and_exits_when_it_cannot() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    let state = dir.path().join("state");
    fs::create_dir(&bin).unwrap();
    fs::create_dir(&state).unwrap();
    let suffix = env::consts::EXE_SUFFIX;
    let tray = bin.join(format!("stt-tray{suffix}"));
    let runner = bin.join(format!("stt-service{suffix}"));
    fs::copy(env!("CARGO_BIN_EXE_stt-tray"), &tray).unwrap();
    fs::copy(env::current_exe().unwrap(), &runner).unwrap();
    // A crashed service: its runtime.json is left behind, its endpoint answers nothing.
    support::write_bootstrap(&state, support::closed_port());
    let log = dir.path().join("tray.log");
    let mut child = Command::new(&tray)
        .env("STT_CONFIG_DIR", &state)
        .env("STT_RUNTIME_DIR", &state)
        .env(STAND_IN, "1")
        .stdout(Stdio::null())
        .stderr(File::create(&log).unwrap())
        .spawn()
        .unwrap();

    // The tray restarts it through the runner beside it. The test does not ask the service itself:
    // it waits for the tray's own second health check, which comes only after the tray has seen
    // the restart through - so the next crash cannot catch the tray still waiting for it.
    let restarted = wait_until(Duration::from_secs(30), || health_checks(&state) >= 2);
    if !restarted {
        child.kill().unwrap();
    }
    assert!(
        restarted,
        "the tray must restart the crashed service; it logged: {}",
        fs::read_to_string(&log).unwrap()
    );

    // The tray told the service who it is, so the service can restart it in turn (ADR 0064).
    let registered = wait_until(Duration::from_secs(30), || {
        fs::read_to_string(state.join("tray-registration"))
            .is_ok_and(|body| body.contains("stt-tray"))
    });
    assert!(
        registered,
        "the tray must register itself with the service; it logged: {}",
        fs::read_to_string(&log).unwrap()
    );

    // The runner goes away and the service crashes again: the restart fails, and the tray exits.
    fs::rename(&runner, bin.join(format!("gone{suffix}"))).unwrap();
    support::write_bootstrap(&state, support::closed_port());
    fs::write(state.join("stop"), "").unwrap();
    let status = wait_for_exit(&mut child, Duration::from_secs(30));
    let stderr = fs::read_to_string(&log).unwrap();
    let status = status.unwrap_or_else(|| {
        child.kill().unwrap();
        panic!("the tray must exit when it cannot restart the service; it logged: {stderr}")
    });
    assert!(!status.success(), "{status}");
    // The message names the platform's runner: stt-service on Unix, stt-service.exe on Windows.
    assert!(stderr.contains("no stt-service"), "{stderr}");
}

#[test]
fn the_tray_exits_when_the_unit_was_stopped_on_purpose() {
    let dir = tempfile::tempdir().unwrap();
    let bin = dir.path().join("bin");
    let state = dir.path().join("state");
    fs::create_dir(&bin).unwrap();
    fs::create_dir(&state).unwrap();
    let tray = bin.join(format!("stt-tray{}", env::consts::EXE_SUFFIX));
    fs::copy(env!("CARGO_BIN_EXE_stt-tray"), &tray).unwrap();
    // A clean stop removes runtime.json; config.json stays. No runner is beside the tray, so a
    // restart attempt would fail and exit non-zero.
    support::write_bootstrap(&state, support::closed_port());
    fs::remove_file(state.join("runtime.json")).unwrap();
    let log = dir.path().join("tray.log");

    let mut child = Command::new(&tray)
        .env("STT_CONFIG_DIR", &state)
        .env("STT_RUNTIME_DIR", &state)
        .stdout(Stdio::null())
        .stderr(File::create(&log).unwrap())
        .spawn()
        .unwrap();

    let status = wait_for_exit(&mut child, Duration::from_secs(30));
    let stderr = fs::read_to_string(&log).unwrap();
    let status = status.unwrap_or_else(|| {
        child.kill().unwrap();
        panic!("the tray must exit when the service stopped on purpose; it logged: {stderr}")
    });
    assert!(status.success(), "{status}: {stderr}");
    assert!(!stderr.contains("stops too"), "{stderr}");
}

/// The stand-in the runner copy runs (see the module comment), the only test whose name contains
/// `service`, because the runner's argument is the filter: it advertises itself in
/// `runtime.json`, answers health and records each health check in `health-checks`, and exits once
/// `stop` appears in the runtime directory.
#[test]
fn service() {
    if env::var_os(STAND_IN).is_none() {
        return;
    }
    let dir = PathBuf::from(env::var_os("STT_RUNTIME_DIR").unwrap());
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    support::write(
        &dir,
        "runtime.json",
        &format!(
            r#"{{"pid":{},"endpoint":"http://127.0.0.1:{port}"}}"#,
            std::process::id()
        ),
    );
    let record = dir.clone();
    support::answer_forever(listener, move |request| {
        if request.line.starts_with("POST /api/tray ") {
            let mut registration = fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(record.join("tray-registration"))
                .unwrap();
            registration.write_all(request.body.as_bytes()).unwrap();
            registration.write_all(b"\n").unwrap();
            return ("202 Accepted", String::new());
        }
        let mut checks = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(record.join("health-checks"))
            .unwrap();
        checks.write_all(b"check\n").unwrap();
        support::healthy()
    });
    while !dir.join("stop").exists() {
        thread::sleep(Duration::from_millis(50));
    }
}

/// How many health checks the stand-in in `state` has answered.
fn health_checks(state: &Path) -> usize {
    fs::read_to_string(state.join("health-checks"))
        .map(|checks| checks.lines().count())
        .unwrap_or(0)
}

/// Polls `done` until it holds or `timeout` passes.
fn wait_until(timeout: Duration, done: impl Fn() -> bool) -> bool {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if done() {
            return true;
        }
        thread::sleep(Duration::from_millis(100));
    }
    false
}

/// `child`'s exit status, or `None` when it is still running after `timeout`.
fn wait_for_exit(child: &mut Child, timeout: Duration) -> Option<ExitStatus> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if let Some(status) = child.try_wait().unwrap() {
            return Some(status);
        }
        thread::sleep(Duration::from_millis(100));
    }
    None
}
