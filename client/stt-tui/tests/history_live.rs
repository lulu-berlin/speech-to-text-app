//! Roadmap item 67, parity amendment: History follows the events while it is open, as the browser's
//! list does - opening the page shows what the stream already knows, and a task that finishes lands
//! in its rows without the page being reopened.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const COMPLETED: &str = include_str!("../../../docs/api/task.completed.json");
/// Short enough that the stream's 15 s silence never runs out during a wait: every step here is a
/// key press or an event the test itself sends.
const WAIT: Duration = Duration::from_secs(10);

#[test]
fn a_task_that_finishes_while_history_is_open_lands_in_its_list() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);

    // Nothing has finished yet: the snapshot holds the queue alone, so History starts empty.
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    snapshot["tasks"]
        .as_array_mut()
        .unwrap()
        .retain(|task| task["status"] != "COMPLETED" && task["status"] != "FAILED");
    stream.send("snapshot", &snapshot.to_string());
    tui.wait_for(WAIT, |text| text.contains("Live"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("No completed tasks yet"));

    // It finishes while History shows: the event carries the whole task, and the open list takes it
    // in place, as the browser's does.
    stream.send("task-updated", COMPLETED);
    tui.wait_for(WAIT, |text| {
        text.lines()
            .any(|line| line.contains("interview.mp3") && line.contains("COMPLETED"))
    });
}
