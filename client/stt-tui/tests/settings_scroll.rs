//! Roadmap item 67, third amendment (discovered in cycle 143): a click hits the settings row drawn
//! under it after the list has scrolled to keep the selected row in view.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS, value};
use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_click_hits_the_settings_row_after_the_list_scrolls() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    // A short terminal: the list scrolls to keep the selected row in view.
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 14);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // Only the first task fits under the upload box, the header and the rule.
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| value(text, "Threads") == "4");

    // Down to the restore row: the list scrolls, and the last rows show.
    for _ in 0..7 {
        tui.press(b"\x1b[B");
    }
    let screen = tui.wait_for(WAIT, |text| text.contains("Restore default settings"));

    // A click on the Terminal command row - drawn under the scrolled list - selects it: Enter
    // edits the command in its row, not a row counted from the top.
    let (_, row) = position_of(&screen, "Terminal command");
    tui.click(25, row);
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("Enter save · Esc cancel · empty:")
    });
}
