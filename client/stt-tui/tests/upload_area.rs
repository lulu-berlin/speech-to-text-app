//! Roadmap item 67, parity amendment: the queue page's upload area. It sits above the queue and is
//! reachable with the arrows or j/k, so uploading does not depend on knowing the `u` key; Enter or
//! Space on it opens the upload prompt, as `u` does. Focused, the area is tinted as a selected row
//! is (the design's focus), and the header stops offering Space for the queue.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
const AREA: &str = "Choose audio files";

#[test]
fn the_upload_area_is_reachable_and_opens_the_prompt() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // The stored mode drawn: the frames before the startup settings land are in what `auto`
    // resolves to on this machine, and the colours below are compared across frames.
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(AREA) && text.contains("memo.wav") && text.contains("Theme: dark")
    });
    // The header's line has no fill of its own: the page's background.
    let page = scene.bg("Queue 2 tasks");
    assert_eq!(
        scene.bg(AREA),
        page,
        "the queue rows start focused; the upload area sits on the page's background"
    );
    assert_ne!(scene.bg("lecture.m4a"), page, "the first row is selected");

    // Up from the first row reaches the upload area: it is tinted, the row is not.
    tui.press(b"\x1b[A");
    tui.wait_for_cell(WAIT, AREA, |cell| cell.bgcolor() != page);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains(AREA));
    assert!(
        scene.text().contains("memo.wav"),
        "the queue stays visible:\n{}",
        scene.text()
    );
    assert_eq!(scene.bg("lecture.m4a"), page, "no row is selected");
    assert!(
        !scene.text().contains("Space pause"),
        "Space opens the dialog here, not the queue's pause:\n{}",
        scene.text()
    );

    // Enter opens the upload prompt; Escape closes it, and down returns to the rows.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    tui.press(b"\x1b[B");
    tui.wait_for_cell(WAIT, AREA, |cell| cell.bgcolor() == page);
    tui.wait_for_cell(WAIT, "lecture.m4a", |cell| cell.bgcolor() != page);

    // `k` and Space do the same from a row: k up to the area, Space opens the prompt.
    tui.press(b"k");
    tui.wait_for_cell(WAIT, AREA, |cell| cell.bgcolor() != page);
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
}
