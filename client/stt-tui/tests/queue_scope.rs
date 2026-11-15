//! Roadmap item 67, third amendment: the queue lists QUEUED, RUNNING and PAUSED tasks only; FAILED
//! and CANCELED ones are in History, where they are retried, as in the browser.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The queue row that shows `name` with a percentage (a notice line above the rows has none), or
/// an empty one.
fn row<'a>(text: &'a str, name: &str) -> &'a str {
    text.lines()
        .find(|line| line.contains(name) && line.contains('%'))
        .unwrap_or("")
}

/// `snapshot.json`'s task `id` with `status`.
fn task(id: i64, status: &str) -> String {
    let snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    let mut task = snapshot["tasks"]
        .as_array()
        .unwrap()
        .iter()
        .find(|task| task["id"] == id)
        .unwrap()
        .clone();
    task["status"] = json!(status);
    task.to_string()
}

#[test]
fn failed_and_cancelled_tasks_leave_the_queue_for_history_where_they_are_retried() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    // The snapshot's failed call.ogg is not a queue row.
    let queue = tui.wait_for(WAIT, |text| {
        text.contains("lecture.m4a") && text.contains("memo.wav")
    });
    assert!(
        !queue.contains("call.ogg"),
        "a failed task is not in the queue:\n{queue}"
    );

    // lecture.m4a is cancelled elsewhere: it leaves the queue.
    stream.send("task-updated", &task(5, "CANCELED"));
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7]}"#);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && row(text, "lecture.m4a").is_empty()
    });

    // History lists the failed call.ogg beside the completed interview.mp3.
    tui.press(b"H");
    let history = tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("call.ogg")
    });
    assert!(
        history
            .lines()
            .any(|line| line.contains("call.ogg") && line.contains("FAILED")),
        "History shows the failed task's status:\n{history}"
    );

    // `r` on call.ogg retries it.
    tui.press(b"j");
    tui.press(b"r");
    let retry = service.next_command(WAIT);
    assert!(
        retry.request().line.starts_with("POST /api/tasks/4/retry "),
        "{:?}",
        retry.request().line
    );
}
