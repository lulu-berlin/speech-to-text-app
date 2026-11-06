//! Roadmap item 67: settings, the device, threads and upload defaults. `s` opens the settings from
//! `GET /api/settings`; Up/Down select a setting and Left/Right change it, and each change is saved
//! at once, as the browser's Settings page does (ADR 0062): `PUT /api/settings` with the settings as
//! a whole - the default frontend and terminal command included, since the contract reads a missing
//! one as none - and the answer is what the screen shows. Both requests carry the per-install
//! secret.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// Stored settings with every field set, so a write that drops one shows.
const SETTINGS: &str = r#"{"device":"auto","threads":4,"defaultLanguage":"de","speakersMode":"fixed","speakersCount":3,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{"gpu:0":"NVIDIA GeForce RTX 3060","gpu:1":"AMD Radeon RX 6600"},"deviceProbeError":null}"#;
/// The same settings after the write that raised the threads to 5.
const SAVED: &str = r#"{"device":"auto","threads":5,"defaultLanguage":"de","speakersMode":"fixed","speakersCount":3,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{"gpu:0":"NVIDIA GeForce RTX 3060","gpu:1":"AMD Radeon RX 6600"},"deviceProbeError":null}"#;
const WAIT: Duration = Duration::from_secs(20);

/// Whether one line of `screen` holds both `label` and `value`: a setting's row.
fn row(screen: &str, label: &str, value: &str) -> bool {
    screen
        .lines()
        .any(|line| line.contains(label) && line.contains(value))
}

#[test]
fn a_changed_setting_is_saved_whole_and_the_answer_is_shown() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // `s`: the stored settings, from GET /api/settings.
    tui.press(b"s");
    let read = service.next_get(WAIT);
    assert!(
        read.request().line.starts_with("GET /api/settings "),
        "{:?}",
        read.request().line
    );
    assert_eq!(
        read.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    read.answer("200 OK", SETTINGS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Settings") && row(text, "Threads", "4")
    });
    assert!(row(&screen, "Device", "auto"), "{screen}");
    assert!(row(&screen, "Language", "de"), "{screen}");
    assert!(row(&screen, "Speakers", "fixed"), "{screen}");
    assert!(row(&screen, "Speaker count", "3"), "{screen}");
    assert!(row(&screen, "Max speakers", "12"), "{screen}");

    // Down to the threads, Right raises them: saved at once, with every other setting as it was.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[C");
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    assert_eq!(
        write.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        json!({
            "device": "auto",
            "threads": 5,
            "defaultLanguage": "de",
            "speakersMode": "fixed",
            "speakersCount": 3,
            "maxSpeakers": 12,
            "defaultFrontend": "browser",
            "terminalCommand": "kitty -e"
        })
    );
    write.answer("200 OK", SAVED);
    tui.wait_for(WAIT, |text| row(text, "Threads", "5"));
}
