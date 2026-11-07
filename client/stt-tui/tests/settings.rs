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
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
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

/// `base` (the service's settings) with `change`'s fields replaced: the answer to a stored write.
fn stored(base: &str, change: Value) -> String {
    let mut settings: Value = serde_json::from_str(base).unwrap();
    for (key, value) in change.as_object().unwrap() {
        settings[key] = value.clone();
    }
    settings.to_string()
}

/// The body of a settings write: every stored setting, as the contract takes them.
fn body(change: Value) -> Value {
    let mut settings = json!({
        "device": "auto",
        "threads": 4,
        "defaultLanguage": "de",
        "speakersMode": "fixed",
        "speakersCount": 3,
        "maxSpeakers": 12,
        "defaultFrontend": "browser",
        "terminalCommand": "kitty -e"
    });
    for (key, value) in change.as_object().unwrap() {
        settings[key] = value.clone();
    }
    settings
}

#[test]
fn the_device_language_and_speaker_settings_change_within_the_contract() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"s");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Threads", "4"));

    // Device: Right takes the next device the service offers after `auto`, `gpu:0`.
    tui.press(b"\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({ "device": "gpu:0" }))
    );
    let mut current = stored(SETTINGS, json!({ "device": "gpu:0" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| {
        row(text, "Device", "gpu:0 (NVIDIA GeForce RTX 3060)")
    });

    // Language: Right takes the next code after `de`, `es` (the browser's own list).
    tui.press(b"\x1b[B\x1b[B\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({ "device": "gpu:0", "defaultLanguage": "es" }))
    );
    current = stored(&current, json!({ "defaultLanguage": "es" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Language", "es"));

    // Speaker count: Right raises it while the mode is `fixed`.
    tui.press(b"\x1b[B\x1b[B\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({
            "device": "gpu:0",
            "defaultLanguage": "es",
            "speakersCount": 4
        }))
    );
    current = stored(&current, json!({ "speakersCount": 4 }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Speaker count", "4"));

    // Max speakers: Right raises it, within the contract's 1..20.
    tui.press(b"\x1b[B\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({
            "device": "gpu:0",
            "defaultLanguage": "es",
            "speakersCount": 4,
            "maxSpeakers": 13
        }))
    );
    current = stored(&current, json!({ "maxSpeakers": 13 }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Max speakers", "13"));

    // Speakers: Right wraps `fixed` round to `auto`, and the count is no longer fixed.
    tui.press(b"\x1b[A\x1b[A\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({
            "device": "gpu:0",
            "defaultLanguage": "es",
            "speakersMode": "auto",
            "speakersCount": null,
            "maxSpeakers": 13
        }))
    );
    current = stored(
        &current,
        json!({ "speakersMode": "auto", "speakersCount": null }),
    );
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Speakers", "auto"));
}

/// Two frontends installed, as `GET /api/frontends` lists them (roadmap item 66).
const FRONTENDS: &str = r#"{"frontends":[{"id":"browser","name":"Browser","kind":"browser"},{"id":"window","name":"Window","kind":"gui"}]}"#;

#[test]
fn the_default_frontend_and_the_terminal_command_are_saved() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // `s`: the settings, then the installed frontends.
    tui.press(b"s");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    let frontends = service.next_get(WAIT);
    assert!(
        frontends.request().line.starts_with("GET /api/frontends "),
        "{:?}",
        frontends.request().line
    );
    assert_eq!(
        frontends.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    frontends.answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| {
        row(text, "Default frontend", "browser") && row(text, "Terminal command", "kitty -e")
    });

    // Down to the default frontend, Right takes the next installed one, `window`.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({ "defaultFrontend": "window" }))
    );
    let mut current = stored(SETTINGS, json!({ "defaultFrontend": "window" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Default frontend", "window"));

    // Down to the terminal command; Enter opens a prompt holding it. Typed over, Enter saves it.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Terminal: kitty -e"));
    tui.press(&[0x7f; "kitty -e".len()]);
    tui.press(b"alacritty -e\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({ "defaultFrontend": "window", "terminalCommand": "alacritty -e" }))
    );
    current = stored(&current, json!({ "terminalCommand": "alacritty -e" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Terminal command", "alacritty -e"));

    // An emptied prompt saves none: the OS launcher is in charge again.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Terminal: alacritty -e"));
    tui.press(&[0x7f; "alacritty -e".len()]);
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(json!({ "defaultFrontend": "window", "terminalCommand": null }))
    );
    write.answer(
        "200 OK",
        &stored(&current, json!({ "terminalCommand": null })),
    );
    tui.wait_for(WAIT, |text| row(text, "Terminal command", "-"));
}

/// Settings with the speaker mode off and no count stored (`speakersCount` null).
const OFF: &str = r#"{"device":"auto","threads":4,"defaultLanguage":"de","speakersMode":"off","speakersCount":null,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{"gpu:0":"NVIDIA GeForce RTX 3060","gpu:1":"AMD Radeon RX 6600"},"deviceProbeError":null}"#;

#[test]
fn fixed_needs_a_speaker_count_and_the_count_is_chosen_first() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"s");
    service.next_get(WAIT).answer("200 OK", OFF);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Speakers", "off"));

    // Down to Speakers, Right asks for `fixed`: with no count it changes nothing and says so.
    tui.press(b"\x1b[B\x1b[B\x1b[B");
    tui.press(b"\x1b[C");
    let screen = tui.wait_for(WAIT, |text| text.contains("set a speaker count first"));
    assert!(
        !service.command_arrived(),
        "an invalid write is never sent:\n{screen}"
    );
    assert!(row(&screen, "Speakers", "off"), "{screen}");

    // The count row chooses the count first, without a write of its own (the mode is not fixed).
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[C");
    let screen = tui.wait_for(WAIT, |text| row(text, "Speaker count", "1"));
    assert!(
        !service.command_arrived(),
        "a count means nothing outside fixed:\n{screen}"
    );

    // Back up, Right now stores `fixed` with the chosen count.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        json!({
            "device": "auto",
            "threads": 4,
            "defaultLanguage": "de",
            "speakersMode": "fixed",
            "speakersCount": 1,
            "maxSpeakers": 12,
            "defaultFrontend": "browser",
            "terminalCommand": "kitty -e"
        })
    );
    write.answer(
        "200 OK",
        &stored(OFF, json!({ "speakersMode": "fixed", "speakersCount": 1 })),
    );
    tui.wait_for(WAIT, |text| row(text, "Speakers", "fixed"));
}

#[test]
fn a_settings_change_made_elsewhere_updates_the_open_settings() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"s");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Threads", "4"));

    // The browser saved other settings: the event carries them, without the device list.
    stream.send(
        "settings-updated",
        include_str!("../../../docs/api/settings-updated.json"),
    );
    let screen = tui.wait_for(WAIT, |text| row(text, "Threads", "6"));
    assert!(
        row(&screen, "Device", "gpu:1 (AMD Radeon RX 6600)"),
        "the device list and GPU names the screen read stay:\n{screen}"
    );
    assert!(row(&screen, "Language", "de"), "{screen}");
    assert!(row(&screen, "Default frontend", "-"), "{screen}");
    assert!(row(&screen, "Terminal command", "-"), "{screen}");
    assert!(
        !service.command_arrived(),
        "following the browser writes nothing:\n{screen}"
    );

    // A later change starts from what the browser stored, the device list still offered.
    tui.press(b"\x1b[C");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        json!({
            "device": "cpu",
            "threads": 6,
            "defaultLanguage": "de",
            "speakersMode": "fixed",
            "speakersCount": 3,
            "maxSpeakers": 12,
            "defaultFrontend": null,
            "terminalCommand": null
        }),
        "Right on Device wraps gpu:1 round to the first offered device, cpu"
    );
}
