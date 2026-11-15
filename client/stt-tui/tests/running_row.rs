//! Roadmap item 67: a running queue row reads "Transcribing… · 00:12" - its phase and the time since
//! it started, ticking - beside its percentage, as the browser's running line does.
//! `STT_TUI_CLOCK` names a file holding "now" in epoch milliseconds, so the test moves the clock
//! itself and nothing here reads real time.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// `lecture.m4a`'s `startedAt` in `snapshot.json`.
const STARTED_AT: i64 = 1_791_537_661_000;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_running_row_shows_its_phase_and_elapsed_time_ticking_beside_its_percentage() {
    let clock = tempfile::tempdir().unwrap();
    let now = clock.path().join("now");
    fs::write(&now, (STARTED_AT + 12_000).to_string()).unwrap();
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        &[("STT_TUI_CLOCK", now.to_str().unwrap())],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    // Twelve seconds in: the running row's phase, its elapsed time, and its percentage.
    let screen = tui.wait_for(WAIT, |text| text.contains("Transcribing… · 00:12"));
    let row = screen
        .lines()
        .find(|line| line.contains("lecture.m4a"))
        .unwrap_or_default();
    assert!(
        row.contains("Transcribing… · 00:12") && row.contains("42%"),
        "the running row:\n{screen}"
    );

    // A second later the row reads it - no event arrived, the time ticks on its own.
    fs::write(&now, (STARTED_AT + 13_000).to_string()).unwrap();
    tui.wait_for(WAIT, |text| text.contains("Transcribing… · 00:13"));
}
