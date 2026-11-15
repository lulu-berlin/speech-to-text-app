//! Roadmap item 67: `Space` on a History row plays that task from the start without opening it, the
//! row shows it is playing, and `Space` again pauses it. The frozen sink keeps the playhead still,
//! so nothing here reads a clock.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, offers, open_transcript_on};

const CLIP: &[u8] = include_bytes!("fixtures/playback.flac");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn space_on_a_history_row_plays_that_task_and_space_again_pauses_it() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_on("frozen");

    // Back on History (its search line, which the transcript has not), on interview.mp3's row: the
    // footer offers Space.
    tui.press(b"b");
    tui.wait_for(WAIT, |text| {
        text.contains("/ Search") && offers(text, &["Space play"], &[])
    });

    // Space reads the row's clip and plays it; the row says so, and the queue is not paused.
    tui.press(b" ");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/3/audio "),
        "{:?}",
        audio.request().line
    );
    audio.answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| text.contains("♪ playing"));
    assert!(
        !service.command_arrived(),
        "Space on History plays, it does not pause the queue"
    );

    // Space again pauses it.
    tui.press(b" ");
    tui.wait_for(WAIT, |text| !text.contains("♪ playing"));
    assert!(!service.command_arrived(), "nothing is sent");
}
