//! Roadmap item 67, third amendment: a History row deletes any task after a confirmation - the same
//! dialog the queue's delete asks in - and the service's `task-deleted` event takes the row away.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, history_row};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_history_row_is_deleted_after_the_dialog_and_leaves_with_the_event() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        history_row(text, "interview.mp3").is_some() && history_row(text, "call.ogg").is_some()
    });

    // Delete on the failed call.ogg asks first; Escape sends nothing.
    tui.press(b"j");
    tui.press(b"\x1b[3~");
    let screen = tui.wait_for(WAIT, |text| text.contains("Delete call.ogg?"));
    assert!(
        screen.contains("Removes the task, its transcript and audio for good."),
        "the queue's dialog:\n{screen}"
    );
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete call.ogg?"));
    std::thread::sleep(Duration::from_millis(200));
    assert!(!service.command_arrived(), "Escape sends nothing");

    // Asked again, Enter deletes it; the service's event takes the row away, the other stays.
    tui.press(b"\x1b[3~");
    tui.wait_for(WAIT, |text| text.contains("Delete call.ogg?"));
    tui.press(b"\r");
    let delete = service.next_command(WAIT);
    assert!(
        delete.request().line.starts_with("DELETE /api/tasks/4 "),
        "{:?}",
        delete.request().line
    );
    delete.answer("204 No Content", "");
    stream.send("task-deleted", r#"{"id":4}"#);
    tui.wait_for(WAIT, |text| {
        history_row(text, "interview.mp3").is_some() && history_row(text, "call.ogg").is_none()
    });
}
