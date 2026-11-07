//! Roadmap item 67, parity amendment: the `?` reference. The footer names the help key, `?` opens
//! the current page's full key list, and `?` or Escape closes it, leaving the page as it was.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_help_key_lists_the_pages_keys_and_closes() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 200, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let queue = tui.wait_for(WAIT, |text| text.contains("? help"));
    assert!(
        queue.contains("u upload"),
        "the footer still states the keys:\n{queue}"
    );

    // `?` opens the queue's reference, which every key on that page appears in.
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    assert!(help.contains("u upload"), "{help}");
    assert!(help.contains("J/K"), "reorder is listed:\n{help}");
    tui.press(b"?");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Queue keys"));
    assert!(screen.contains("memo.wav"), "the queue is back:\n{screen}");

    // Escape closes it too, and history has its own list.
    tui.press(b"?");
    tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Queue keys"));
    tui.press(b"2");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    tui.wait_for(WAIT, |text| {
        text.contains("History keys") || text.contains("[2 History]")
    });
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("History keys"));
    assert!(help.contains("enter open"), "{help}");
}
