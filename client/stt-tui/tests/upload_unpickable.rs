//! Roadmap item 67, parity amendment: a file the service would refuse by extension is greyed out in
//! the dialog - no mark to tick, no size or date - and the keyboard steps over it, so it can never be
//! marked or picked from the list. A typed path to one still says why it cannot be sent (upload.rs).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_non_audio_file_is_greyed_out_and_the_keyboard_steps_over_it() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("notes.txt"), b"not audio").unwrap();
    fs::write(files.path().join("talk.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("notes.txt") && text.contains("(not audio)")
    });
    assert!(
        screen.contains("talk.mp3"),
        "the audio file is listed too:\n{screen}"
    );

    // The non-audio row: one pair of parentheses, no mark to tick, and no size or date after it.
    let notes = screen
        .lines()
        .find(|line| line.contains("notes.txt"))
        .unwrap_or_default();
    assert!(
        notes.contains("notes.txt (not audio)"),
        "one set of parentheses:\n{notes}"
    );
    assert!(!notes.contains('['), "no mark to tick:\n{notes}");
    let after = notes
        .split("(not audio)")
        .nth(1)
        .and_then(|rest| rest.split('│').next())
        .unwrap_or("");
    assert!(
        after.trim().is_empty(),
        "no size or date on the row:\n{notes}"
    );

    // Down from `../` steps over notes.txt (files sort by name) and lands on the recording; Enter
    // uploads it, and nothing else was sent.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload.request().body.contains(r#"filename="talk.mp3""#),
        "{}",
        upload.request().body
    );
}
