//! Roadmap item 67: settings, the device, threads and upload defaults. `s` opens the settings from
//! `GET /api/settings`; Up/Down select a setting and Enter changes it - a dropdown for a set of
//! values, the row itself for a number - and each change is saved at once, as the browser's
//! Settings page does (ADR 0062): `PUT /api/settings` with the settings as a whole - the default
//! frontend and terminal command included, since the contract reads a missing one as none - and
//! the answer is what the screen shows. Both requests carry the per-install secret.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, GPU0, GPU1, SETTINGS, body, stored};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
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
    service.capture_next_settings();
    tui.press(b"s");
    let read = service.next_get_from(&tui, WAIT);
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
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Settings") && row(text, "Threads", "4")
    });
    assert!(row(&screen, "Device", "Auto"), "{screen}");
    assert!(row(&screen, "Language", "de"), "{screen}");
    assert!(row(&screen, "Speakers", "3"), "{screen}");
    assert!(
        !screen.contains("Max speakers"),
        "one row for the speakers:\n{screen}"
    );

    // Down to the threads, Enter, Up and Enter raise them: saved at once, with every other setting
    // as it was.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("4▏"));
    tui.press(b"\x1b[A\r");
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
            "maxSpeakers": 3,
            "defaultFrontend": "browser",
            "terminalCommand": "kitty -e",
            "appLanguage": null,
            "themeMode": "auto"
        })
    );
    write.answer("200 OK", &stored(SETTINGS, json!({ "threads": 5 })));
    tui.wait_for(WAIT, |text| row(text, "Threads", "5"));
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
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Threads", "4"));

    // Device: the dropdown's next device after `auto`, `gpu:0`.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ Auto"));
    tui.press(b"\x1b[B\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(SETTINGS, json!({ "device": "gpu:0", "maxSpeakers": 3 }))
    );
    let mut current = stored(SETTINGS, json!({ "device": "gpu:0", "maxSpeakers": 3 }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Device", GPU0));

    // Language: the dropdown's next code after `de`, `es` (the browser's own list).
    tui.press(b"\x1b[B\x1b[B\r");
    tui.wait_for(WAIT, |text| text.contains("✓ de"));
    tui.press(b"\x1b[B\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            SETTINGS,
            json!({ "device": "gpu:0", "defaultLanguage": "es", "maxSpeakers": 3 })
        )
    );
    current = stored(&current, json!({ "defaultLanguage": "es" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Language", "es"));

    // Speakers: one choice - auto, off, or a count, a fixed count being the upload's maximum too.
    // The list opens on the stored count, 3; Up to `auto` unfixes it.
    tui.press(b"\x1b[B\r");
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    for _ in 0..4 {
        tui.press(b"\x1b[A");
    }
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            SETTINGS,
            json!({
                "device": "gpu:0",
                "defaultLanguage": "es",
                "speakersMode": "auto",
                "speakersCount": null,
                "maxSpeakers": 3
            })
        )
    );
    current = stored(
        &current,
        json!({
            "speakersMode": "auto",
            "speakersCount": null,
            "maxSpeakers": 3
        }),
    );
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Speakers", "auto"));
}

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
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    let frontends = service.next_get_from(&tui, WAIT);
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
        row(text, "Default user interface", "browser") && row(text, "Terminal command", "kitty -e")
    });

    // Down to the default frontend, the dropdown's next installed one, `window`.
    for _ in 0..4 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ browser"));
    tui.press(b"\x1b[B\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            SETTINGS,
            json!({ "defaultFrontend": "window", "maxSpeakers": 3 })
        )
    );
    let mut current = stored(
        SETTINGS,
        json!({ "defaultFrontend": "window", "maxSpeakers": 3 }),
    );
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Default user interface", "window"));

    // Down to the terminal command; Enter edits it in its row, starting with the stored one.
    // Typed over, Enter saves it.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.lines()
            .any(|line| line.contains("Terminal command") && line.contains("kitty -e▏"))
    });
    tui.press(&[0x7f; "kitty -e".len()]);
    tui.press(b"alacritty -e\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            SETTINGS,
            json!({
                "defaultFrontend": "window",
                "terminalCommand": "alacritty -e",
                "maxSpeakers": 3
            })
        )
    );
    current = stored(&current, json!({ "terminalCommand": "alacritty -e" }));
    write.answer("200 OK", &current);
    tui.wait_for(WAIT, |text| row(text, "Terminal command", "alacritty -e"));

    // An emptied row saves none: the OS launcher is in charge again, and the row says which.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("alacritty -e▏"));
    tui.press(&[0x7f; "alacritty -e".len()]);
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            SETTINGS,
            json!({
                "defaultFrontend": "window",
                "terminalCommand": null,
                "maxSpeakers": 3
            })
        )
    );
    write.answer(
        "200 OK",
        &stored(&current, json!({ "terminalCommand": null })),
    );
    tui.wait_for(WAIT, |text| row(text, "Terminal command", "default:"));
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
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Threads", "4"));

    // The browser saved other settings: the event carries them, without the device list.
    stream.send(
        "settings-updated",
        include_str!("../../../docs/api/settings-updated.json"),
    );
    // The example stores German as the App language too (item 88), so the open page switches to
    // German: the labels are the catalog's, which is the point of the shared text.
    stt_client::set_locale(Some("de"), &[]);
    let german = |key: &str| stt_client::text(key, &[]);
    let screen = tui.wait_for(WAIT, |text| row(text, &german("settings.threads"), "6"));
    assert!(
        row(&screen, &german("settings.device"), GPU1),
        "the device list and GPU names the screen read stay:\n{screen}"
    );
    assert!(row(&screen, &german("settings.language"), "de"), "{screen}");
    assert!(
        row(&screen, &german("settings.defaultFrontend"), "-"),
        "{screen}"
    );
    assert!(
        row(&screen, &german("settings.terminalCommand"), "Standard:"),
        "{screen}"
    );
    assert!(
        !service.command_arrived(),
        "following the browser writes nothing:\n{screen}"
    );

    // A later change starts from what the browser stored, the device list still offered.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains(&format!("✓ {GPU1}")));
    tui.press(b"\x1b[A\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        json!({
            "device": "gpu:0",
            "threads": 6,
            "defaultLanguage": "de",
            "speakersMode": "fixed",
            "speakersCount": 3,
            "maxSpeakers": 3,
            "defaultFrontend": null,
            "terminalCommand": null,
            "appLanguage": "de",
            "themeMode": "auto"
        }),
        "the dropdown's device above gpu:1 is gpu:0"
    );
}

#[test]
fn choosing_a_count_in_the_speakers_row_fixes_it_and_caps_the_maximum() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer(
        "200 OK",
        &stored(
            SETTINGS,
            json!({ "speakersMode": "auto", "speakersCount": null }),
        ),
    );
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| row(text, "Threads", "4"));

    // Down to Speakers; Enter opens its list at the stored `auto`, and Down to the count `3` and
    // Enter fixes it - the count is the upload's maximum too, in one write.
    for _ in 0..3 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ auto"));
    for _ in 0..4 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    let sent: Value = serde_json::from_str(&write.request().body).unwrap();
    assert_eq!(sent["speakersMode"], json!("fixed"));
    assert_eq!(sent["speakersCount"], json!(3));
    assert_eq!(sent["maxSpeakers"], json!(3));
    write.answer(
        "200 OK",
        &stored(
            SETTINGS,
            json!({
                "speakersMode": "fixed",
                "speakersCount": 3,
                "maxSpeakers": 3
            }),
        ),
    );
    tui.wait_for(WAIT, |text| row(text, "Speakers", "3"));

    // Enter on the stored count opens the list on it; two down is `5`, which saves both again.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    for _ in 0..2 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    let sent: Value = serde_json::from_str(&write.request().body).unwrap();
    assert_eq!(sent["speakersMode"], json!("fixed"));
    assert_eq!(sent["speakersCount"], json!(5));
    assert_eq!(sent["maxSpeakers"], json!(5));
}
