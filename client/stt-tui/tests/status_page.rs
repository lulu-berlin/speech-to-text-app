//! Roadmap item 67: a FAILED or CANCELED task opens a status page - what it was doing when it
//! ended, its error and a retry that puts it back in the queue - and a task still waiting says it
//! is in the queue. Neither asks for turns.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_failed_task_opens_a_status_page_and_a_queued_one_says_it_waits() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("call.ogg"));

    // The newest task is selected: Down reaches the failed call.ogg and Enter opens its status
    // page, not a transcript - what it was doing, its error and the retry, and no turns are asked
    // for.
    tui.press(b"j");
    tui.press(b"\r");
    let page = tui.wait_for(WAIT, |text| {
        text.contains("Failed while transcribing at 04:58")
    });
    assert!(page.contains("call.ogg"), "the file name:\n{page}");
    assert!(
        page.contains("Error: whisper-cli exited with code 1"),
        "the stored error:\n{page}"
    );
    assert!(
        page.contains("retry puts it back in the queue"),
        "the retry:\n{page}"
    );
    assert!(!service.get_arrived(), "no turns are requested:\n{page}");

    // `r` retries it: the command goes out and the task waits in the queue, where the page goes.
    tui.press(b"r");
    let retry = service.next_command(WAIT);
    assert!(
        retry.request().line.starts_with("POST /api/tasks/4/retry "),
        "{:?}",
        retry.request().line
    );
    retry.answer(
        "200 OK",
        r#"{"id":4,"filename":"call.ogg","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        text.contains("Queue") && text.contains("lecture.m4a")
    });

    // Enter on the queued row says it waits in the queue, and asks for no turns.
    tui.press(b"j");
    tui.press(b"\r");
    let waiting = tui.wait_for(WAIT, |text| {
        text.contains("This task is still in the queue.")
    });
    assert!(waiting.contains("memo.wav"), "the file name:\n{waiting}");
    assert!(!service.get_arrived(), "no turns are requested:\n{waiting}");
}
