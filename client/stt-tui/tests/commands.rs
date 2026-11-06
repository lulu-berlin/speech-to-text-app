//! Roadmap item 67: the queue's commands from the TUI. `p` pauses or resumes the selected task,
//! `c` cancels it, `r` retries a failed or canceled one, `J`/`K` (and Shift-Down/Shift-Up) move it
//! in the queue, and Space pauses or resumes the whole queue - each the API call the browser makes,
//! against the one backend. A command the task's status does not allow sends nothing.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

/// The contract's `snapshot`: task 5 (`lecture.m4a`, RUNNING at 0.42) and task 7 (`memo.wav`,
/// QUEUED at 0.0), in that order.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

const WAIT: Duration = Duration::from_secs(20);

/// The screen line that shows `name` with a percentage (the queue row, not a notice), or an empty
/// one.
fn row<'a>(text: &'a str, name: &str) -> &'a str {
    text.lines()
        .find(|line| line.contains(name) && line.contains('%'))
        .unwrap_or("")
}

fn task(id: i64, filename: &str, status: &str, progress: f64) -> String {
    format!(
        r#"{{"id":{id},"filename":"{filename}","status":"{status}","transcriptionProgress":{progress}}}"#
    )
}

fn queue(ids: &[i64]) -> String {
    let ids: Vec<String> = ids.iter().map(i64::to_string).collect();
    format!(r#"{{"paused":false,"taskIds":[{}]}}"#, ids.join(","))
}

#[test]
fn pause_resume_cancel_retry_reorder_and_the_queue_pause_use_the_shared_api() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        row(text, "lecture.m4a").contains("RUNNING") && row(text, "memo.wav").contains("QUEUED")
    });

    // `p` on the selected RUNNING task pauses it.
    tui.press(b"p");
    let pause = service.next_command(WAIT);
    let request = pause.request();
    assert!(
        request.line.starts_with("POST /api/tasks/5/pause "),
        "{:?}",
        request.line
    );
    assert_eq!(
        request.header("Authorization"),
        Some("Bearer the-install-secret"),
        "{:?}",
        request.head
    );
    pause.answer("200 OK", &task(5, "lecture.m4a", "PAUSED", 0.42));
    stream.send("task-updated", &task(5, "lecture.m4a", "PAUSED", 0.42));
    stream.send("queue-updated", &queue(&[5, 7]));
    tui.wait_for(WAIT, |text| row(text, "lecture.m4a").contains("PAUSED"));

    // A second `p` resumes it.
    tui.press(b"p");
    let resume = service.next_command(WAIT);
    assert!(
        resume
            .request()
            .line
            .starts_with("POST /api/tasks/5/resume "),
        "{:?}",
        resume.request().line
    );
    resume.answer("200 OK", &task(5, "lecture.m4a", "RUNNING", 0.42));
    stream.send("task-updated", &task(5, "lecture.m4a", "RUNNING", 0.42));
    tui.wait_for(WAIT, |text| row(text, "lecture.m4a").contains("RUNNING"));

    // `c` cancels it: it leaves the queue but stays on screen, retryable.
    tui.press(b"c");
    let cancel = service.next_command(WAIT);
    assert!(
        cancel
            .request()
            .line
            .starts_with("POST /api/tasks/5/cancel "),
        "{:?}",
        cancel.request().line
    );
    cancel.answer("200 OK", &task(5, "lecture.m4a", "CANCELED", 0.42));
    stream.send("task-updated", &task(5, "lecture.m4a", "CANCELED", 0.42));
    stream.send("queue-updated", &queue(&[7]));
    let screen = tui.wait_for(WAIT, |text| {
        row(text, "lecture.m4a").contains("CANCELED") && row(text, "memo.wav").contains("QUEUED")
    });
    assert!(
        screen.find("memo.wav").unwrap_or(usize::MAX) < screen.find("lecture.m4a").unwrap_or(0),
        "the pending task comes before the canceled one:\n{screen}"
    );

    // `r` on the canceled task retries it: it rejoins the queue at the end.
    tui.press(b"\x1b[B"); // Down
    tui.press(b"r");
    let retry = service.next_command(WAIT);
    assert!(
        retry.request().line.starts_with("POST /api/tasks/5/retry "),
        "{:?}",
        retry.request().line
    );
    retry.answer("200 OK", &task(5, "lecture.m4a", "QUEUED", 0.0));
    stream.send("task-updated", &task(5, "lecture.m4a", "QUEUED", 0.0));
    stream.send("queue-updated", &queue(&[7, 5]));
    let screen = tui.wait_for(WAIT, |text| {
        row(text, "lecture.m4a").contains("QUEUED") && row(text, "memo.wav").contains("QUEUED")
    });
    assert!(
        screen.find("memo.wav").unwrap_or(0) < screen.find("lecture.m4a").unwrap_or(usize::MAX),
        "the retried task is at the end of the queue:\n{screen}"
    );

    // Shift-Up moves the selected (last) row up: the same order the browser's drag sends.
    tui.press(b"\x1b[1;2A");
    let reorder_up = service.next_command(WAIT);
    let request = reorder_up.request();
    assert!(
        request.line.starts_with("PUT /api/queue/order "),
        "{:?}",
        request.line
    );
    assert_eq!(request.body, r#"{"taskIds":[5,7]}"#, "{}", request.body);
    reorder_up.answer("200 OK", &queue(&[5, 7]));
    stream.send("queue-updated", &queue(&[5, 7]));
    let screen = tui.wait_for(WAIT, |text| {
        text.find("lecture.m4a").unwrap_or(0) < text.find("memo.wav").unwrap_or(usize::MAX)
    });
    assert!(screen.contains("lecture.m4a"), "{screen}");

    // `J` moves the selected row down: back to the queue's order.
    tui.press(b"\x1b[A"); // Up, onto the first row
    tui.press(b"J");
    let reorder_down = service.next_command(WAIT);
    let request = reorder_down.request();
    assert!(
        request.line.starts_with("PUT /api/queue/order "),
        "{:?}",
        request.line
    );
    assert_eq!(request.body, r#"{"taskIds":[7,5]}"#, "{}", request.body);
    reorder_down.answer("200 OK", &queue(&[7, 5]));
    stream.send("queue-updated", &queue(&[7, 5]));
    tui.wait_for(WAIT, |text| {
        text.find("memo.wav").unwrap_or(0) < text.find("lecture.m4a").unwrap_or(usize::MAX)
    });

    // Space pauses the whole queue, and pauses it back.
    tui.press(b" ");
    let pause_queue = service.next_command(WAIT);
    assert!(
        pause_queue
            .request()
            .line
            .starts_with("POST /api/queue/pause "),
        "{:?}",
        pause_queue.request().line
    );
    pause_queue.answer("200 OK", r#"{"paused":true,"taskIds":[7,5]}"#);
    stream.send("queue-updated", r#"{"paused":true,"taskIds":[7,5]}"#);
    tui.wait_for(WAIT, |text| text.contains("Queue (paused)"));
    tui.press(b" ");
    let resume_queue = service.next_command(WAIT);
    assert!(
        resume_queue
            .request()
            .line
            .starts_with("POST /api/queue/resume "),
        "{:?}",
        resume_queue.request().line
    );
    resume_queue.answer("200 OK", &queue(&[7, 5]));
    stream.send("queue-updated", &queue(&[7, 5]));
    tui.wait_for(WAIT, |text| !text.contains("Queue (paused)"));

    // A command the selected task's status does not allow sends nothing: retry on a QUEUED task.
    tui.press(b"r");
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !service.command_arrived(),
        "a QUEUED task cannot be retried, so no request goes out"
    );
}
