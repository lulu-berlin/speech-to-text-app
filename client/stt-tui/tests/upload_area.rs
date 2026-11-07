//! Roadmap item 67, parity amendment: the queue page's upload area. It sits above the queue and is
//! reachable with the arrows or j/k, so uploading does not depend on knowing the `u` key; Enter or
//! Space on it opens the upload prompt, as `u` does.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_upload_area_is_reachable_and_opens_the_prompt() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| text.contains("Upload a file"));
    assert!(
        !screen.contains("> Upload a file"),
        "the queue rows start focused:\n{screen}"
    );

    // Up from the first row reaches the upload area.
    tui.press(b"\x1b[A");
    let screen = tui.wait_for(WAIT, |text| text.contains("> Upload a file"));
    assert!(
        screen.contains("memo.wav"),
        "the queue stays visible:\n{screen}"
    );

    // Enter opens the upload prompt; Escape closes it, and down returns to the rows.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload:"));
    tui.press(b"\x1b[B");
    let screen = tui.wait_for(WAIT, |text| !text.contains("> Upload a file"));
    assert!(screen.contains("memo.wav"), "{screen}");

    // `k` and Space do the same from a row: k up to the area, Space opens the prompt.
    tui.press(b"k");
    tui.wait_for(WAIT, |text| text.contains("> Upload a file"));
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains("Upload:"));
}
