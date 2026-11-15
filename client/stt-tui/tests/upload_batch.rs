//! Roadmap item 67: several files can be marked in the upload dialog and uploaded one after
//! another with the options the dialog shows - Space marks the selected file, Enter sends the
//! marked ones.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn marked_files_upload_one_after_another() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF-FAKE-A").unwrap();
    fs::write(folder.path().join("b.wav"), b"RIFF-FAKE-B").unwrap();
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

    // The dialog lists `../`, a.mp3 and b.wav: Space marks a.mp3, Down and Space mark b.wav.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3") && text.contains("b.wav")
    });
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.press(b"\r");

    // a.mp3's bytes are all sent and its answer is still to come: its line shows the progress.
    tui.wait_for(WAIT, |text| text.contains("uploading a.mp3 100%"));

    // One after another: a.mp3 first, then b.wav.
    let first = service.next_upload(WAIT);
    assert!(
        first
            .request()
            .body
            .contains(r#"name="file"; filename="a.mp3""#),
        "{}",
        first.request().body
    );
    first.answer(
        "201 Created",
        r#"{"id":9,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    let second = service.next_upload(WAIT);
    assert!(
        second
            .request()
            .body
            .contains(r#"name="file"; filename="b.wav""#),
        "{}",
        second.request().body
    );
    second.answer(
        "201 Created",
        r#"{"id":10,"filename":"b.wav","status":"QUEUED","transcriptionProgress":0.0}"#,
    );

    // Each one reports itself and its line goes; the dialog closed with the first.
    let screen = tui.wait_for(WAIT, |text| text.contains("queued b.wav"));
    assert!(!screen.contains("Upload audio"), "{screen}");
    assert!(
        !screen.contains("uploading "),
        "the lines go with the files:\n{screen}"
    );
}
