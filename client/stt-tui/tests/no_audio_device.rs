//! Roadmap item 67, playback's edge line: without an audio device, play says so and the TUI stays
//! usable. `STT_AUDIO_SINK=absent` opens no device and fails as a machine without one does - a pty
//! test cannot take a sound card away, and whether one is there differs from machine to machine.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript_on};

const CLIP: &[u8] = include_bytes!("fixtures/playback.flac");
const WAIT: Duration = Duration::from_secs(20);
const NO_DEVICE: &str = "cannot play the audio: no audio device";

#[test]
fn without_an_audio_device_play_says_so_and_the_tui_stays_usable() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_on("absent");

    // Space reads the clip, and the transcript says there is nothing to play it on.
    tui.press(b" ");
    service
        .next_get_from(&tui, WAIT)
        .answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| text.contains(NO_DEVICE));

    // The keys still act: `b` returns to History, whose row of that task says it too.
    tui.press(b"b");
    tui.wait_for(WAIT, |text| {
        text.contains("/ Search") && text.contains(NO_DEVICE)
    });

    // Space there tries again and still says so - it does not claim to play in silence.
    tui.press(b" ");
    std::thread::sleep(Duration::from_millis(500));
    let history = tui.text();
    assert!(
        history.contains(NO_DEVICE) && !history.contains("♪ playing"),
        "no device, no playing:\n{history}"
    );

    // And the list still moves.
    tui.press(b"j");
    tui.wait_for(WAIT, |text| text.contains("call.ogg"));
}
