//! Roadmap item 66, test-list item 5 (the opt-in switch): `stt-app autostart on` installs the
//! user-scoped entry that runs the service at login, `off` removes it, and `on` again replaces it.
//! This test runs on Linux, where the entry is an XDG desktop file; macOS and Windows get their
//! entries from the same model (`autostart_entry`), whose shape `tests/autostart.rs` pins. The whole
//! file is Linux-only, so no unused import or helper reaches clippy on the other platforms.

#![cfg(target_os = "linux")]

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;

use std::process::Command;

// The XDG entry is Linux's; macOS writes a LaunchAgent and Windows an HKCU Run value, and
// `tests/autostart.rs` pins both of those shapes on their own platforms.
#[test]
fn autostart_on_installs_the_entry_and_off_removes_it() {
    let home = tempfile::tempdir().unwrap();
    let dirs = tempfile::tempdir().unwrap();
    let exe = env!("CARGO_BIN_EXE_stt-app");
    let entry = home.path().join(".config/autostart/stt-app.desktop");

    let on = run(exe, dirs.path(), home.path(), ["autostart", "on"]);
    assert!(
        on.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&on.stderr)
    );
    let content = std::fs::read_to_string(&entry).unwrap();
    assert!(content.contains("Exec="), "{content}");
    assert!(content.contains(" service"), "{content}");

    let off = run(exe, dirs.path(), home.path(), ["autostart", "off"]);
    assert!(
        off.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&off.stderr)
    );
    assert!(!entry.exists(), "autostart off must remove {entry:?}");
}

fn run(
    exe: &str,
    config: &std::path::Path,
    home: &std::path::Path,
    args: [&str; 2],
) -> std::process::Output {
    Command::new(exe)
        .args(args)
        .env("STT_CONFIG_DIR", config)
        .env("STT_RUNTIME_DIR", config)
        .env("HOME", home)
        .output()
        .unwrap()
}
