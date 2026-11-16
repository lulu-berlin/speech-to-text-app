//! Roadmap item 67, the designs line: the History page as `docs/design/tui/history-dark.svg` draws
//! it at 100x30 - no panel; the controls row (`/ Search` and its field, `f Status` and its chip,
//! `n of m tasks`), a rule, and one line a task - its status chip first in its group's colours, the
//! name, the finish date and duration - with a stored error under it and the selected row tinted.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use terminal::{EventService, Tui, settings_in};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
const WIDTH: usize = 100;

fn dark(path: &[&str]) -> vt100::Color {
    token(path, "dark")
}

#[test]
fn the_history_page_follows_its_design() {
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
    let (text, muted, accent, border, raised) = (
        dark(&["colors", "text"]),
        dark(&["colors", "textMuted"]),
        dark(&["colors", "accent"]),
        dark(&["colors", "border"]),
        dark(&["colors", "surfaceRaised"]),
    );
    tui.wait_for_cell(WAIT, "memo.wav", |cell| cell.fgcolor() == text);
    tui.press(b"H");
    let scene = tui.wait_for_scene(WAIT, |screen| {
        screen.contains("/ Search") && screen.contains("call.ogg")
    });
    let screen = scene.text();

    // The controls row: the keys in the accent, their words muted, the field and the chip raised.
    let controls = scene.row_of("/ Search");
    assert_eq!(scene.slice(controls, 0..12), "  / Search  ", "{screen}");
    assert_eq!(scene.cell(controls, 2).unwrap().fg, accent);
    assert_eq!(scene.cell(controls, 4).unwrap().fg, muted);
    assert_eq!(
        scene.cell(controls, 12).unwrap().bg,
        raised,
        "the search field"
    );
    assert_eq!(scene.cell(controls, 39).unwrap().bg, raised, "28 columns");
    assert_ne!(scene.cell(controls, 40).unwrap().bg, raised, "{screen}");
    assert_eq!(scene.slice(controls, 45..54), "f Status ", "{screen}");
    assert_eq!(scene.cell(controls, 45).unwrap().fg, accent);
    assert_eq!(scene.cell(controls, 47).unwrap().fg, muted);
    let chip = " All statuses ▾  ";
    assert_eq!(scene.slice(controls, 55..55 + 17), chip, "{screen}");
    assert_eq!(
        scene.cell(controls, 55).unwrap().bg,
        raised,
        "the status chip"
    );
    assert_eq!(scene.slice(controls, 80..92), "2 of 2 tasks", "{screen}");
    assert_eq!(scene.cell(controls, 80).unwrap().fg, muted);

    // The rule.
    assert_eq!(
        scene.row_text(controls + 1),
        format!("  {}  ", "─".repeat(WIDTH - 4)),
        "{screen}"
    );
    assert_eq!(scene.cell(controls + 1, 2).unwrap().fg, border);

    // The newest task, selected: its chip, its name, its date and duration, tinted to the edge.
    let interview = controls + 2;
    assert_eq!(scene.slice(interview, 0..2), " ▍", "{screen}");
    assert_eq!(scene.cell(interview, 1).unwrap().fg, accent);
    assert_eq!(scene.slice(interview, 2..13), " COMPLETED ", "{screen}");
    assert_eq!(
        scene.cell(interview, 2).unwrap().bg,
        dark(&["colors", "status", "done", "background"])
    );
    assert_eq!(
        scene.cell(interview, 3).unwrap().fg,
        dark(&["colors", "status", "done", "text"])
    );
    assert_eq!(scene.slice(interview, 15..28), "interview.mp3", "{screen}");
    assert_eq!(scene.cell(interview, 15).unwrap().fg, text);
    assert_eq!(
        scene.slice(interview, 61..80),
        "Oct 9, 2026 · 19:45",
        "{screen}"
    );
    assert_eq!(scene.cell(interview, 61).unwrap().fg, muted);
    for column in [0, WIDTH - 1] {
        assert_eq!(
            scene.cell(interview, column).unwrap().bg,
            dark(&["colors", "accentTint"])
        );
    }

    // The failed task, and its error under it.
    let call = interview + 1;
    assert_eq!(scene.slice(call, 0..15), "   FAILED      ", "{screen}");
    assert_eq!(
        scene.cell(call, 2).unwrap().bg,
        dark(&["colors", "status", "problem", "background"])
    );
    assert_ne!(
        scene.cell(call, 0).unwrap().bg,
        dark(&["colors", "accentTint"])
    );
    assert_eq!(scene.slice(call, 15..23), "call.ogg", "{screen}");
    assert_eq!(scene.slice(call, 61..80), "Oct 9, 2026 · 10:00", "{screen}");
    assert_eq!(
        scene.slice(call + 1, 17..30),
        "⚠ whisper-cli",
        "the stored error under its row:\n{screen}"
    );
    assert_eq!(
        scene.cell(call + 1, 17).unwrap().fg,
        dark(&["colors", "danger"])
    );

    // No panel around the page.
    assert!(
        (controls..scene.cells.len() - 1).all(|row| !scene.row_text(row).contains('┌')),
        "{screen}"
    );
}
