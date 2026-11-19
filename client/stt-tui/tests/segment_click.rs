//! Roadmap item 67 (the user's report 2026-11-13: "clicking on a segment with the mouse should
//! select that segment"): a left click on a transcript's segment moves the segment cursor to it,
//! as the arrows would.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript, position_of};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_click_on_a_segment_moves_the_cursor_to_it() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    let screen = tui.wait_for(WAIT, |text| text.contains("Thanks for joining us today."));
    tui.wait_for_highlight(WAIT, "Good morning.");

    // A click inside the first turn's second segment - its word "joining".
    let (column, row) = position_of(&screen, "joining");
    tui.click(column + 2, row);
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");

    // A click on another turn's segment moves the cursor there.
    let (column, row) = position_of(&screen, "background");
    tui.click(column, row);
    tui.wait_for_highlight(WAIT, "Let's start with your background.");
}

/// Twenty seconds of silence, for the player's clip.
const CLIP: &[u8] = include_bytes!("fixtures/playback-20s.flac");

#[test]
fn a_double_click_on_a_segment_plays_it() {
    let OpenTranscript {
        service, mut tui, ..
    } = terminal::open_transcript_on("frozen");
    let screen = tui.wait_for(WAIT, |text| text.contains("Happy to be here."));

    // Two clicks on the second turn's segment: it plays alone, from its start (5 s).
    let (column, row) = position_of(&screen, "Happy");
    tui.click(column, row);
    tui.click(column, row);
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/3/audio "),
        "{:?}",
        audio.request().line
    );
    audio.answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| {
        text.contains("0:05 / 19:45") && text.contains('⏸')
    });
}
