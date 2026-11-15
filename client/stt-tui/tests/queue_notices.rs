//! Roadmap item 67: the latest finished tasks show as notices under the queue's rows - "memo.wav
//! transcribed — Enter open" or "call.ogg failed — see History" - the newest first and at most five
//! of them; Down reaches them from the last row, and Enter opens the notice's target: the
//! transcript, or History for a task that failed or was canceled.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn finished_tasks_show_as_notices_and_enter_opens_each_one() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // memo.wav finishes, then call.ogg fails: a line each, the newest first.
    stream.send(
        "task-updated",
        r#"{"id":7,"filename":"memo.wav","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav transcribed — Enter open")
    });
    stream.send(
        "task-updated",
        r#"{"id":4,"filename":"call.ogg","status":"FAILED","transcriptionProgress":0.5,"error":"boom"}"#,
    );
    let screen = tui.wait_for(WAIT, |text| text.contains("call.ogg failed — see History"));
    assert!(
        screen.find("call.ogg").unwrap_or(0) < screen.find("memo.wav transcribed").unwrap_or(0),
        "the newest notice is first:\n{screen}"
    );

    // Down from the last row reaches the notices; Enter on the failed one opens History.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));

    // Back to the queue, into the notices again, down to the completed one: Enter opens its
    // transcript.
    tui.press(b"Q");
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav transcribed — Enter open")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let turns = service.next_get_from(&tui, WAIT);
    assert!(
        turns.request().line.starts_with("GET /api/tasks/7/turns "),
        "{:?}",
        turns.request().line
    );
    turns.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));
}
