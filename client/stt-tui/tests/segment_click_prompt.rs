//! Roadmap item 67 (discovered in cycle 139): a click on a segment while a prompt holds the keyboard
//! leaves the cursor where it is, so a rename in progress still goes to the speaker it started on.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{open_transcript, position_of};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_click_during_a_rename_keeps_the_speaker_it_started_on() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    let screen = tui.wait_for(WAIT, |text| text.contains("Happy to be here."));

    // `r` on the first segment renames its speaker, Speaker 1.
    tui.press(b"r");
    tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 1"));

    // The cursor's segment, as the screen marks it, before the click.
    let before = tui.marked_text();
    assert!(
        before.contains("Good morning."),
        "the cursor is on Speaker 1's segment: {before:?}"
    );

    // A click on Speaker 2's segment while the prompt is open moves nothing: the cursor's segment
    // is the one it was, on frames drawn after the click (the review, T7).
    let (column, row) = position_of(&screen, "Happy");
    tui.click(column + 1, row);
    tui.press(b"x");
    tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 1x"));
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| !text.contains("Rename: Speaker 1x"));
    assert_eq!(
        tui.marked_text(),
        before,
        "the click left the cursor where it was"
    );

    // The name typed then goes to Speaker 1.
    tui.press(&[0x7f; "Speaker 1".len()]);
    tui.press(b"Alice\r");
    let rename = open.service.next_command(WAIT);
    assert!(
        rename
            .request()
            .line
            .starts_with("PUT /api/tasks/3/speakers/0 "),
        "the rename goes to the speaker it started on: {:?}",
        rename.request().line
    );
}
