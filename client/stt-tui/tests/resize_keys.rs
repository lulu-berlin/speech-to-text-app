//! Roadmap item 67 (discovered, the PR's CI run 2026-11-16): a key pressed right after a terminal
//! resize is applied. A resize and a keypress that reach the TUI together must both be read, so
//! the UI never looks frozen until the user types again.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
/// How many resize-then-key rounds run: a key lost to a resize showed within a dozen.
const ROUNDS: u16 = 50;

#[test]
fn a_key_pressed_right_after_a_resize_is_applied() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Each key follows a resize at once: `?` opens the key reference, Escape closes it.
    for round in 0..ROUNDS {
        tui.resize(100 + round % 2, 24);
        tui.press(b"?");
        tui.wait_for(WAIT, |text| text.contains("Queue keys"));
        tui.resize(101 - round % 2, 24);
        tui.press(b"\x1b");
        tui.wait_for(WAIT, |text| !text.contains("Queue keys"));
    }
}
