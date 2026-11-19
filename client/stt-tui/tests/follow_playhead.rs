//! Roadmap item 67: following the playhead. While a clip plays and the cursor walks with the
//! playhead the transcript's footer says "Following playback"; a key that moves the cursor leaves
//! the playhead behind while the footer offers `f`, and `f` returns the cursor to the playing
//! segment and following resumes. The sink is frozen (`STT_AUDIO_SINK=frozen`): only a seek moves
//! the playhead, so nothing here reads a clock.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript_playing};

/// Twenty seconds of silence at 16 kHz, mono: long enough for a five-second seek.
const CLIP: &[u8] = include_bytes!("fixtures/playback-20s.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_moved_cursor_returns_to_the_playing_segment_with_f_and_following_resumes() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_playing("frozen", CLIP);

    // Following from the start: the cursor walks with the playhead, and the footer says so.
    let screen = tui.wait_for(WAIT, |text| text.contains("Following playback"));
    assert!(screen.contains("Following playback"), "{screen}");
    tui.press(b"]");
    tui.wait_for(WAIT, |text| text.contains("0:05 / 19:45"));

    // A key that moves the cursor stops the walking: the footer offers the way back instead.
    tui.press(b"\x1b[B");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("follow playback") && !text.contains("Following playback")
    });
    assert!(screen.contains("follow playback"), "{screen}");

    // `f` returns the cursor to the playing segment and following resumes.
    tui.press(b"f");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Following playback") && !text.contains("follow playback")
    });
    assert!(screen.contains("Following playback"), "{screen}");

    // The cursor is on the playing segment (2.4 s), not where it had been moved (5.2 s): Enter
    // seeks to the cursor's own segment.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("0:02 / 19:45"));
}
