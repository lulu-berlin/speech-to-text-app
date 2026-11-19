//! Roadmap item 67, discovered in the double-click cycle: the pty tests' list barrier
//! (`Tui::settle_in_list`) waits for the cursor to move and come back, reading the marked cells.
//! What they read must be the selection alone: a page that keeps changing under an open dialog -
//! a running task's elapsed clock, the stenographer - would otherwise never read as it did before.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui, WIDE};

/// lecture.m4a is running: its row shows the elapsed time, and the stenographer types.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_marked_cells_are_the_selection_alone_over_a_running_task() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    for name in ["a.mp3", "b.mp3"] {
        fs::write(files.path().join(name), b"RIFF-FAKE-WAVE").unwrap();
    }
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        WIDE,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Transcribing"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));

    // Down to a.mp3, below `../`: the cursor's row is the one marked.
    tui.press(b"\x1b[B");
    tui.wait_for_highlight(WAIT, "a.mp3");
    let marked = tui.marked_text();
    assert!(
        !marked.contains("Transcribing") && !marked.contains("Speech-to-Text"),
        "the page behind the dialog is not marked: {marked:?}"
    );
    assert!(!marked.contains("b.mp3"), "{marked:?}");
}
