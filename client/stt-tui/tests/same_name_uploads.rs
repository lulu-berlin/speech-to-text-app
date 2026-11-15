//! Roadmap item 67: two uploads with the same file name - a second one sent while the first is
//! still on its way - each keep their own progress line.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// How many lines above the queue report an upload of `name`.
fn uploading_lines(screen: &str, name: &str) -> usize {
    let uploading = format!("uploading {name}");
    screen
        .lines()
        .filter(|line| line.contains(&uploading))
        .count()
}

#[test]
fn a_second_upload_of_the_same_name_keeps_its_own_progress_line() {
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

    // a.mp3 sent alone and held unanswered: its line shows the progress.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let first = service.next_upload(WAIT);
    tui.wait_for(WAIT, |text| uploading_lines(text, "a.mp3") == 1);

    // The same file sent again while the first is unanswered: the dialog opens on the same list,
    // and Down and Enter send a.mp3 a second time.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let second = service.next_upload(WAIT);

    // Both are on their way and each keeps its own line, rather than the second replacing the
    // first's.
    let screen = tui.wait_for(WAIT, |text| uploading_lines(text, "a.mp3") == 2);
    assert!(
        uploading_lines(&screen, "a.mp3") == 2,
        "each upload keeps its own line:\n{screen}"
    );

    // Each answer takes only its own line.
    first.answer(
        "201 Created",
        r#"{"id":9,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    let screen = tui.wait_for(WAIT, |text| uploading_lines(text, "a.mp3") == 1);
    assert!(
        screen.contains("queued a.mp3"),
        "the first one's line goes:\n{screen}"
    );
    second.answer(
        "201 Created",
        r#"{"id":10,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    let screen = tui.wait_for(WAIT, |text| !text.contains("uploading "));
    assert!(
        !screen.contains("uploading "),
        "the second one's line goes too:\n{screen}"
    );
}
