//! Roadmap item 67, parity amendment: the `?` reference. The footer names the help key, `?` opens
//! the current page's full key list, and `?` or Escape closes it, leaving the page as it was.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, table_row};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_help_key_lists_the_pages_keys_and_closes() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 200, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // The queue drawn (before the snapshot no row key shows, so the footer starts differently).
    let queue = tui.wait_for(WAIT, |text| {
        text.contains("? help") && text.contains("memo.wav")
    });
    assert!(
        queue.contains("   upload   "),
        "the footer still states the keys:\n{queue}"
    );

    // `?` opens the queue's reference, which every key on that page appears in.
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    assert!(table_row(&help, "u", "upload"), "{help}");
    assert!(
        table_row(&help, "Shift-↑↓ or J/K", "reorder"),
        "reorder is listed:\n{help}"
    );
    tui.press(b"?");
    // The queue is back: waited for together with the list's absence, which a frame caught
    // mid-repaint also shows.
    tui.wait_for(WAIT, |text| {
        !text.contains("Queue keys") && text.contains("memo.wav")
    });

    // Escape closes it too, and history has its own list.
    tui.press(b"?");
    tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Queue keys"));
    tui.press(b"2");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("History keys"));
    assert!(table_row(&help, "Enter", "open"), "{help}");
}
