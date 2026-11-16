//! Roadmap item 67, second amendment: closing the upload dialog redraws the whole screen. A
//! terminal that draws a glyph wider than the TUI counted it (an ambiguous-width `·` or `—`) leaves
//! cells the TUI believes unchanged, so only a full repaint - the screen cleared, every row drawn
//! again - removes what the dialog left; after it the screen is the queue as it is drawn afresh.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
/// The escape that clears the whole screen.
const CLEAR: &str = "\x1b[2J";

/// The queue's rows and footer, the lines a closed dialog must leave whole.
fn queue_shown(text: &str) -> bool {
    text.contains("lecture.m4a") && text.contains("memo.wav") && !text.contains("Esc cancel")
}

/// The header's stenographer tile: its first three rows' first columns, which animate while a task
/// runs.
const TILE: (usize, usize) = (3, 11);

/// What comes before a running row's elapsed time.
const ELAPSED: &str = "… · ";

/// `text` without what moves while a task runs: the stenographer tile, which animates (the run
/// 43884436898 compared two of its frames), and the running row's elapsed time, which counts the
/// wall clock's seconds (the run 43904222789: `918:59:40` against `918:59:39`). On a slow runner
/// the two screens are more than a frame or a second apart.
fn steady(text: &str) -> String {
    text.lines()
        .enumerate()
        .map(|(row, line)| {
            let line: String = if row < TILE.0 {
                line.chars().skip(TILE.1).collect()
            } else {
                line.to_string()
            };
            match line.split_once(ELAPSED) {
                Some((status, rest)) => {
                    let after = rest.trim_start_matches(|c: char| !c.is_whitespace());
                    format!("{status}{ELAPSED}<elapsed>{after}")
                }
                None => line,
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn closing_the_upload_dialog_repaints_the_whole_screen() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    let audio = files.path().join("podcast.mp3");
    fs::write(&audio, b"RIFF-FAKE-WAVE").unwrap();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), state.path(), 80, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // The settled screen: the startup settings read answered too, so the header names the mode
    // (it appears when that answer lands, which would otherwise race the comparison below).
    let fresh = tui.wait_for(WAIT, |text| queue_shown(text) && text.contains("Theme:"));

    // Escape closes the dialog: the screen is cleared and the queue drawn again, as it was.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    let before = tui.raw().len();
    tui.press(b"\x1b");
    let after = tui.wait_for(WAIT, queue_shown);
    assert!(
        tui.raw()[before..].contains(CLEAR),
        "closing the dialog with Escape repaints the whole screen"
    );
    assert_eq!(
        steady(&after),
        steady(&fresh),
        "the queue is drawn as it was before the dialog"
    );

    // An upload closes the dialog too, with the same repaint. The queue's frame after Escape is
    // already awaited, so `u` cannot reach the TUI with the Escape as one Alt-u.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    tui.press(audio.to_str().unwrap().as_bytes());
    tui.wait_for(WAIT, |text| text.contains("podcast.mp3"));
    let before = tui.raw().len();
    tui.press(b"\r");
    service.next_upload(WAIT).answer(
        "201 Created",
        r#"{"id":9,"filename":"podcast.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, queue_shown);
    assert!(
        tui.raw()[before..].contains(CLEAR),
        "closing the dialog with an upload repaints the whole screen"
    );
}
