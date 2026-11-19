//! Roadmap item 67, third amendment: a History row shows the finish date, the duration and a
//! stored error beside the file name and the status, and the rows come newest first.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The fixture snapshot with the failed call.ogg finished a second after the completed
/// interview.mp3, so the newest first is visible: it is the newer of the two there.
fn newest_last() -> String {
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    let tasks = snapshot["tasks"].as_array_mut().unwrap();
    let interview = tasks
        .iter()
        .find(|task| task["filename"] == "interview.mp3")
        .unwrap()["finishedAt"]
        .as_i64()
        .unwrap();
    let call = tasks
        .iter_mut()
        .find(|task| task["filename"] == "call.ogg")
        .unwrap();
    call["finishedAt"] = (interview + 1000).into();
    snapshot.to_string()
}

#[test]
fn history_rows_show_the_date_the_duration_and_the_error_newest_first() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &newest_last());
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("call.ogg") && text.contains("interview.mp3")
    });

    // Newest first: the failed call.ogg outranks the completed interview.mp3.
    assert!(
        screen.find("call.ogg").unwrap() < screen.find("interview.mp3").unwrap(),
        "the newest finished task comes first:\n{screen}"
    );
    let line = |needle: &str| -> String {
        screen
            .lines()
            .find(|line| line.contains(needle))
            .unwrap_or_else(|| panic!("{needle:?} is on screen:\n{screen}"))
            .to_string()
    };
    assert!(line("call.ogg").contains("FAILED"), "{}", line("call.ogg"));
    assert!(
        line("Oct 9, 2026").contains("10:00"),
        "the call's date and duration:\n{screen}"
    );
    assert!(
        screen.contains("whisper-cli exited with code 1"),
        "the stored error:\n{screen}"
    );
    assert!(
        line("interview.mp3").contains("COMPLETED"),
        "{}",
        line("interview.mp3")
    );
    assert!(
        line("19:45").contains("Oct 9, 2026"),
        "the interview's date and duration:\n{screen}"
    );
    assert!(
        screen.find("10:00").unwrap() < screen.find("19:45").unwrap(),
        "the newer task's lines come first:\n{screen}"
    );
}
