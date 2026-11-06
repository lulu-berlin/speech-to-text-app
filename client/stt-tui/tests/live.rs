//! Roadmap item 67: the queue is live. The service's events change the view as they arrive, with
//! nothing refreshed by hand; when the stream drops, the TUI opens a new one (with the secret again)
//! and shows the state its fresh snapshot reports.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

/// The contract's `snapshot` event: tasks 5 (`lecture.m4a`, RUNNING at 0.42) and 7 (`memo.wav`,
/// QUEUED at 0.0) are the queue, in that order.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn events_update_the_queue_and_a_dropped_stream_reconnects_and_converges() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| row(text, "lecture.m4a").contains("42%"));

    // Progress of the running task.
    first.send(
        "task-progress",
        r#"{"id":5,"transcriptionProgress":0.75,"diarizationStage":null,"diarizationProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| row(text, "lecture.m4a").contains("75%"));

    // The running task completes and leaves the queue; the next one starts.
    first.send(
        "task-updated",
        r#"{"id":5,"filename":"lecture.m4a","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    first.send("queue-updated", r#"{"paused":false,"taskIds":[7]}"#);
    first.send(
        "task-updated",
        r#"{"id":7,"filename":"memo.wav","status":"RUNNING","transcriptionProgress":0.1}"#,
    );
    let screen = tui.wait_for(WAIT, |text| {
        let memo = row(text, "memo.wav");
        memo.contains("RUNNING") && memo.contains("10%")
    });
    assert!(
        !screen.contains("lecture.m4a"),
        "a completed task left the queue:\n{screen}"
    );

    // The stream drops; what happened meanwhile arrives in the next stream's snapshot.
    drop(first);
    let mut second = service.next_stream(WAIT);
    assert_eq!(
        second.authorization.as_deref(),
        Some("Bearer the-install-secret"),
        "the new stream is asked for with the secret too"
    );
    second.send(
        "snapshot",
        r#"{"tasks":[
            {"id":7,"filename":"memo.wav","status":"COMPLETED","transcriptionProgress":1.0},
            {"id":8,"filename":"podcast.mp3","status":"RUNNING","transcriptionProgress":0.3}
        ],"queue":{"paused":false,"taskIds":[8]}}"#,
    );
    let screen = tui.wait_for(WAIT, |text| {
        let podcast = row(text, "podcast.mp3");
        podcast.contains("RUNNING") && podcast.contains("30%")
    });
    assert!(
        !screen.contains("memo.wav"),
        "the fresh snapshot replaced the old queue:\n{screen}"
    );
}

/// The screen line that shows `name`, or an empty one.
fn row<'a>(text: &'a str, name: &str) -> &'a str {
    text.lines().find(|line| line.contains(name)).unwrap_or("")
}
