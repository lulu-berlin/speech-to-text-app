//! Roadmap item 67: a transcript's task can be deleted from the transcript itself - after the same
//! confirmation the rows ask in - and the page returns to History, where the service's event takes
//! the row away.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{history_row, open_transcript};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn deleting_a_transcript_asks_first_then_returns_to_history_without_its_row() {
    let mut open = open_transcript();
    let tui = &mut open.tui;

    // Delete asks the queue's dialog first; Enter deletes the task behind the open transcript.
    tui.press(b"\x1b[3~");
    let dialog = tui.wait_for(WAIT, |text| text.contains("Delete interview.mp3?"));
    assert!(
        dialog.contains("Removes the task, its transcript and audio for good."),
        "the queue's dialog:\n{dialog}"
    );
    tui.press(b"\r");
    let delete = open.service.next_command(WAIT);
    assert!(
        delete.request().line.starts_with("DELETE /api/tasks/3 "),
        "{:?}",
        delete.request().line
    );
    delete.answer("204 No Content", "");

    // The transcript is gone: History is back, and the event takes the row away.
    let history = tui.wait_for(WAIT, |text| {
        text.contains("All statuses ▾") && !text.contains("Speakers")
    });
    assert!(history.contains("call.ogg"), "the other row:\n{history}");
    open.stream.send("task-deleted", r#"{"id":3}"#);
    tui.wait_for(WAIT, |text| {
        history_row(text, "interview.mp3").is_none() && history_row(text, "call.ogg").is_some()
    });
}
