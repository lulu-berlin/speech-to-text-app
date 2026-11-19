//! Roadmap item 67, the review (R1): the TUI keeps running in a terminal narrower than 20 columns
//! or shorter than 6 rows. A click, a confirmation, the download dialog and "Move to" are drawn
//! there - clipped to what fits - and the program goes on: grown again, the terminal shows the
//! whole screen.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// Starts the TUI in a `cols` x `rows` terminal and goes through every place that draws a dialog
/// in the middle of the screen, a click among them; then grows the terminal and checks the TUI
/// still runs and draws the whole transcript.
fn every_dialog_at(cols: u16, rows: u16) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        cols,
        rows,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // The snapshot is in: the queue's rows (at 19 columns only their status shows), or - where
    // they do not fit - the header's count of the running task.
    tui.wait_for(WAIT, |text| {
        text.contains("QUEUED") || text.contains("transcribing 1 task")
    });

    // `c` on the running task asks first: the confirmation is drawn, clipped.
    tui.press(b"c");
    tui.wait_for(WAIT, |text| text.contains("Cancel"));
    // A click in the top-left corner, outside the confirmation in both terminals, closes it, as a
    // click outside a dialog does; nothing is canceled.
    tui.click(0, 0);
    tui.wait_for(WAIT, |text| !text.contains("Cancel"));

    // History's first row opens its transcript.
    tui.press(b"H");
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Speak"));

    // `d` opens the download dialog; Escape closes it.
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Download"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Download"));

    // `a` marks the cursor's segment and Enter opens "Move to"; Escape closes it.
    tui.press(b"a");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Move 1"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Move 1"));

    // Grown again, the TUI draws the whole transcript: the header's right end and the turns, which
    // neither small terminal has room for.
    tui.resize(120, 24);
    tui.wait_for(WAIT, |text| {
        text.contains("● Live") && text.contains("Good morning.")
    });
    assert!(
        tui.exit_within(Duration::ZERO).is_none(),
        "the TUI still runs"
    );
    assert!(
        !service.command_arrived(),
        "Escape on the confirmation canceled nothing"
    );
}

#[test]
fn a_terminal_narrower_than_20_columns_draws_every_dialog_and_takes_a_click() {
    every_dialog_at(19, 24);
}

#[test]
fn a_terminal_shorter_than_6_rows_draws_every_dialog_and_takes_a_click() {
    every_dialog_at(80, 5);
}
