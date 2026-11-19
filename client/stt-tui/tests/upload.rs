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
    fs::write(files.path().join("notes.txt"), b"not audio").unwrap();
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
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    let path = files.path().join("podcast.mp3");
    let path = path.to_str().unwrap();
    tui.press(path.as_bytes());
    // The typed path's frame: its end, the file name, is in view however long the path is.
    tui.wait_for(WAIT, |text| text.contains("podcast.mp3"));
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
    // Completed, it is no longer a queue row - the queue lists QUEUED, RUNNING and PAUSED tasks
    // only - even before the service's queue update drops its id.
    stream.send(
        "task-updated",
        r#"{"id":9,"filename":"podcast.mp3","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    let screen = tui.wait_for(WAIT, |text| queue_row(text, "podcast.mp3").is_empty());
    assert!(
        screen.contains("memo.wav"),
        "a completed task leaves the queue:\n{screen}"
    );
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7]}"#);

    // A path that does not exist: reported inside the dialog, which stays open; no request.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"/nowhere/missing.mp3\r");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("/nowhere/missing.mp3 does not exist")
    });
    assert!(
        !service.upload_arrived(),
        "a missing path is not sent:\n{screen}"
    );
    tui.press(b"\x1b");
    // The UI stays usable: the queue is back once the dialog is gone.
    tui.wait_for(WAIT, |text| {
        !text.contains("Upload audio") && text.contains("memo.wav")
    });

    // An unsupported format: refused before any request.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    let notes = files.path().join("notes.txt");
    tui.press(format!("{}\r", notes.display()).as_bytes());
    let screen = tui.wait_for(WAIT, |text| text.contains("not an audio or video file"));
    assert!(
        !service.upload_arrived(),
        "an unsupported format is not sent:\n{screen}"
    );
    assert!(
        screen.contains("Upload audio") && screen.contains("Folder"),
        "the dialog stays usable:\n{screen}"
    );
}

#[test]
fn escape_cancels_the_upload_prompt_without_quitting() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let queue = tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // A typed path, then Escape: the prompt closes, nothing is sent, the queue is as it was.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"/tmp/memo.wav");
    tui.wait_for(WAIT, |text| text.contains("/tmp/memo.wav"));
    tui.press(b"\x1b");
    let rows = |text: &str| {
        text.lines()
            .filter(|line| line.contains("memo.wav") || line.contains("lecture.m4a"))
            .count()
    };
    // Waited for until both rows are drawn again: a frame caught mid-repaint lacks the dialog too.
    let screen = tui.wait_for(WAIT, |text| {
        !text.contains("Upload audio") && text.contains("memo.wav") && text.contains("lecture.m4a")
    });
    assert_eq!(
        rows(&queue),
        rows(&screen),
        "the queue is unchanged:\n{screen}"
    );
    assert!(
        !service.upload_arrived(),
        "a cancelled prompt sends nothing:\n{screen}"
    );
    assert!(
        tui.exit_within(Duration::from_millis(200)).is_none(),
        "Escape at the queue cancels the prompt, it does not quit:\n{screen}"
    );

    // Still usable: the prompt opens again and `q` quits.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    tui.press(b"q");
    assert!(
        tui.exit_within(WAIT).is_some(),
        "`q` at the queue still quits"
    );
}

#[test]
fn a_refused_upload_shows_the_service_message_and_leaves_the_ui_usable() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("podcast.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    fs::write(files.path().join("big.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let queue = tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    let rows = |text: &str| text.lines().filter(|line| line.contains('%')).count();

    // A duplicate of an existing task: the offer to open it (cycle 159), no new row, the queue as
    // it was.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    let path = files.path().join("podcast.mp3");
    tui.press(path.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    service.next_upload(WAIT).answer(
        "409 Conflict",
        include_str!("../../../docs/api/error.duplicate-audio.json"),
    );
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("podcast.mp3 already uploaded — Enter open")
    });
    assert_eq!(
        rows(&queue),
        rows(&screen),
        "no task row is added:\n{screen}"
    );

    // An oversized file: the service's message too, and the prompt still works.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    let path = files.path().join("big.mp3");
    tui.press(path.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    service.next_upload(WAIT).answer(
        "413 Content Too Large",
        include_str!("../../../docs/api/error.upload-too-large.json"),
    );
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("The upload exceeds the maximum size of 1 GiB")
    });
    assert_eq!(
        rows(&queue),
        rows(&screen),
        "no task row is added:\n{screen}"
    );
    // The prompt still works after the refusal: it opens again, and Escape closes it. The dialog
    // closes when Enter submits, so an Escape here without reopening would quit the TUI (the pull
    // request's macOS run, 2026-11-19: the write then hit a closed pty).
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    tui.press(b"q");
    assert!(
        tui.exit_within(WAIT).is_some(),
        "the TUI still quits normally:\n{screen}"
    );
}
