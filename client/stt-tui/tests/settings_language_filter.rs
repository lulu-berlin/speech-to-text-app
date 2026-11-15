//! Roadmap item 67 (the user's report 2026-11-08): the language dropdown is a filtering search -
//! typing narrows the list to the languages whose name or code matches, as History's search
//! narrows its rows, and Backspace widens it again. No dropdown moves its cursor with `j`/`k` any
//! more - the arrows do - so every letter can be typed.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, stored, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_language_dropdown_is_a_filtering_search() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| value(text, "Language") == "de");

    // Down to the language row and Enter: the whole list, "de  german" ticked.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    assert!(screen.contains("en  english"), "{screen}");
    // Its keys line names typing as what narrows it, before anything is typed, so the search has
    // to be found.
    assert!(screen.contains("type filter"), "{screen}");

    // Typing narrows the list to the languages whose code or name matches: `j` is a letter here,
    // not a move.
    tui.press(b"j");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("ja  japanese") && !text.contains("en  english")
    });
    assert!(screen.contains("filter: j"), "{screen}");

    // Backspace widens it again.
    tui.press(&[0x7f]);
    tui.wait_for(WAIT, |text| text.contains("en  english"));

    // Typing chooses: "fr" narrows to French, and Enter stores it.
    tui.press(b"fr");
    tui.wait_for(WAIT, |text| {
        text.contains("fr  french") && !text.contains("de  german")
    });
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["defaultLanguage"],
        json!("fr")
    );
    write.answer(
        "200 OK",
        &stored(SETTINGS, json!({ "defaultLanguage": "fr" })),
    );
    tui.wait_for(WAIT, |text| value(text, "Language") == "fr");
}
