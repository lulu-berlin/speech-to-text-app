//! Roadmap item 67 (the user, 2026-11-12: "make sure that none appear when it cannot be used"): the
//! queue's footer offers `pause` or `resume` and `cancel` as the selected task's status allows,
//! `reorder` for a task with a neighbour, `on the upload area` only while it does, and `move` only
//! with a row; `theme` waits for the stored settings it cycles from. The queue's own `Space pause`
//! or `Space resume` is its header's (the design pass, 2026-11-15), not while the upload area has
//! the focus.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use terminal::{EventService, Tui, WIDE, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = include_str!("../../../docs/api/settings.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_queue_footer_offers_a_key_only_while_it_can_act() {
    let service = EventService::start();
    service.capture_startup_settings();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), WIDE, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    // The running lecture.m4a is selected, memo.wav waits behind it; the settings are not read yet,
    // so `t` has no stored mode to cycle from.
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &[
                "↑↓/j/k move",
                "Shift-↑↓ or J/K reorder",
                "pause",
                "cancel",
                "upload",
            ],
            &[
                "theme",
                "p resume",
                "Space queue pause",
                "Space queue resume",
                "Enter/Space on the upload area",
            ],
        ) && header(screen).contains("Space pause")
    });

    // The stored settings arrive: `theme` joins.
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |screen| offers(screen, &["theme"], &[]));

    // On the upload area Enter and Space open the dialog; the task and queue keys do not act.
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["Enter/Space on the upload area", "upload"],
            &[
                "pause",
                "cancel",
                "Shift-↑↓ or J/K reorder",
                "Space queue pause",
            ],
        ) && !header(screen).contains("Space")
    });
    tui.press(b"\x1b[B");

    // The running task paused: `p` resumes it now.
    let snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    let mut paused = snapshot["tasks"][0].clone();
    paused["status"] = Value::from("PAUSED");
    stream.send("task-updated", &paused.to_string());
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["p resume", "cancel"], &["pause"])
    });

    // The queue paused: Space resumes it.
    stream.send("queue-updated", r#"{"paused":true,"taskIds":[5,7]}"#);
    tui.wait_for(WAIT, |screen| header(screen).contains("Space resume"));
}

/// The queue's header row: `Queue n tasks` and the queue's state with its key.
fn header(screen: &str) -> &str {
    screen
        .lines()
        .find(|line| line.trim_start().starts_with("Queue ") && line.contains("task"))
        .unwrap_or_default()
}

#[test]
fn an_empty_queue_offers_no_row_keys() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), WIDE, 24);
    let mut stream = service.next_stream(WAIT);
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    snapshot["queue"]["taskIds"] = Value::from(Vec::<i64>::new());
    stream.send("snapshot", &snapshot.to_string());
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["upload"],
            &["↑↓/j/k move", "Shift-↑↓ or J/K reorder", "pause", "cancel"],
        ) && header(screen).contains("Space pause")
    });
}
