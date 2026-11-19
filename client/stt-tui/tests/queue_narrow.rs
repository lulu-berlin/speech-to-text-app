//! Roadmap item 67, second amendment: queue rows in a narrow terminal. At 80 and at 60 columns
//! every row keeps its status and percentage on its first line; a file name that does not fit is
//! shortened with `…`. As the width shrinks the bar drops out first (80 columns), then the running
//! row's phase gives way to its status chip (60).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

/// Tasks 5 (`lecture.m4a`, RUNNING at 42 %) and 7 (`memo.wav`, QUEUED); its FAILED `call.ogg` is
/// History's, not a queue row.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const LONG: &str = "field-recording-interview-with-the-harbour-master-2026-11-07.m4a";
const WAIT: Duration = Duration::from_secs(20);

/// Whether the screen line naming `start` - the beginning of a file name - also carries `status`
/// and `percent`: the row did not wrap.
fn one_line(text: &str, start: &str, status: &str, percent: &str) -> bool {
    text.lines()
        .find(|line| line.contains(start))
        .is_some_and(|line| line.contains(status) && line.contains(percent))
}

/// Whether every row fits on its first line, the running one showing `running`.
fn rows_fit(text: &str, running: &str) -> bool {
    one_line(text, "lecture.m4a", running, "42%")
        && one_line(text, "memo.wav", "QUEUED", "0%")
        && one_line(text, "field-recording", "QUEUED", "0%")
}

#[test]
fn queue_rows_stay_on_one_line_with_their_percentage_at_80_and_60_columns() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), state.path(), 80, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    stream.send(
        "task-updated",
        &format!(r#"{{"id":9,"filename":"{LONG}","status":"QUEUED","transcriptionProgress":0.0}}"#),
    );
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[5,7,9]}"#);

    let screen = tui.wait_for(WAIT, |text| rows_fit(text, "Transcribing…"));
    let long = screen
        .lines()
        .find(|line| line.contains("field-recording"))
        .unwrap_or_default();
    assert!(
        long.contains('…') && !screen.contains(LONG),
        "the long name is shortened with an ellipsis at 80 columns:\n{screen}"
    );
    assert!(
        !screen.contains('━'),
        "the bar drops out at 80 columns:\n{screen}"
    );

    tui.resize(60, 24);
    let screen = tui.wait_for(WAIT, |text| {
        rows_fit(text, "RUNNING") && text.lines().all(|line| line.chars().count() <= 60)
    });
    assert!(
        !screen.contains("Transcribing"),
        "the phase gives way to the status at 60 columns:\n{screen}"
    );
}
