//! Roadmap item 67: while diarizing, a running queue row has a second line for the speakers stage -
//! the browser's two-stage bar, embedding on the track's left half and clustering on its right,
//! with the stage and its own percentage beside it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_diarizing_row_has_a_speakers_line_with_its_stage_and_percentage() {
    // lecture.m4a transcribed and half through clustering.
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    for task in snapshot["tasks"].as_array_mut().unwrap() {
        if task["id"] == 5 {
            task["phase"] = json!("DIARIZING");
            task["transcriptionProgress"] = json!(1.0);
            task["diarizationStage"] = json!("CLUSTERING");
            task["diarizationProgress"] = json!(0.5);
        }
    }
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
    stream.send("snapshot", &snapshot.to_string());

    // The line after the transcription line names the stage and its percentage, and its track is
    // filled three quarters: clustering's half, half done, after embedding's.
    let screen = tui.wait_for(WAIT, |text| text.contains("Clustering 50%"));
    let mut lines = screen.lines();
    lines.find(|line| line.contains("lecture.m4a"));
    let speakers = lines.next().unwrap_or_default();
    assert!(
        speakers.contains("Clustering 50%"),
        "the speakers line is the row's second:\n{screen}"
    );
    let track: String = speakers
        .chars()
        .filter(|character| matches!(character, '━' | '─'))
        .collect();
    let width = track.chars().count();
    let filled = track
        .chars()
        .take_while(|character| *character == '━')
        .count();
    assert!(width > 0, "a track:\n{screen}");
    assert_eq!(
        filled,
        (width as f64 * 0.75).round() as usize,
        "three quarters of {width} filled:\n{screen}"
    );
    drop(stream);
}
