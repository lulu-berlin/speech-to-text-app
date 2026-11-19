//! Roadmap item 67, the review (R20): a character made of several code points is one character to
//! erase - one Backspace takes a base letter together with its combining mark, leaving nothing of
//! it behind.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::path::Path;
use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, stored, value};
use terminal::{EventService, EventStream, Tui, history_row};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The TUI on its queue after the snapshot, working in `folder` when given; the state directory
/// and the event stream are kept for as long as the test holds the third value.
fn start_in(folder: Option<&Path>) -> (EventService, Tui, (tempfile::TempDir, EventStream)) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let program: &Path = env!("CARGO_BIN_EXE_stt-tui").as_ref();
    let tui = match folder {
        Some(folder) => Tui::start_in(program, state.path(), 120, 30, folder),
        None => Tui::start(program, state.path(), 120, 30),
    };
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    (service, tui, (state, stream))
}

/// The upload dialog's path row - the line above its `Upload N files` keys - inside the dialog's
/// borders, trimmed; empty when no dialog is open.
fn path_row(screen: &str) -> &str {
    let lines: Vec<&str> = screen.lines().collect();
    lines
        .iter()
        .position(|line| line.contains("│Upload "))
        .and_then(|keys| keys.checked_sub(1))
        .and_then(|row| lines[row].split('│').nth(1))
        .map(str::trim)
        .unwrap_or("")
}

#[test]
fn one_backspace_erases_a_character_of_several_code_points() {
    // The folder's name holds the letter typed below, as a random one can: only the path row
    // tells whether it was typed or erased (the second review, discovered with S4).
    let files = tempfile::Builder::new().prefix("z").tempdir().unwrap();
    std::fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    let (service, mut tui, _connection) = start_in(Some(files.path()));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));

    // A base letter and its combining acute, typed as two code points: the path row draws them as
    // one character.
    tui.press(b"z");
    tui.press("\u{301}".as_bytes());
    tui.wait_for(WAIT, |text| path_row(text).contains('z'));

    // One Backspace erases the whole character: neither the mark nor the letter is left behind.
    tui.press(b"\x7f");
    let screen = tui.wait_for(WAIT, |text| !path_row(text).contains('z'));
    assert_eq!(
        path_row(&screen),
        "/ type a path",
        "the row is the path hint again:\n{screen}"
    );
}

/// The second review (S4): History's search erases a character of several code points with one
/// Backspace too.
#[test]
fn one_backspace_erases_a_whole_character_from_historys_search() {
    let (_service, mut tui, _connection) = start_in(None);
    tui.press(b"H");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());

    // `inter` keeps interview.mp3; an `e` with a combining acute after it matches nothing.
    tui.press(b"/");
    tui.press(b"inter");
    tui.press("e\u{301}".as_bytes());
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_none());

    // One Backspace erases the whole `é`: the search is `inter` again, and interview.mp3 is back.
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());
}

/// The second review (S4): the terminal-command prompt on the Settings page erases a character of
/// several code points with one Backspace too.
#[test]
fn one_backspace_erases_a_whole_character_from_the_terminal_command() {
    let (service, mut tui, _connection) = start_in(None);
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer(
        "200 OK",
        &stored(SETTINGS, json!({ "terminalCommand": "foot" })),
    );
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| value(text, "Terminal command") == "foot");

    // The row is the last field's, five Downs from the first; Enter edits it.
    for _ in 0..5 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));

    // ` -e`, then an `e` with a combining acute; one Backspace erases the whole `é`.
    tui.press(b" -e");
    tui.press("e\u{301}".as_bytes());
    tui.press(b"\x7f");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["terminalCommand"],
        "foot -e"
    );
}

/// The second review (discovered with S4): the language dropdown's filter erases a character of
/// several code points with one Backspace too.
#[test]
fn one_backspace_erases_a_whole_character_from_the_language_filter() {
    let (service, mut tui, _connection) = start_in(None);
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("kitty -e"));

    // The language's dropdown, two Downs from the first row; `germ` keeps german.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    tui.press(b"germ");
    tui.wait_for(WAIT, |text| {
        text.contains("de  german") && !text.contains("en  english")
    });

    // An `e` with a combining acute after it matches nothing, so german leaves the list; one
    // Backspace erases the whole `é`, and german is back.
    tui.press("e\u{301}".as_bytes());
    tui.wait_for(WAIT, |text| !text.contains("de  german"));
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| text.contains("de  german"));
}
