//! Roadmap item 67, parity amendment: empty states. A queue with no tasks and a history with no
//! completed ones say what they are and what to do, instead of an empty frame.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

/// A service with nothing in it: no tasks, an empty queue.
const EMPTY: &str = r#"{"tasks":[],"queue":{"paused":false,"taskIds":[]}}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn an_empty_queue_and_history_say_what_to_do() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", EMPTY);

    let queue = tui.wait_for(WAIT, |text| text.contains("No tasks yet"));
    assert!(queue.contains("upload"), "it says how to start:\n{queue}");
    assert!(queue.contains("u"), "it names the key:\n{queue}");

    tui.press(b"2");
    let history = tui.wait_for(WAIT, |text| text.contains("No completed tasks yet"));
    for page in ["Queue", "History", "Settings"] {
        assert!(
            history.contains(page),
            "the tab bar names {page}:\n{history}"
        );
    }
}
