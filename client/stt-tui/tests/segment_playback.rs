//! Roadmap item 67: `p` plays the cursor's segment alone, as the browser's Play-segment control
//! (item 52): from the segment's start, stopping at its end; `p` again while it plays stops it back
//! at the segment's start. The frozen sink keeps the playhead where it is put, so nothing here
//! reads a clock; the stop at the segment's end is the player's own test (`player.rs`).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, offers, open_transcript_on};

/// Twenty seconds of silence: long enough for every segment of `turns.json`.
const CLIP: &[u8] = include_bytes!("fixtures/playback-20s.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn p_plays_the_cursors_segment_alone_and_p_again_stops_it() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_on("frozen");

    // The second segment, "Thanks for joining us today." (2.4 s to 5.2 s): the footer offers `p`.
    tui.press(b"l");
    tui.wait_for(WAIT, |text| offers(text, &["play segment"], &[]));

    // `p` reads the clip and plays it from the segment's start.
    tui.press(b"p");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/3/audio "),
        "{:?}",
        audio.request().line
    );
    audio.answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| {
        text.contains("0:02 / 19:45") && text.contains('⏸')
    });

    // `p` again stops it, at the segment's start.
    tui.press(b"p");
    tui.wait_for(WAIT, |text| {
        text.contains("0:02 / 19:45") && text.contains('▶')
    });
}
