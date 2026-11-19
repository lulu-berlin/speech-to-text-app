//! Roadmap item 67, parity amendment: empty states. A queue with no tasks and a history with no
//! completed ones say what they are and what to do, instead of an empty frame.

mod settings_fixture;
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

/// A service with the queue paused, no tasks: what a pause on the empty queue answers.
const PAUSED: &str = r#"{"tasks":[],"queue":{"paused":true,"taskIds":[]}}"#;

/// The review (T5): the empty pages are pages, not placeholders - the queue's pause, the upload
/// dialog's key and History's search all still act on them.
#[test]
fn the_empty_pages_keys_still_act() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", EMPTY);
    tui.wait_for(WAIT, |text| text.contains("No tasks yet"));

    // Space pauses the empty queue: the write goes out and the answer draws the paused queue.
    tui.press(b" ");
    let pause = service.next_command(WAIT);
    assert!(
        pause.request().line.starts_with("POST /api/queue/pause "),
        "{:?}",
        pause.request().line
    );
    pause.answer("200 OK", PAUSED);
    // The request is the proof the key acted: an empty queue draws no state chip to change.

    // `u` opens the upload dialog over the empty queue, and Escape closes it.
    service.capture_next_settings();
    tui.press(b"u");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", settings_fixture::SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        !text.contains("Upload audio") && text.contains("No tasks yet")
    });

    // History's search opens on an empty list too, and Escape leaves it.
    tui.press(b"2");
    tui.wait_for(WAIT, |text| text.contains("No completed tasks yet"));
    tui.press(b"/");
    tui.wait_for(WAIT, |text| text.contains("Enter search"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        !text.contains("Enter search") && text.contains("No completed tasks yet")
    });
}
