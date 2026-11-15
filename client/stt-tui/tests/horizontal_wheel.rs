//! Roadmap item 67 (the user's report 2026-11-08): a two-finger horizontal scroll - which the
//! terminal reports as left/right scroll events - moves between the tabs as Left and Right do; on a
//! transcript it does not move the segment cursor.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// A horizontal scroll on the second row: SGR button 67 is right, 66 is left.
fn scroll(tui: &mut Tui, right: bool) {
    let button = if right { 67 } else { 66 };
    tui.press(format!("\x1b[<{button};10;2M").as_bytes());
}

#[test]
fn a_horizontal_scroll_moves_between_the_tabs_and_never_the_segment_cursor() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    // Right moves from the queue to History; left comes back to the queue.
    scroll(&mut tui, true);
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    scroll(&mut tui, false);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));

    // On a transcript the horizontal scroll changes the tab, not the segment cursor: the
    // transcript goes and the queue is open.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Transcript: interview.mp3"));
    scroll(&mut tui, false);
    tui.wait_for(WAIT, |text| {
        text.contains("Choose audio files") && !text.contains("Transcript: interview.mp3")
    });
}
