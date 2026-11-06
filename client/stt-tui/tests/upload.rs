//! Roadmap item 67: upload by path. `u` opens a path prompt; Enter sends the file to the service as
//! a multipart `POST /api/tasks`, and the task it creates shows up in the live queue and reaches
//! COMPLETED (leaving the queue, as the service's `queue-updated` says). A path that does not exist
//! or a file of an unsupported format is reported, with no request, and the TUI stays usable.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

/// The contract's `snapshot`: tasks 5 (`lecture.m4a`, RUNNING at 0.42) and 7 (`memo.wav`, QUEUED).
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

const WAIT: Duration = Duration::from_secs(20);

/// The screen line that shows `name`, or an empty one.
fn row<'a>(text: &'a str, name: &str) -> &'a str {
    text.lines().find(|line| line.contains(name)).unwrap_or("")
}

/// The queue row of `name` (the one with a percentage), or an empty one - the notice can name the
/// file too.
fn queue_row<'a>(text: &'a str, name: &str) -> &'a str {
    text.lines()
        .find(|line| line.contains(name) && line.contains('%'))
        .unwrap_or("")
}

#[test]
fn uploading_by_path_queues_the_file_and_reports_a_bad_path_or_format() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("podcast.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| row(text, "memo.wav").contains("QUEUED"));

    // `u` asks for a path; Enter sends the file.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    let path = files.path().join("podcast.mp3");
    let path = path.to_str().unwrap();
    tui.press(path.as_bytes());
    tui.wait_for(WAIT, |text| text.contains(path));
    tui.press(b"\r");

    let upload = service.next_upload(WAIT);
    let request = upload.request();
    assert!(
        request.line.starts_with("POST /api/tasks "),
        "{:?}",
        request.line
    );
    assert_eq!(
        request.header("Authorization"),
        Some("Bearer the-install-secret"),
        "{:?}",
        request.head
    );
    assert!(
        request
            .header("Content-Type")
            .is_some_and(|value| value.starts_with("multipart/form-data; boundary=")),
        "{:?}",
        request.head
    );
    assert!(
        request
            .body
            .contains(r#"name="file"; filename="podcast.mp3""#),
        "{}",
        request.body
    );
    assert!(request.body.contains("RIFF-FAKE-WAVE"), "{}", request.body);
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"podcast.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );

    // The service's events put it in the queue, run it to COMPLETED, then drop it from the queue.
    stream.send(
        "task-updated",
        r#"{"id":9,"filename":"podcast.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7,9]}"#);
    let screen = tui.wait_for(WAIT, |text| {
        let podcast = row(text, "podcast.mp3");
        podcast.contains("QUEUED") && podcast.contains("0%")
    });
    assert!(
        screen.contains("memo.wav"),
        "the queue keeps its other tasks:\n{screen}"
    );

    stream.send(
        "task-updated",
        r#"{"id":9,"filename":"podcast.mp3","status":"RUNNING","transcriptionProgress":0.0}"#,
    );
    stream.send(
        "task-progress",
        r#"{"id":9,"transcriptionProgress":0.5,"diarizationStage":null,"diarizationProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        let podcast = row(text, "podcast.mp3");
        podcast.contains("RUNNING") && podcast.contains("50%")
    });
    stream.send(
        "task-updated",
        r#"{"id":9,"filename":"podcast.mp3","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        let podcast = row(text, "podcast.mp3");
        podcast.contains("COMPLETED") && podcast.contains("100%")
    });
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7]}"#);
    let screen = tui.wait_for(WAIT, |text| queue_row(text, "podcast.mp3").is_empty());
    assert!(
        screen.contains("memo.wav"),
        "a completed task leaves the queue:\n{screen}"
    );

    // A path that does not exist: reported, no request, the queue still there.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    tui.press(b"/nowhere/missing.mp3\r");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("/nowhere/missing.mp3") && text.contains("upload failed")
    });
    assert!(
        !service.upload_arrived(),
        "a missing path is not sent:\n{screen}"
    );
    assert!(
        screen.contains("memo.wav"),
        "the UI stays usable:\n{screen}"
    );

    // An unsupported format: refused before any request.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    tui.press(b"notes.txt\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("not an audio or video file"));
    assert!(
        !service.upload_arrived(),
        "an unsupported format is not sent:\n{screen}"
    );
    assert!(
        screen.contains("memo.wav"),
        "the UI stays usable:\n{screen}"
    );
}
