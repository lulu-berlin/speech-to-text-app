//! Roadmap item 67: an upload the service recognizes as a file it already has is offered as the
//! existing task - a notice line above the queue - and Enter on that notice opens it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const DUPLICATE: &str = include_str!("../../../docs/api/error.duplicate-audio.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_duplicate_upload_offers_to_open_the_existing_task() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF-FAKE-A").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        folder.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The dialog lists `../` and a.mp3: Down selects a.mp3, Enter sends it.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    // The service already has the file: task 3, interview.mp3.
    upload.answer("409 Conflict", DUPLICATE);

    // The queue page says so and offers the existing task.
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("a.mp3 already uploaded — Enter open")
    });
    assert!(
        screen.contains("a.mp3 already uploaded — Enter open"),
        "{screen}"
    );

    // Down past the rows reaches the notice under them; Enter opens the existing task's
    // transcript.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let turns = service.next_get_from(&tui, WAIT);
    assert!(
        turns.request().line.starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        turns.request().line
    );
    turns.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Transcript: interview.mp3"));
}
