//! Roadmap item 67: the TUI is a client of the per-user service, never a second backend. With no
//! service running it says how to start one - `stt-app open` - and exits, starting nothing itself.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;

use std::env;
use std::fs;
use std::path::Path;
use std::process::Command;
use std::thread;
use std::time::Duration;

/// The runner the TUI finds beside itself is a copy of this test binary. Run as `stt-service`, it
/// records that it was started - which the TUI must never cause - and does nothing else. The test's
/// name contains "service", so a runner started with the `service` argument (as `stt-app` starts it)
/// still runs this test as its harness filter and leaves the record.
fn ran_as_the_runner() -> bool {
    let exe = env::current_exe().unwrap();
    if exe.file_stem().unwrap() != "stt-service" {
        return false;
    }
    fs::write(exe.with_file_name("runner-started"), "").unwrap();
    true
}

#[test]
fn with_no_service_running_the_tui_names_stt_app_open_and_starts_no_service() {
    if ran_as_the_runner() {
        return;
    }
    // Never started (no bootstrap files at all), and crashed (a runtime.json whose endpoint nothing
    // answers on).
    for crashed in [false, true] {
        let dir = tempfile::tempdir().unwrap();
        let bin = dir.path().join("bin");
        let state = dir.path().join("state");
        fs::create_dir(&bin).unwrap();
        fs::create_dir(&state).unwrap();
        let suffix = env::consts::EXE_SUFFIX;
        let tui = bin.join(format!("stt-tui{suffix}"));
        fs::copy(env!("CARGO_BIN_EXE_stt-tui"), &tui).unwrap();
        fs::copy(
            env::current_exe().unwrap(),
            bin.join(format!("stt-service{suffix}")),
        )
        .unwrap();
        if crashed {
            support::write_bootstrap(&state, support::closed_port());
        }

        let out = Command::new(&tui)
            .env("STT_CONFIG_DIR", &state)
            .env("STT_RUNTIME_DIR", &state)
            .output()
            .unwrap();

        let stderr = String::from_utf8_lossy(&out.stderr);
        assert!(!out.status.success(), "crashed: {crashed}; {stderr}");
        assert!(
            stderr.contains("stt-app open"),
            "crashed: {crashed}; {stderr}"
        );
        // A runner the TUI started would be detached, so give it time to leave its record.
        thread::sleep(Duration::from_secs(1));
        assert!(
            !runner_started(&bin),
            "crashed: {crashed}: the TUI started the service"
        );
    }
}

fn runner_started(bin: &Path) -> bool {
    bin.join("runner-started").exists()
}
