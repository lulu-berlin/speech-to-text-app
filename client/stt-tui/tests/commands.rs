//! Roadmap item 67: the queue's commands from the TUI. `p` pauses or resumes the selected task,
//! `c` cancels it, `J`/`K` (and Shift-Down/Shift-Up) move it in the queue, and Space pauses or
//! resumes the whole queue - each the API call the browser makes, against the one backend; a
//! cancelled task leaves the queue for History, where `r` retries it. A command the task's status
//! does not allow sends nothing.

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

/// The line the queue row for `name` is on - a notice line above the rows names the file too.
fn row_line(text: &str, name: &str) -> Option<usize> {
    text.lines()
        .position(|line| line.contains(name) && line.contains('%'))
}

/// Whether the selected row - the one with the selection's bar `▍` - is `name`'s, in the queue or
/// in History. A key that acts on the selection waits for it: the selection is a row index, so the
/// key it follows has to have landed before the command reads it.
fn selected(text: &str, name: &str) -> bool {
    text.lines()
        .any(|line| line.contains('▍') && line.contains(name))
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
        row(text, "lecture.m4a").contains("Transcribing…")
            && row(text, "memo.wav").contains("QUEUED")
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

    // `c` cancels it, once the dialog is answered (a running task asks first): it leaves the queue
    // for History.
    tui.press(b"c");
    tui.wait_for(WAIT, |text| text.contains("Cancel lecture.m4a?"));
    tui.press(b"\r");
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
    // The row goes (the notice line still names the file).
    tui.wait_for(WAIT, |text| {
        row(text, "memo.wav").contains("QUEUED") && row(text, "lecture.m4a").is_empty()
    });

    // In History `r` on the canceled task retries it: it rejoins the queue at the end. The two
    // newer finishes come first, so it is the last row.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        text.contains("lecture.m4a") && text.contains("CANCELED")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| selected(text, "lecture.m4a"));
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
    tui.press(b"Q");
    let screen = tui.wait_for(WAIT, |text| {
        row(text, "lecture.m4a").contains("QUEUED") && row(text, "memo.wav").contains("QUEUED")
    });
    assert!(
        row_line(&screen, "memo.wav") < row_line(&screen, "lecture.m4a"),
        "the retried task is at the end of the queue:\n{screen}"
    );

    // Shift-Up moves the selected (last) row up: the same order the browser's drag sends.
    tui.press(b"\x1b[B"); // Down, onto the last row
    tui.wait_for(WAIT, |text| selected(text, "lecture.m4a"));
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
        // The rows, not the first mention of a file name: a notice line above the rows carries
        // names too, and the order the keys below act on is the rows'.
        match (row_line(text, "lecture.m4a"), row_line(text, "memo.wav")) {
            (Some(lecture), Some(memo)) => lecture < memo,
            _ => false,
        }
    });
    assert!(screen.contains("lecture.m4a"), "{screen}");

    // `J` moves the selected row down: back to the queue's order.
    tui.press(b"\x1b[A"); // Up, onto the first row
    tui.wait_for(WAIT, |text| selected(text, "lecture.m4a"));
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
        match (row_line(text, "memo.wav"), row_line(text, "lecture.m4a")) {
            (Some(memo), Some(lecture)) => memo < lecture,
            _ => false,
        }
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
    tui.wait_for(WAIT, |text| text.contains("● Paused"));
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
    tui.wait_for(WAIT, |text| !text.contains("● Paused"));

    // A command the selected task's status does not allow sends nothing: retry on a QUEUED task.
    tui.press(b"r");
    tui.settle();
    assert!(
        !service.command_arrived(),
        "a QUEUED task cannot be retried, so no request goes out"
    );
}
