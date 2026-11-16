//! Roadmap item 67, the designs line: the header as every design in `docs/design/tui/` draws it -
//! the product name in the accent with a muted summary beside it (`transcribing 1 task` while tasks
//! run, `idle` otherwise), its first two rows on the surface band, the tile's column on the accent
//! tint, and the rule under the tabs in the border colour.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use terminal::{EventService, Tui, settings_in};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

fn dark(path: &[&str]) -> vt100::Color {
    token(path, "dark")
}

#[test]
fn the_header_follows_its_design() {
    let service = EventService::start();
    service.capture_startup_settings();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    let accent = dark(&["colors", "accent"]);
    tui.wait_for_cell(WAIT, "Speech-to-Text", |cell| cell.fgcolor() == accent);

    // lecture.m4a runs: the summary counts it.
    let scene = tui.wait_for_scene(WAIT, |screen| {
        screen.contains("Speech-to-Text   transcribing 1 task")
    });
    assert_eq!(scene.fg("transcribing"), dark(&["colors", "textMuted"]));
    let (surface, tint) = (
        dark(&["colors", "surface"]),
        dark(&["colors", "accentTint"]),
    );
    assert_eq!(
        scene.cell(0, 50).unwrap().bg,
        surface,
        "the first row's band"
    );
    assert_eq!(scene.cell(1, 50).unwrap().bg, surface, "the tab row's band");
    for row in 0..3 {
        assert_eq!(
            scene.cell(row, 5).unwrap().bg,
            tint,
            "the tile's column, row {row}"
        );
    }
    assert_eq!(
        scene.cell(2, 20).unwrap().fg,
        dark(&["colors", "border"]),
        "the rule"
    );
    assert_ne!(
        scene.cell(2, 20).unwrap().bg,
        surface,
        "no band under the tabs"
    );

    // It completes and nothing else runs: idle.
    stream.send(
        "task-updated",
        r#"{"id":5,"filename":"lecture.m4a","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7]}"#);
    tui.wait_for(WAIT, |screen| screen.contains("Speech-to-Text   idle"));
}
