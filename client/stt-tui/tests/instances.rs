//! Roadmap item 67: several TUIs side by side. Each opening from the tray (item 66's terminal
//! descriptor, `stt-tui` with no arguments) is its own process with its own event stream and the
//! credential; none locks out another, and quitting one leaves the others working.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn two_tuis_run_side_by_side_and_quitting_one_leaves_the_other() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let program = env!("CARGO_BIN_EXE_stt-tui");

    let mut first = Tui::start(program.as_ref(), dir.path(), 100, 24);
    let mut first_stream = service.next_stream(WAIT);
    let mut second = Tui::start(program.as_ref(), dir.path(), 100, 24);
    let mut second_stream = service.next_stream(WAIT);
    for stream in [&first_stream, &second_stream] {
        assert_eq!(
            stream.authorization.as_deref(),
            Some("Bearer the-install-secret"),
            "each instance opens its own stream with the credential"
        );
    }
    first_stream.send("snapshot", SNAPSHOT);
    second_stream.send("snapshot", SNAPSHOT);
    first.wait_for(WAIT, |text| text.contains("memo.wav"));
    second.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The service's change reaches both, each through its own stream.
    let progress = r#"{"id":5,"transcriptionProgress":0.9}"#;
    first_stream.send("task-progress", progress);
    second_stream.send("task-progress", progress);
    first.wait_for(WAIT, |text| text.contains("90%"));
    second.wait_for(WAIT, |text| text.contains("90%"));

    // Quitting the first leaves the second running and answering keys.
    first.press(b"q");
    assert!(first.exit_within(WAIT).is_some(), "the first TUI quits");
    assert!(
        second.exit_within(Duration::from_millis(300)).is_none(),
        "the second TUI keeps running"
    );
    second.press(b"h");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    second.wait_for(WAIT, |text| {
        text.contains("History") && text.contains("interview.mp3")
    });
    drop(first_stream);
}
