//! Roadmap item 67, parity amendment: the upload dialog. `u` opens a file browser in the current
//! directory - directories first, Enter enters one, Enter on a file uploads it - and Escape closes
//! it without a request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_upload_dialog_browses_and_uploads_the_chosen_file() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::create_dir(files.path().join("clips")).unwrap();
    fs::write(files.path().join("clips/talk.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    fs::write(files.path().join("notes.txt"), b"not audio").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Upload a file"));

    // `u`: the dialog, listing this directory - the subdirectory first.
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("clips/"));
    assert!(screen.contains("notes.txt"), "{screen}");
    assert!(
        screen.contains("Upload:"),
        "the dialog names its directory:\n{screen}"
    );

    // Enter enters the directory, then Enter on the recording uploads it.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("talk.mp3"));
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload.request().body.contains(r#"filename="talk.mp3""#),
        "{}",
        upload.request().body
    );
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"talk.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| !text.contains("Upload:"));

    // Escape closes it without a request.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Upload:"));
    assert!(
        !service.upload_arrived(),
        "a cancelled dialog sends nothing:\n{screen}"
    );
}
