//! Roadmap item 67: a file being uploaded shows its upload progress above the queue whichever way
//! it was sent - one file chosen with Enter as well as a marked batch.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn one_file_sent_with_enter_shows_its_upload_progress() {
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

    // The dialog lists `../` and a.mp3: Down selects a.mp3, Enter sends it alone.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");

    // Its bytes are all sent and its answer is still to come: its line shows the progress.
    let upload = service.next_upload(WAIT);
    tui.wait_for(WAIT, |text| text.contains("uploading a.mp3 100%"));

    // Answered, it reports itself and its line goes.
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    let screen = tui.wait_for(WAIT, |text| text.contains("queued a.mp3"));
    assert!(
        !screen.contains("uploading "),
        "the line goes with the file:\n{screen}"
    );
}
