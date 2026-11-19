//! Roadmap item 67, parity amendment: the wheel scrolls the focused list. Down/up move the queue's
//! selection, the history's, the settings' and a transcript's segment cursor (the view follows it).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::json;
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

/// The fixture snapshot with both finished tasks completed - `interview.mp3` and `call.ogg` - so
/// History has two rows to move between.
fn two_completed() -> String {
    let mut snapshot: serde_json::Value = serde_json::from_str(SNAPSHOT).unwrap();
    for task in snapshot["tasks"].as_array_mut().unwrap() {
        if task["status"] == "FAILED" {
            task["status"] = json!("COMPLETED");
            task["error"] = serde_json::Value::Null;
        }
    }
    snapshot.to_string()
}

/// A wheel notch at `column`, `row` (zero-based): SGR button 65 is down, 64 is up.
fn wheel(tui: &mut Tui, down: bool, column: u16, row: u16) {
    let button = if down { 65 } else { 64 };
    tui.press(format!("\x1b[<{button};{};{}M", column + 1, row + 1).as_bytes());
}

#[test]
fn the_wheel_moves_the_focused_list_and_the_transcripts_cursor() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    // A notch down selects the queue's second row; `d` offers that file.
    wheel(&mut tui, true, 3, 6);
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
    // Escape is read alone only once its effect shows: a key sent before that can arrive in the
    // same read and be taken as Alt+key.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete memo.wav?"));

    // The history: a notch down selects the second completed task, Enter opens it.
    stream.send("snapshot", &two_completed());
    tui.press(b"H");
    // History has arrived once `interview.mp3` shows (the queue lists neither of these tasks).
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    wheel(&mut tui, true, 3, 2);
    tui.press(b"\r");
    let transcript = service.next_get_from(&tui, WAIT);
    assert!(
        transcript
            .request()
            .line
            .starts_with("GET /api/tasks/4/turns "),
        "{:?}",
        transcript.request().line
    );
    transcript.answer("200 OK", TURNS);
    tui.wait_for_highlight(WAIT, "Good morning.");

    // In a transcript the wheel moves the segment cursor, and the view follows it.
    wheel(&mut tui, true, 3, 6);
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    wheel(&mut tui, false, 3, 6);
    tui.wait_for_highlight(WAIT, "Good morning.");

    // The settings: a notch down selects the threads row; Enter, Up and Enter raise them.
    tui.press(b"3");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Threads"));
    wheel(&mut tui, true, 3, 3);
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Enter save"));
    tui.press(b"\x1b[A\r");
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    assert!(
        write.request().body.contains(r#""threads":1"#),
        "the wheel's row's Up picked the first thread count: {}",
        write.request().body
    );
}
