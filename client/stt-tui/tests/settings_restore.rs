//! Roadmap item 67, third amendment: restoring the default settings asks first and then resets
//! every field (`DELETE /api/settings`). The TUI reaches it as the browser reaches its button: a
//! last row under the fields (the user's choice, 2026-11-13), whose Enter opens the shared
//! confirmation.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS, value};
use terminal::{EventService, Tui, offers, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// The service's defaults, as `DELETE /api/settings` answers them.
const DEFAULTS: &str = include_str!("../../../docs/api/settings.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn restoring_the_defaults_asks_first_and_then_resets_every_field() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 32);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The settings page, every field set.
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| value(text, "Threads") == "4");

    // Down past the six fields: the restore row, whose Enter the footer names.
    for _ in 0..6 {
        tui.press(b"\x1b[B");
    }
    tui.wait_for(WAIT, |text| {
        offers(text, &["Enter restore defaults"], &["Enter choose / edit"])
    });

    // Enter asks first; Escape closes the question with nothing sent.
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("Restore default settings?"));
    assert!(
        screen.contains("Every field goes back to the server's defaults"),
        "{screen}"
    );
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Restore default settings?"));
    tui.settle();
    assert!(
        !service.command_arrived(),
        "nothing is sent until it is confirmed"
    );

    // Up off the row, then a click back on it and Enter, and Enter again: the defaults are
    // restored.
    tui.press(b"\x1b[A");
    let (column, row) = position_of(&screen, "Restore default settings");
    tui.click(column, row);
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Restore default settings?"));
    tui.press(b"\r");
    let restore = service.next_command(WAIT);
    assert!(
        restore.request().line.starts_with("DELETE /api/settings "),
        "{:?}",
        restore.request().line
    );
    assert_eq!(
        restore.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );

    // The service's answer is what every field shows.
    restore.answer("200 OK", DEFAULTS);
    let screen = tui.wait_for(WAIT, |text| value(text, "Threads") == "auto");
    assert_eq!(value(&screen, "Language"), "auto", "{screen}");
    assert_eq!(value(&screen, "Speakers"), "auto", "{screen}");
    assert!(
        !screen.contains("Max speakers"),
        "one row for the speakers:\n{screen}"
    );
}

#[test]
fn restoring_the_defaults_says_saving_while_its_write_runs() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 32);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| value(text, "Threads") == "4");

    // Down past the six fields to the restore row, Enter, and Enter again: the `DELETE` is on
    // its way, and the page says it is saving.
    for _ in 0..6 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Restore default settings?"));
    tui.press(b"\r");
    let restore = service.next_command(WAIT);
    tui.wait_for(WAIT, |text| text.contains("Saving…"));

    // Answered, the defaults show and the page is no longer saving.
    restore.answer("200 OK", DEFAULTS);
    tui.wait_for(WAIT, |text| {
        value(text, "Threads") == "auto" && !text.contains("Saving…")
    });
}
