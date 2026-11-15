//! Roadmap item 67: a running queue row has a progress bar beside its status, drawn as the design
//! draws it - `━` for each twenty-column cell done, `╸` for a half-done one, `─` for the rest - and,
//! while the task is chunking and has no percentage yet, an indeterminate segment instead, as the
//! browser's bar sweeps one.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, EventStream, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The bar on `lecture.m4a`'s row: its `━`, `╸` and `─` only.
fn progress_line(screen: &str) -> Option<String> {
    let line = screen.lines().find(|line| line.contains("lecture.m4a"))?;
    let bar: String = line
        .chars()
        .filter(|character| matches!(character, '━' | '╸' | '─'))
        .collect();
    (!bar.is_empty()).then_some(bar)
}

/// The TUI on `snapshot`, with what has to outlive it: the service, the stream and the state.
fn open(snapshot: &str) -> (EventService, Tui, EventStream, tempfile::TempDir) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", snapshot);
    (service, tui, stream, state)
}

#[test]
fn a_running_row_has_a_progress_line_and_an_indeterminate_one_while_chunking() {
    // Transcribing at 42%: 8.4 of twenty cells - eight whole ones, a half one, then the track.
    let (_service, tui, _stream, _state) = open(SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| progress_line(text).is_some());
    let bar = progress_line(&screen).unwrap();
    assert_eq!(
        bar,
        format!("{}╸{}", "━".repeat(8), "─".repeat(11)),
        "42% of twenty cells filled from the left:\n{screen}"
    );

    // Chunking, with no percentage yet: a segment of two fifths of the line, not a 0% bar.
    let mut chunking: Value = serde_json::from_str(SNAPSHOT).unwrap();
    for task in chunking["tasks"].as_array_mut().unwrap() {
        if task["id"] == 5 {
            task["phase"] = json!("CHUNKING");
            task["transcriptionProgress"] = json!(0.0);
        }
    }
    let (_service, tui, _stream, _state) = open(&chunking.to_string());
    let screen = tui.wait_for(WAIT, |text| progress_line(text).is_some());
    let bar = progress_line(&screen).unwrap();
    let width = bar.chars().count();
    let segment = bar.chars().filter(|character| *character == '━').count();
    assert_eq!(
        segment,
        (width as f64 * 0.4).round() as usize,
        "an indeterminate segment two fifths wide:\n{screen}"
    );
}
