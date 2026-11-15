//! Roadmap item 67, the designs line: the queue page as `docs/design/tui/queue-dark.svg` draws it
//! at 100x30 - no outer panel; the titled upload box with the chooser line and the stored upload
//! defaults; the `Queue n tasks` header with the queue's state and its key; a rule; two-line rows
//! (name, status or phase, a twenty-column bar and the percentage; then language and size) with the
//! selected row tinted to the page's edge; and the finished tasks under a `Finished` heading. `o`
//! ("change [o]ptions") opens the settings.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Scene, Tui};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
const WIDTH: usize = 100;

/// The settings example in dark mode with new uploads in German for three speakers.
fn stored() -> String {
    let mut settings: Value =
        serde_json::from_str(include_str!("../../../docs/api/settings.json")).unwrap();
    settings["themeMode"] = json!("dark");
    settings["defaultLanguage"] = json!("de");
    settings["speakersMode"] = json!("fixed");
    settings["speakersCount"] = json!(3);
    settings.to_string()
}

fn dark(path: &[&str]) -> vt100::Color {
    token(path, "dark")
}

/// The row and column `needle` is first drawn at.
fn at(scene: &Scene, needle: &str) -> (usize, usize) {
    scene
        .position(needle)
        .unwrap_or_else(|| panic!("{needle:?} on the screen:\n{}", scene.text()))
}

#[test]
fn the_queue_page_follows_its_design() {
    let service = EventService::start();
    service.capture_startup_settings();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    // Before the settings are read the upload box states the contract's defaults.
    tui.wait_for(WAIT, |text| {
        text.contains("New uploads: language auto · speakers auto · max 8")
    });
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &stored());
    stream.send(
        "task-updated",
        r#"{"id":3,"filename":"interview.mp3","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    stream.send(
        "task-updated",
        r#"{"id":4,"filename":"call.ogg","status":"FAILED","transcriptionProgress":0.5}"#,
    );
    let tint = dark(&["colors", "accentTint"]);
    tui.wait_for_cell(WAIT, "▍", |cell| cell.bgcolor() == tint);
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains("language de · speakers 3 · max 8") && text.contains("call.ogg failed")
    });
    let screen = scene.text();
    let page = dark(&["colors", "background"]);
    let (text, muted, accent, border, done) = (
        dark(&["colors", "text"]),
        dark(&["colors", "textMuted"]),
        dark(&["colors", "accent"]),
        dark(&["colors", "border"]),
        dark(&["colors", "status", "done", "text"]),
    );

    // The upload box: two columns in from each side, accent border, its title in the text colour.
    let (choose, column) = at(&scene, "Choose audio files");
    assert_eq!(column, 7, "{screen}");
    let top = scene.row_text(choose - 1);
    assert!(top.starts_with("  ╭─ Upload ─"), "{screen}");
    assert_eq!(top.trim_end().chars().count(), WIDTH - 2, "{screen}");
    assert!(top.trim_end().ends_with('╮'), "{screen}");
    assert_eq!(scene.fg("╭─"), accent);
    assert_eq!(scene.fg("Upload ─"), text);
    assert_eq!(
        scene.slice(choose, 2..WIDTH - 3).trim_end(),
        "│ ⇪  Choose audio files  —  u or Enter, or paste / drop a path into the terminal",
        "{screen}"
    );
    assert_eq!(scene.slice(choose, WIDTH - 3..WIDTH - 2), "│", "{screen}");
    assert_eq!(scene.fg("⇪"), accent);
    assert_eq!(scene.fg("Choose audio files"), text);
    assert_eq!(scene.fg("—"), border, "the separator in the border role");
    assert_eq!(scene.fg("u or Enter"), accent);
    assert_eq!(scene.fg(" or Enter"), muted);
    assert_eq!(scene.fg(", or paste"), muted);
    assert_eq!(
        scene.bg("Choose audio files"),
        page,
        "unfocused, on the page"
    );
    let defaults = scene.row_text(choose + 1);
    assert!(
        defaults
            .starts_with("  │    New uploads: language de · speakers 3 · max 8     change options"),
        "{screen}"
    );
    assert_eq!(scene.fg("New uploads"), muted);
    assert_eq!(scene.fg("language de"), text);
    assert_eq!(scene.fg("change"), muted);
    assert_eq!(scene.fg("options"), accent, "the `o` of options");
    assert!(scene.row_text(choose + 2).starts_with("  ╰──"), "{screen}");
    assert!(scene.row_text(choose + 3).trim().is_empty(), "{screen}");

    // The header: the count, and the queue's state with its key at the right.
    let header = choose + 4;
    let line = scene.row_text(header);
    assert!(line.starts_with("  Queue 2 tasks"), "{screen}");
    assert_eq!(scene.fg("Queue 2"), text);
    assert_eq!(scene.fg("2 tasks"), muted);
    assert_eq!(
        at(&scene, "● Running   Space pause"),
        (header, WIDTH - 28),
        "{screen}"
    );
    assert_eq!(scene.fg("● Running"), done);
    assert_eq!(scene.fg("Space pause"), accent);
    assert_eq!(scene.fg(" pause"), muted);

    // The rule.
    let rule = scene.row_text(header + 1);
    assert_eq!(rule, format!("  {}  ", "─".repeat(WIDTH - 4)), "{screen}");
    assert_eq!(scene.cell(header + 1, 2).unwrap().fg, border);

    // The running, selected row: its bar, the phase in accent, its percentage, tinted to the edge.
    let lecture = header + 2;
    assert_eq!(scene.slice(lecture, 0..13), " ▍lecture.m4a", "{screen}");
    assert_eq!(scene.cell(lecture, 1).unwrap().fg, accent);
    assert!(
        scene.slice(lecture, 38..54) == "Transcribing… · ",
        "the phase at the status column:\n{screen}"
    );
    assert_eq!(scene.cell(lecture, 38).unwrap().fg, accent);
    assert_eq!(
        scene.slice(lecture, 68..88),
        format!("{}╸{}", "━".repeat(8), "─".repeat(11)),
        "42% of twenty columns:\n{screen}"
    );
    assert_eq!(scene.cell(lecture, 68).unwrap().fg, accent);
    assert_eq!(scene.cell(lecture, 80).unwrap().fg, border);
    assert_eq!(scene.slice(lecture, 90..96), "   42%", "{screen}");
    assert_eq!(scene.cell(lecture, 95).unwrap().fg, text);
    assert_eq!(
        scene.slice(lecture + 1, 0..14),
        "  de · 57.7 MB",
        "{screen}"
    );
    assert_eq!(scene.cell(lecture + 1, 2).unwrap().fg, muted);
    for row in [lecture, lecture + 1] {
        for column in [0, WIDTH - 1] {
            assert_eq!(scene.cell(row, column).unwrap().bg, tint, "{row}:{column}");
        }
    }

    // The queued row: the status chip in the pending group's colours, an empty track, 0% muted.
    let memo = lecture + 2;
    assert_eq!(scene.slice(memo, 0..10), "  memo.wav", "{screen}");
    assert_eq!(scene.cell(memo, 2).unwrap().fg, text);
    assert_eq!(scene.cell(memo, 0).unwrap().bg, page, "not selected");
    assert_eq!(scene.slice(memo, 38..46), " QUEUED ", "{screen}");
    for column in 38..46 {
        assert_eq!(
            scene.cell(memo, column).unwrap().bg,
            dark(&["colors", "status", "pending", "background"])
        );
    }
    assert_eq!(
        scene.cell(memo, 39).unwrap().fg,
        dark(&["colors", "status", "pending", "text"])
    );
    assert_eq!(scene.slice(memo, 68..88), "─".repeat(20), "{screen}");
    assert_eq!(scene.slice(memo, 90..96), "    0%", "{screen}");
    assert_eq!(scene.cell(memo, 95).unwrap().fg, muted);
    assert_eq!(scene.slice(memo + 1, 0..15), "  auto · 3.1 MB", "{screen}");

    // The finished tasks under their heading, the newest first.
    assert!(scene.row_text(memo + 2).trim().is_empty(), "{screen}");
    assert!(
        scene.row_text(memo + 3).starts_with("  Finished"),
        "{screen}"
    );
    assert!(
        scene
            .row_text(memo + 4)
            .starts_with("  ✗ call.ogg failed — see History"),
        "{screen}"
    );
    assert!(
        scene
            .row_text(memo + 5)
            .starts_with("  ✓ interview.mp3 transcribed — Enter open"),
        "{screen}"
    );
    assert_eq!(scene.fg("✗"), dark(&["colors", "danger"]));
    assert_eq!(scene.fg("✓"), done);
    assert_eq!(scene.fg("interview.mp3"), text);
    assert_eq!(scene.fg("transcribed"), muted);
    assert_eq!(scene.fg("Enter open"), accent);
    assert!(
        scene.row_text(memo + 6).trim().is_empty(),
        "nothing under the notices:\n{screen}"
    );

    // The queue paused: its state muted, Space resumes it.
    stream.send("queue-updated", r#"{"paused":true,"taskIds":[5,7]}"#);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("● Paused    Space resume"));
    assert_eq!(at(&scene, "● Paused"), (header, WIDTH - 28));
    assert_eq!(scene.fg("● Paused"), muted);

    // `o` - "change options" - opens the settings: the settings read is answered with the
    // contract's defaults, the frontends read here.
    tui.press(b"o");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", include_str!("../../../docs/api/frontends.json"));
    tui.wait_for(WAIT, |text| text.contains("Restore default settings"));
}
