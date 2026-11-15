//! Roadmap item 67, parity amendment: deleting a queue task. `d`, Delete or Backspace opens a
//! confirmation naming the file; Enter sends `DELETE /api/tasks/{id}` and Escape sends nothing. The
//! service's `task-deleted` event removes the row.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn delete_asks_first_and_the_deleted_event_removes_the_row() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"j");

    // `d` asks first; Escape cancels with no request.
    tui.press(b"d");
    let screen = tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
    assert!(screen.contains("Enter Delete task"), "{screen}");
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete memo.wav?"));
    assert!(!service.command_arrived(), "Escape sends nothing");

    // Delete opens the same question; Enter sends the DELETE.
    tui.press(b"\x1b[3~");
    tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
    tui.press(b"\r");
    let delete = service.next_command(WAIT);
    assert!(
        delete.request().line.starts_with("DELETE /api/tasks/7 "),
        "{:?}",
        delete.request().line
    );
    delete.answer("204 No Content", "");

    // The service's event takes the row away and the others stay. Both are waited for together: a
    // frame still being repainted also lacks the row, so its absence alone proves nothing.
    stream.send("task-deleted", r#"{"id":7}"#);
    tui.wait_for(WAIT, |text| {
        text.contains("lecture.m4a")
            && !text
                .lines()
                .any(|line| line.contains("memo.wav") && line.contains('%'))
    });

    // Backspace opens it too; Escape leaves the task.
    tui.press(b"j");
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| text.contains("Delete "));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete "));
    assert!(!service.command_arrived(), "Escape sends nothing");
}
