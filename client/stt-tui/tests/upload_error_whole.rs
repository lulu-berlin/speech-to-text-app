//! Roadmap item 67 (discovered, the run 43802779592 on macOS 2026-11-16): a typed path's error is
//! shown whole inside the upload dialog - the dialog widens for it while the terminal has room, and
//! a message wider than the terminal keeps its end, what went wrong, in view.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// Whether a line holds `message` inside the dialog: a border after it on the same row.
fn inside_the_dialog(text: &str, message: &str) -> bool {
    text.lines().any(|line| {
        line.find(message)
            .is_some_and(|at| line[at + message.len()..].contains('│'))
    })
}

#[test]
fn a_typed_paths_error_is_shown_whole_inside_the_dialog() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));

    // A message the terminal has room for: the whole of it, inside the dialog.
    let missing = format!("/{}missing.wav", "a-folder-that-is-not-there/".repeat(3));
    tui.press(missing.as_bytes());
    tui.wait_for(WAIT, |text| text.contains("missing.wav"));
    tui.press(b"\r");
    let whole = format!("{missing} does not exist");
    tui.wait_for(WAIT, |text| inside_the_dialog(text, &whole));

    // A message wider than the terminal: its end, what went wrong, inside the dialog.
    let longer = format!("/{}gone.wav", "a-folder-that-is-not-there/".repeat(6));
    tui.press(longer.as_bytes());
    tui.wait_for(WAIT, |text| text.contains("gone.wav"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        inside_the_dialog(text, "a-folder-that-is-not-there/gone.wav does not exist")
    });
}
