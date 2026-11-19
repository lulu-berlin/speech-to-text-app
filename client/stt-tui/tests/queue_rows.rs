//! Roadmap item 67, parity amendment: the queue's rows carry the GUI's detail - the phase while a
//! task is processed, its detected language and its size - beside the file name, status and
//! progress. As the design draws it, a row is two lines: the name, status or phase and progress;
//! then the language and size.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The queue row of `name`: its first line (the one with a percentage) and the line under it.
fn row<'a>(text: &'a str, name: &str) -> (&'a str, &'a str) {
    let mut lines = text.lines();
    let first = lines
        .find(|line| line.contains(name) && line.contains('%'))
        .unwrap_or("");
    (first, lines.next().unwrap_or(""))
}

#[test]
fn a_queue_row_shows_phase_language_size_and_progress() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    let screen = tui.wait_for(WAIT, |text| {
        row(text, "lecture.m4a").0.contains("Transcribing")
    });
    let (lecture, detail) = row(&screen, "lecture.m4a");
    assert!(lecture.contains("42%"), "{screen}");
    assert!(
        detail.contains("de · 57.7 MB"),
        "the language and size:\n{screen}"
    );

    // A queued task has no phase: its status instead.
    let (queued, detail) = row(&screen, "memo.wav");
    assert!(queued.contains("QUEUED"), "{screen}");
    assert!(!queued.contains("Transcribing"), "{screen}");
    assert!(detail.contains("auto · 3.1 MB"), "{screen}");
}
