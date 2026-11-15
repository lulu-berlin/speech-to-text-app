//! Roadmap item 67: a silent event stream. The service sends at least a heartbeat every 5 s
//! (ADR 0044), so a stream with no event at all for 15 s is treated as dropped and opened anew, as
//! the browser does - a network partition that leaves the connection open must not freeze the view.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
/// The 15 s silence, the 1 s reconnect delay and room for a loaded machine.
const SILENCE_AND_RECONNECT: Duration = Duration::from_secs(30);

#[test]
fn a_stream_silent_for_15_seconds_is_opened_anew() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The first stream stays open and says nothing - not even a heartbeat.
    let mut second = service.next_stream(SILENCE_AND_RECONNECT);
    assert_eq!(
        second.authorization.as_deref(),
        Some("Bearer the-install-secret"),
        "the new stream carries the credential"
    );

    // The new stream's snapshot is what the view shows from then on.
    second.send(
        "snapshot",
        &SNAPSHOT.replace("\"memo.wav\"", "\"renamed.wav\""),
    );
    tui.wait_for(WAIT, |text| text.contains("renamed.wav"));
    drop(first);
}
