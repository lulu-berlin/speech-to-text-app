//! Roadmap item 67: the connection's state. While the event stream is down the TUI says so, in the
//! browser's words, and the line goes once a new stream's snapshot has brought the view up to date.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
const UNREACHABLE: &str = "Can't reach the app's service. Reconnecting…";

#[test]
fn a_dropped_stream_is_announced_until_a_new_snapshot_arrives() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    assert!(
        !screen.contains(UNREACHABLE),
        "a live stream is not announced:\n{screen}"
    );

    // The stream drops: the TUI says so, and keeps saying it while the new stream has sent nothing.
    drop(first);
    tui.wait_for(WAIT, |text| text.contains(UNREACHABLE));
    let mut second = service.next_stream(WAIT);
    let screen = tui.wait_for(WAIT, |text| text.contains(UNREACHABLE));
    assert!(
        screen.contains("memo.wav"),
        "the last known queue stays on screen:\n{screen}"
    );

    // The new stream's snapshot ends the notice.
    second.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && !text.contains(UNREACHABLE)
    });
}
