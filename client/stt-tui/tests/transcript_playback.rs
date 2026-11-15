//! Roadmap item 67: a transcript's audio. `Space` plays the task's clip from the playhead and
//! pauses it; the player bar shows the state, the playhead over the clip's length and the progress.
//! `STT_AUDIO_SINK=null` plays silently in real time - a pty test has no sound card, and the journey
//! gets its silent real-time output the same way.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, OpenTranscript, Tui, open_transcript_playing};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
/// Two seconds of silence at 16 kHz, mono: what the service's `/audio` answers in this test.
const CLIP: &[u8] = include_bytes!("fixtures/playback.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn space_plays_the_transcript_and_pauses_it_with_the_player_bar_shown() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_colours(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        30,
        &[("STT_AUDIO_SINK", "null")],
        None,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    let turns = service.next_get_from(&tui, WAIT);
    assert!(
        turns.request().line.starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        turns.request().line
    );
    turns.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));

    // Space reads the clip: the service's FLAC, with the credential.
    tui.press(b" ");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/3/audio "),
        "{:?}",
        audio.request().line
    );
    assert_eq!(
        audio.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    audio.answer_bytes("200 OK", CLIP);

    // The bar: playing, the playhead at the start, the task's length, and a line of progress.
    let playing = tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('⏸')
    });
    assert!(
        playing.contains('━') || playing.contains('─'),
        "the progress line:\n{playing}"
    );

    // The playhead follows the audio: a second in, the bar reads it.
    tui.wait_for(WAIT, |text| text.contains("0:01 / 19:45"));

    // Space pauses: the state changes and the playhead stands still.
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains('▶'));
    std::thread::sleep(Duration::from_millis(1200));
    let paused = tui.text();
    assert!(
        paused.contains("0:01 / 19:45"),
        "a paused playhead stands still:\n{paused}"
    );
}

/// The user's report (2026-11-12): opening another tab stops what plays, and the transcript, opened
/// again, finds its clip stopped where it stood - Space plays it on from there. The frozen sink keeps
/// the playhead still, so nothing here reads a clock.
#[test]
fn opening_another_tab_stops_the_playback() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_playing("frozen", CLIP);

    // The queue's tab: the transcript's playback stops.
    tui.press(b"Q");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);

    // Back in the transcript the clip stands stopped, and Space plays it on without reading it again.
    tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('▶')
    });
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains('⏸'));
    assert!(!service.get_arrived(), "the clip is read once and kept");
}
