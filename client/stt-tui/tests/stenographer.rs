//! Roadmap item 67, second amendment: a stenographer placeholder. A 3-row tile sits at the left of a
//! 3-row header, beside the product name and the tabs; it moves while a task is RUNNING and rests on
//! a still frame otherwise, and a larger one shows on the empty queue and history. The tile is a
//! rounded box (`╭` over `╰`); the panels' corners are square, so a rounded corner is the tile.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use terminal::{EventService, Scene, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// A service with nothing in it: no tasks, an empty queue.
const EMPTY: &str = r#"{"tasks":[],"queue":{"paused":false,"taskIds":[]}}"#;
const PRODUCT: &str = "Speech-to-Text";
const WAIT: Duration = Duration::from_secs(20);

/// The fixture snapshot with its running task paused: nothing runs.
fn paused() -> String {
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    for task in snapshot["tasks"].as_array_mut().unwrap() {
        if task["status"] == "RUNNING" {
            task["status"] = "PAUSED".into();
            task["phase"] = Value::Null;
        }
    }
    snapshot.to_string()
}

/// The fixture snapshot without its finished tasks: the queue alone, so History lists nothing.
fn unfinished() -> String {
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    snapshot["tasks"]
        .as_array_mut()
        .unwrap()
        .retain(|task| task["status"] != "COMPLETED" && task["status"] != "FAILED");
    snapshot.to_string()
}

/// The column `needle` starts at on `line`.
fn column_of(line: &str, needle: &str) -> Option<usize> {
    line.find(needle).map(|byte| line[..byte].chars().count())
}

/// The header's first three rows, cut at `width` columns: the tile left of the product name.
fn tile(text: &str, width: usize) -> String {
    text.lines()
        .take(3)
        .map(|line| line.chars().take(width).collect::<String>())
        .collect::<Vec<_>>()
        .join("\n")
}

/// The tallest rounded box below the header: its `╭` row and column, and its `╰` row under it.
fn body_tile(scene: &Scene) -> Option<(usize, usize, usize)> {
    let rows = scene.cells.len();
    let mut tallest: Option<(usize, usize, usize)> = None;
    for top in 3..rows {
        for (column, cell) in scene.cells[top].iter().enumerate() {
            if cell.text != "╭" {
                continue;
            }
            if let Some(bottom) = (top + 1..rows).find(|row| {
                scene.cells[*row]
                    .get(column)
                    .is_some_and(|cell| cell.text == "╰")
            }) && tallest
                .is_none_or(|(old_top, _, old_bottom)| bottom - top > old_bottom - old_top)
            {
                tallest = Some((top, column, bottom));
            }
        }
    }
    tallest
}

/// The height of the tallest rounded box below the header, 0 without one.
fn body_tile_rows(scene: &Scene) -> usize {
    body_tile(scene).map_or(0, |(top, _, bottom)| bottom - top + 1)
}

/// The columns from a large tile's left edge that hold it and its pen and paper.
const LARGE_COLUMNS: usize = 20;

/// The large tile's rows `top..=bottom` from `column`, with its pen and paper beside it.
fn large(text: &str, (top, column, bottom): (usize, usize, usize)) -> String {
    text.lines()
        .skip(top)
        .take(bottom - top + 1)
        .map(|line| {
            line.chars()
                .skip(column)
                .take(LARGE_COLUMNS)
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn the_stenographer_types_while_a_task_runs_and_rests_otherwise() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(PRODUCT) && text.contains("lecture.m4a")
    });

    // A 3-row header: the tile at its left, the product name on its first row, the tabs on its
    // second, both right of the tile.
    let text = scene.text();
    let width = column_of(&scene.row_text(0), PRODUCT)
        .unwrap_or_else(|| panic!("the product name is on the first row:\n{text}"));
    let head = tile(&text, width);
    let rows: Vec<&str> = head.lines().collect();
    assert!(
        rows[0].contains('╭') && rows[2].contains('╰'),
        "a 3-row tile left of the product name:\n{text}"
    );
    assert!(
        column_of(&scene.row_text(1), "Queue").is_some_and(|column| column >= width),
        "the tabs are on the header's second row, beside the tile:\n{text}"
    );

    // While lecture.m4a runs the tile moves.
    tui.wait_for(Duration::from_secs(5), |text| tile(text, width) != head);

    // Paused, nothing runs: the tile rests on one frame.
    stream.send("snapshot", &paused());
    tui.wait_for(WAIT, |text| {
        text.contains("PAUSED") && !text.contains("RUNNING")
    });
    let resting = tile(&tui.text(), width);
    std::thread::sleep(Duration::from_millis(1500));
    assert_eq!(
        tile(&tui.text(), width),
        resting,
        "with nothing running the tile rests"
    );

    // The empty queue and the empty history show a larger one.
    stream.send("snapshot", EMPTY);
    let queue = tui.wait_for_scene(WAIT, |text| text.contains("No tasks yet"));
    assert!(
        body_tile_rows(&queue) >= 5,
        "the empty queue shows a larger tile:\n{}",
        queue.text()
    );
    tui.press(b"2");
    let history = tui.wait_for_scene(WAIT, |text| text.contains("No completed tasks yet"));
    assert!(
        body_tile_rows(&history) >= 5,
        "the empty history shows a larger tile:\n{}",
        history.text()
    );
}

/// Roadmap item 67 (the user's question 2026-11-09, "browser's rules"): the large tile follows the
/// browser - on the empty queue it always types (nothing runs there; item 59's charm), on the empty
/// History it rests even while a task runs.
#[test]
fn the_large_tile_types_on_the_empty_queue_and_rests_on_the_empty_history() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &unfinished());
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(PRODUCT) && text.contains("lecture.m4a")
    });
    let width = column_of(&scene.row_text(0), PRODUCT).unwrap();

    // The empty History while lecture.m4a runs: once the header's tile has moved, the large one is
    // still on the frame it was drawn with.
    tui.press(b"2");
    let history = tui.wait_for_scene(WAIT, |text| text.contains("No completed tasks yet"));
    let place = body_tile(&history)
        .unwrap_or_else(|| panic!("the empty history's tile:\n{}", history.text()));
    let resting = large(&history.text(), place);
    let head = tile(&history.text(), width);
    // The header's tile moved, on a page drawn down to its text: a screen still being repainted
    // can show the header before the body.
    let moved = tui.wait_for(Duration::from_secs(5), |text| {
        tile(text, width) != head && text.contains("No completed tasks yet")
    });
    assert_eq!(
        large(&moved, place),
        resting,
        "the empty history's tile rests while a task runs"
    );

    // The empty queue: nothing runs, and its tile types all the same.
    stream.send("snapshot", EMPTY);
    tui.press(b"1");
    let queue = tui.wait_for_scene(WAIT, |text| text.contains("No tasks yet"));
    let place =
        body_tile(&queue).unwrap_or_else(|| panic!("the empty queue's tile:\n{}", queue.text()));
    let first = large(&queue.text(), place);
    tui.wait_for(Duration::from_secs(5), |text| large(text, place) != first);
}
