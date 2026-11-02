//! Roadmap item 66, test-list item 5 (the entry model): autostart is one user-scoped entry per OS —
//! an XDG `.desktop` file on Linux, a LaunchAgent property list on macOS, the `HKCU\…\Run` value on
//! Windows — each running the backend runner in service mode. The CI legs prove the OS acts on it;
//! this pins what gets installed.

use std::path::{Path, PathBuf};

use stt_client::{Autostart, Os, autostart_entry};

/// A runner whose path has a space, so quoting is checked on every OS.
const RUNNER: &str = "/opt/STT Apps/stt-service";

#[test]
fn linux_installs_an_xdg_desktop_entry_running_the_runner_in_service_mode() {
    let entry = autostart_entry(Os::Linux, Path::new("/home/lulu"), Path::new(RUNNER));

    let Autostart::File { path, content } = entry else {
        panic!("Linux autostarts through a file, got {entry:?}");
    };
    assert_eq!(
        path,
        PathBuf::from("/home/lulu/.config/autostart/stt-app.desktop")
    );
    assert!(content.contains("Type=Application"), "{content}");
    assert!(content.contains("Name="), "{content}");
    assert!(
        content.contains("Exec=\"/opt/STT Apps/stt-service\" service"),
        "{content}"
    );
    assert!(
        content.contains("X-GNOME-Autostart-enabled=true"),
        "{content}"
    );
}

#[test]
fn macos_installs_a_launch_agent_that_runs_the_runner_at_load() {
    let entry = autostart_entry(Os::MacOs, Path::new("/Users/lulu"), Path::new(RUNNER));

    let Autostart::File { path, content } = entry else {
        panic!("macOS autostarts through a file, got {entry:?}");
    };
    assert_eq!(
        path,
        PathBuf::from("/Users/lulu/Library/LaunchAgents/dev.sttapp.service.plist")
    );
    assert!(
        content.contains("<key>Label</key>\n    <string>dev.sttapp.service</string>"),
        "{content}"
    );
    assert!(
        content.contains("<string>/opt/STT Apps/stt-service</string>"),
        "{content}"
    );
    assert!(content.contains("<string>service</string>"), "{content}");
    assert!(
        content.contains("<key>RunAtLoad</key>\n    <true/>"),
        "{content}"
    );
}

#[test]
fn windows_installs_an_hkcu_run_value() {
    let entry = autostart_entry(
        Os::Windows,
        Path::new("/users/lulu"),
        Path::new("C:\\STT Apps\\stt-service.exe"),
    );

    let Autostart::WindowsRun { key, name, command } = entry else {
        panic!("Windows autostarts through HKCU\\Run, got {entry:?}");
    };
    assert_eq!(key, r"Software\Microsoft\Windows\CurrentVersion\Run");
    assert_eq!(name, "stt-app");
    assert_eq!(command, r#""C:\STT Apps\stt-service.exe" service"#);
}
