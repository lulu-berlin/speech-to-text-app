//! Roadmap item 67 (the user's report 2026-11-08): a left click on a footer hint performs its key,
//! exactly as pressing it does; a hint naming several keys stays keyboard-only.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_click_on_a_footer_hint_performs_its_key_and_a_multi_key_hint_does_not() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 160, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    // A hint naming several keys stays keyboard-only: a click on "move" does nothing.
    let (column, row) = position_of(&screen, "move");
    tui.click(column, row);
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !service.command_arrived(),
        "a multi-key hint acts on no click"
    );

    // A hint naming one key performs it: a click on "pause" pauses the RUNNING task. The header's
    // "Space pause" comes first on the screen, so the footer's is the last row naming it.
    let lines: Vec<&str> = screen.lines().collect();
    let row = lines
        .iter()
        .rposition(|line| line.contains("pause"))
        .expect("the footer offers pause");
    let column = lines[row][..lines[row].find("pause").unwrap()]
        .chars()
        .count() as u16;
    let row = row as u16;
    tui.click(column + 1, row);
    let pause = service.next_command(WAIT);
    assert!(
        pause.request().line.starts_with("POST /api/tasks/5/pause "),
        "{:?}",
        pause.request().line
    );
}
