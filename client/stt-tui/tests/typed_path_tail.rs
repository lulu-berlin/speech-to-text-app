//! Roadmap item 67 (discovered, the diagnostic run on macOS 2026-11-16): a typed path longer than
//! its field keeps its end - where the cursor is - in view, in the upload and the download
//! dialogs, so what the user types last is never cut off at the dialog's edge.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, screen_text};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// A path far wider than an 80-column terminal, ending in `end`.
fn long_path(end: &str) -> String {
    format!("/{}{end}", "a-deeply-nested-folder/".repeat(8))
}

/// Whether `end` is drawn inside a dialog's box - a border right of it on its row - with the
/// cursor on the cell right after it.
fn end_in_view(screen: &vt100::Screen, end: &str) -> bool {
    let (row, column) = screen.cursor_position();
    let contents = screen_text(screen);
    contents.lines().enumerate().any(|(index, line)| {
        let Some(byte) = line.find(end) else {
            return false;
        };
        let after = byte + end.len();
        index == row as usize
            && line[..after].chars().count() == column as usize
            && line[after..].contains('│')
    })
}

#[test]
fn a_typed_path_longer_than_its_field_keeps_its_end_in_view() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), state.path(), 80, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The upload dialog's typed path.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(long_path("upload-end.mp3").as_bytes());
    tui.wait_for_screen(WAIT, |screen| end_in_view(screen, "upload-end.mp3"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));

    // The download dialog's typed target.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Speakers"));
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Download"));
    tui.press(long_path("download-end.srt").as_bytes());
    tui.wait_for_screen(WAIT, |screen| end_in_view(screen, "download-end.srt"));
}
