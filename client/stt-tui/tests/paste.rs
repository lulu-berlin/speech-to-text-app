//! Roadmap item 67, parity amendment: a pasted path opens the upload dialog - the terminal's
//! drag-and-drop. Shell quoting and `file://` URIs are understood, several paths are accepted one
//! after another, and text that is not a path is ignored.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// Pastes `text` as a terminal sends a bracketed paste.
fn paste(tui: &mut Tui, text: &str) {
    tui.press(b"\x1b[200~");
    tui.press(text.as_bytes());
    tui.press(b"\x1b[201~");
}

#[test]
fn a_pasted_path_opens_the_upload_dialog_and_text_that_is_not_a_path_is_ignored() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    let first = files.path().join("a b.wav");
    let second = files.path().join("c.ogg");
    fs::write(&first, b"RIFF-FAKE-WAVE").unwrap();
    fs::write(&second, b"OGG-FAKE").unwrap();
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // A shell-quoted path with a space and a `file://` URI: both are accepted, the first opens the
    // dialog.
    paste(
        &mut tui,
        &format!("\"{}\" file://{}", first.display(), second.display()),
    );
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a b.wav")
    });

    // Enter uploads the first; the second path follows in the dialog.
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload
            .request()
            .body
            .contains(r#"name="file"; filename="a b.wav""#),
        "{}",
        upload.request().body
    );
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"a b.wav","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("c.ogg")
    });
    // Escape is read alone only once its effect shows: a paste sent before that can arrive in the
    // same read and be parsed together with it.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));

    // Text that names no existing file opens nothing.
    paste(&mut tui, "hello there, this is not a path");
    tui.settle();
    assert!(
        !tui.text().contains("Upload audio"),
        "text that is not a path is ignored:\n{}",
        tui.text()
    );
}
