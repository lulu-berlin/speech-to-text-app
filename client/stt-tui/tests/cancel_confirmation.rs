//! Roadmap item 67: a row offers only the commands its status allows, and cancelling a task that
//! has started - running, or paused after it started - asks first, as the browser does; a task
//! never started is cancelled at once.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// `snapshot.json` with lecture.m4a paused after it started, memo.wav behind it, never started.
fn paused_after_starting() -> String {
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    snapshot["tasks"][0]["status"] = Value::from("PAUSED");
    snapshot["queue"]["taskIds"] = json!([5, 7]);
    snapshot.to_string()
}

#[test]
fn cancelling_a_started_task_asks_first_and_a_never_started_one_does_not() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &paused_after_starting());
    tui.wait_for(WAIT, |text| {
        text.contains("lecture.m4a") && text.contains("PAUSED")
    });

    // lecture.m4a started before it was paused: `c` asks first; Escape keeps it, nothing sent.
    tui.press(b"c");
    tui.wait_for(WAIT, |text| text.contains("Cancel lecture.m4a?"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Cancel lecture.m4a?"));
    tui.settle();
    assert!(!service.command_arrived(), "Escape sends nothing");

    // memo.wav never started: `c` cancels it at once, with no dialog.
    tui.press(b"j");
    tui.press(b"c");
    let cancel = service.next_command(WAIT);
    assert!(
        cancel
            .request()
            .line
            .starts_with("POST /api/tasks/7/cancel "),
        "{:?}",
        cancel.request().line
    );
    assert!(
        !tui.text().contains("Cancel memo.wav?"),
        "a never-started task is cancelled without a dialog"
    );
}
