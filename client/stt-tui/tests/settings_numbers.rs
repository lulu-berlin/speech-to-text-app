//! Roadmap item 67, second amendment: the thread count, the speaker count and the max speakers are
//! edited inside their row. Enter opens the row's number; digits and Backspace edit it, Up/Down
//! change it by one, Enter checks it against its range (the browser's words when it is outside)
//! and saves it, Escape leaves the stored value. Nothing on Settings changes with Left/Right any
//! more: they move between the tabs, as on the other pages.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, body, stored, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn numbers_are_edited_in_their_row_checked_and_saved_and_left_right_move_between_tabs() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("kitty -e"));

    // Enter on the threads opens their number inside the row, with its keys beside it.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let editing = tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("4▏"));
    assert!(
        value(&editing, "Threads").contains("Enter save · Esc cancel · ↑/↓ ±1"),
        "{editing}"
    );

    // Backspace and digits edit it; Up and Down change it by one; Enter saves it whole.
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("▏"));
    tui.press(b"12");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("12▏"));
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("13▏"));
    tui.press(b"\x1b[B\x1b[B");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("11▏"));
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    let change = json!({ "threads": 11, "maxSpeakers": 3 });
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(SETTINGS, change.clone())
    );
    write.answer("200 OK", &stored(SETTINGS, change));
    tui.wait_for(WAIT, |text| value(text, "Threads") == "11");

    // An emptied thread count is auto, whisper's default: stored as none.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("11▏"));
    tui.press(b"\x7f\x7f");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("▏"));
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    let change = json!({ "threads": null, "maxSpeakers": 3 });
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(SETTINGS, change.clone())
    );
    write.answer("200 OK", &stored(SETTINGS, change));
    tui.wait_for(WAIT, |text| value(text, "Threads") == "auto");

    // Escape leaves the stored value and Settings stays open.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| value(text, "Threads").contains("Esc cancel"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| {
        value(text, "Threads") == "auto" && text.contains("kitty -e")
    });
    assert!(
        !service.command_arrived(),
        "Escape sends nothing:\n{screen}"
    );

    // Left/Right change nothing on Settings: Right moves to the next tab, wrapping to the Queue.
    tui.press(b"\x1b[C");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    assert!(
        !service.command_arrived(),
        "Right on Settings saves nothing"
    );
}
