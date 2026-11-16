//! Roadmap item 67, the design pass (the user's report, 2026-11-15, after btop): while a dialog is
//! open the page under it is greyed out - every cell outside the dialog in the muted colour - and
//! the dialog's frame is in the accent colour, showing which one has the focus.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use terminal::{EventService, Scene, Tui, settings_in};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

fn dark(path: &[&str]) -> vt100::Color {
    token(path, "dark")
}

/// The foreground of the footer's first key: the first cell drawn on the screen's last row.
fn footer_key(scene: &Scene) -> vt100::Color {
    let row = scene.cells.len() - 1;
    scene.cells[row]
        .iter()
        .find(|cell| !cell.text.trim().is_empty())
        .unwrap_or_else(|| panic!("a footer:\n{}", scene.text()))
        .fg
}

/// The colour of the frame corner left of the dialog title `title`.
fn corner(scene: &Scene, title: &str) -> (String, vt100::Color) {
    let (row, column) = scene
        .position(title)
        .unwrap_or_else(|| panic!("{title:?} on the screen:\n{}", scene.text()));
    let cell = scene.cell(row, column - 1).unwrap();
    (cell.text.clone(), cell.fg)
}

#[test]
fn an_open_dialog_greys_the_page_and_frames_itself_in_the_accent() {
    let service = EventService::start();
    service.capture_startup_settings();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    let (text, muted, accent) = (
        dark(&["colors", "text"]),
        dark(&["colors", "textMuted"]),
        dark(&["colors", "accent"]),
    );
    tui.wait_for_cell(WAIT, "memo.wav", |cell| cell.fgcolor() == text);

    // The key reference: the page, its header and its footer greyed; its frame in the accent.
    tui.press(b"?");
    tui.wait_for_cell(WAIT, "memo.wav", |cell| cell.fgcolor() == muted);
    let scene = tui.wait_for_scene(WAIT, |screen| screen.contains("Queue keys"));
    assert_eq!(scene.fg("Speech-to-Text"), muted, "the header");
    assert_eq!(footer_key(&scene), muted, "the footer's keys");
    assert_eq!(corner(&scene, "Queue keys"), ("┌".to_string(), accent));

    // Closed, the page is itself again.
    tui.press(b"\x1b");
    tui.wait_for_cell(WAIT, "memo.wav", |cell| cell.fgcolor() == text);

    // A confirmation does the same.
    tui.press(b"d");
    tui.wait_for_cell(WAIT, "memo.wav", |cell| cell.fgcolor() == muted);
    let scene = tui.wait_for_scene(WAIT, |screen| screen.contains("Delete lecture.m4a?"));
    assert_eq!(
        corner(&scene, "Delete lecture.m4a?"),
        ("┌".to_string(), accent)
    );
}
