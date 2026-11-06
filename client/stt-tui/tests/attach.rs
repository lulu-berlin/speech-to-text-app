//! Roadmap item 67: with the service running, the TUI attaches with the per-install secret from
//! `config.json` and shows the queue from the service's event stream - every queued task's file
//! name, status and progress, in queue order - as the browser does.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{Tui, service_with_events};

/// The contract's own `snapshot` event (ADR 0029): tasks 5 (`lecture.m4a`, RUNNING at 0.42) and 7
/// (`memo.wav`, QUEUED at 0.0) are the queue, in that order.
const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

#[test]
fn with_the_service_running_the_tui_attaches_with_the_secret_and_shows_the_live_queue() {
    let (port, events_authorization) = service_with_events(("snapshot", SNAPSHOT));
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), port);

    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
    let screen = tui.wait_for(Duration::from_secs(20), |text| {
        text.contains("lecture.m4a") && text.contains("memo.wav")
    });

    let lines: Vec<&str> = screen.lines().collect();
    let row = |name: &str| {
        lines
            .iter()
            .position(|line| line.contains(name))
            .unwrap_or_else(|| panic!("no row for {name}:\n{screen}"))
    };
    let (lecture, memo) = (row("lecture.m4a"), row("memo.wav"));
    assert!(lecture < memo, "queue order:\n{screen}");
    assert!(lines[lecture].contains("RUNNING"), "{screen}");
    assert!(lines[lecture].contains("42%"), "{screen}");
    assert!(lines[memo].contains("QUEUED"), "{screen}");
    assert!(lines[memo].contains("0%"), "{screen}");
    assert_eq!(
        events_authorization.lock().unwrap().as_deref(),
        Some("Bearer the-install-secret"),
        "the event stream is asked for with the secret from config.json"
    );
}
