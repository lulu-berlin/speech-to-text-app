//! Roadmap item 67, the review (discovered in R10): a click on a footer hint acts in the open
//! confirmation and download dialogs as its key does, and the click itself never closes a dialog.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, open_transcript, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_download_dialogs_footer_hint_acts_as_its_key() {
    let open = open_transcript();
    let mut tui = open.tui;
    tui.press(b"d");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("Format:")
    });
    assert!(!screen.contains("> Format:"), "{screen}");

    // The footer's Tab hint moves the keyboard to the format, as Tab does - and the dialog stays.
    let (column, row) = position_of(&screen, "Tab format");
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| text.contains("> Format:"));
    assert!(screen.contains("Download"), "the dialog stays:\n{screen}");
}

#[test]
fn a_click_on_a_confirmations_own_keys_confirms_nothing() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // `c` on the running task asks first; the dialog names its keys itself, inside the frame.
    tui.press(b"c");
    let screen = tui.wait_for(WAIT, |text| text.contains("Cancel lecture.m4a?"));
    let (column, row) = position_of(&screen, "Enter Cancel task");
    tui.click(column, row);

    // The click confirmed nothing and left the confirmation open: Escape finds the dialog, closes
    // it, and the queue is back. Had the click closed the dialog, Escape would have quit the TUI
    // from the queue instead, and no queue would be drawn (the second review, S10). The service
    // was asked for nothing (the review's R10 line: a click never acts as its key in a
    // confirmation).
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        !text.contains("Cancel lecture.m4a?") && text.contains("memo.wav")
    });
    assert!(
        !service.command_arrived(),
        "a click on the confirmation's keys sends nothing"
    );
}
