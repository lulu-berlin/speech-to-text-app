//! Roadmap item 67: the playing segment. While a clip plays, the segment the playhead is in is
//! filled in the accent role - its words in accentText - as the browser fills its current segment;
//! the cursor walks with the playhead until a key moves it; `[`/`]` seek five seconds back or on;
//! Enter on a segment seeks to it while playing, and plays from it while stopped. The sink is
//! frozen (`STT_AUDIO_SINK=frozen`): only a seek moves the playhead, so nothing here reads a clock.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript_playing, style};
use tokens::token;

/// Twenty seconds of silence at 16 kHz, mono: long enough for the five-second seeks.
const CLIP: &[u8] = include_bytes!("fixtures/playback-20s.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_playing_segment_is_filled_and_the_cursor_walks_with_it() {
    // The clip plays; the frozen sink leaves the playhead at its start.
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_playing("frozen", CLIP);

    let accent = token(&["colors", "accent"], "dark");
    let accent_text = token(&["colors", "accentText"], "dark");
    let tint = token(&["colors", "accentTint"], "dark");

    // `]` seeks five seconds on, and the segment the playhead reached - "Thanks for joining us
    // today." (2.4 s) - is filled as the browser fills its current segment.
    tui.press(b"]");
    tui.wait_for(WAIT, |text| text.contains("0:05 / 19:45"));
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("0:05 / 19:45"));
    let row = scene.row_of("Thanks for joining us today.");
    assert_eq!(
        style(&scene, row, "Thanks for joining us today."),
        (accent_text, accent),
        "the playing segment is filled in the accent role:\n{}",
        scene.text()
    );

    // The cursor walks with the playhead: a second `]` takes it to "Let's start with your
    // background." (9.6 s), and Enter acts on the cursor, so it plays from there - 0:09.
    tui.press(b"]");
    tui.wait_for(WAIT, |text| text.contains("0:10 / 19:45"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("0:09 / 19:45") && text.contains('⏸')
    });
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("0:09 / 19:45"));
    let row = scene.row_of("Let's start with your background.");
    assert_eq!(
        style(&scene, row, "Let's start with your background."),
        (accent_text, accent),
        "the reached segment is filled:\n{}",
        scene.text()
    );

    // A key that moves the cursor stops the walking: Space pauses and two `k`s put the cursor on
    // "Thanks for joining us today.", which takes the selection tint while the playing segment
    // keeps its fill; Enter then plays from the cursor's own segment (2.4 s), not the playhead's.
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains('▶'));
    tui.press(b"k");
    tui.press(b"k");
    tui.wait_for_cell(WAIT, "Thanks for joining us today.", |cell| {
        cell.bgcolor() == tint
    });
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains("Let's start with your background.")
    });
    let playing_row = scene.row_of("Let's start with your background.");
    assert_eq!(
        style(&scene, playing_row, "Let's start with your background."),
        (accent_text, accent),
        "the fill stays where the playhead is:\n{}",
        scene.text()
    );
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("0:02 / 19:45") && text.contains('⏸')
    });
}
