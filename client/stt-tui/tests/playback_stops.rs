//! Roadmap item 67, playback's edge line: leaving the transcript stops what it plays, and playing
//! another task stops what was playing. The frozen sink keeps the playhead still, so nothing here
//! reads a clock.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript_playing};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const CLIP: &[u8] = include_bytes!("fixtures/playback.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn leaving_the_transcript_or_playing_another_task_stops_what_was_playing() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_playing("frozen", CLIP);

    // `b` back to History: interview.mp3's clip stopped with the transcript it played in.
    tui.press(b"b");
    let history = tui.wait_for(WAIT, |text| text.contains("/ Search"));
    assert!(
        !history.contains("♪ playing"),
        "leaving the transcript stops its playback:\n{history}"
    );

    // Space on its row plays it on, without reading it again.
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains("♪ playing"));
    assert!(!service.get_arrived(), "the clip is kept");

    // The next row, call.ogg: Space reads its clip, and interview.mp3 stops while it is read.
    tui.press(b"j");
    tui.press(b" ");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/4/audio "),
        "{:?}",
        audio.request().line
    );
    tui.wait_for(WAIT, |text| !text.contains("♪ playing"));
    audio.answer_bytes("200 OK", CLIP);
    let playing = tui.wait_for(WAIT, |text| text.contains("♪ playing"));
    let row = playing
        .lines()
        .find(|line| line.contains("♪ playing"))
        .unwrap_or_default();
    assert!(
        row.contains("call.ogg"),
        "the playing row is call.ogg's:\n{playing}"
    );

    // interview.mp3's transcript, opened while call.ogg plays: one Space stops call.ogg and reads
    // interview.mp3's own clip, which plays.
    tui.press(b"k");
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));
    tui.press(b" ");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/3/audio "),
        "{:?}",
        audio.request().line
    );
    audio.answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('⏸')
    });
}
