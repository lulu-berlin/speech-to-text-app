//! Roadmap item 67, third amendment (discovered after cycle 135): a click hits the queue row drawn
//! under it after the table has scrolled to keep the selected row in view.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_click_hits_the_queue_row_after_the_table_scrolls() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    // A short terminal: the table scrolls to keep the selected row in view.
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 13);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    // Down to memo.wav: the table scrolls, and memo's row shows.
    tui.press(b"\x1b[B");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && !text.contains("lecture.m4a")
    });

    // A click on memo.wav's row - drawn under the scrolled table - selects it: `d` offers memo.
    let (_, row) = position_of(&screen, "memo.wav");
    tui.click(3, row);
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
}
