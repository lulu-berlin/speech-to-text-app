//! Roadmap item 67: a queue row moves to the top or the bottom as well as one step at a time -
//! Shift-Home and Shift-End, beside Shift-↑↓ and J/K - and a reorder the service refuses leaves the
//! order as it was and shows its message.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

/// The contract's `snapshot`, with a third queued task: lecture.m4a, memo.wav and third.wav.
fn three_queued() -> String {
    let mut snapshot: Value =
        serde_json::from_str(include_str!("../../../docs/api/snapshot.json")).unwrap();
    let mut third = snapshot["tasks"][1].clone();
    third["id"] = json!(9);
    third["filename"] = json!("third.wav");
    snapshot["tasks"].as_array_mut().unwrap().push(third);
    snapshot["queue"]["taskIds"] = json!([5, 7, 9]);
    snapshot.to_string()
}

const WAIT: Duration = Duration::from_secs(20);

/// The order the queue page shows, by file name: the names as their rows come down the screen.
fn order(screen: &str) -> Vec<String> {
    let names = ["lecture.m4a", "memo.wav", "third.wav"];
    screen
        .lines()
        .filter_map(|line| {
            names
                .iter()
                .find(|name| line.contains(**name))
                .map(|name| name.to_string())
        })
        .collect()
}

#[test]
fn a_row_moves_to_the_top_or_the_bottom_and_a_refused_reorder_keeps_the_order() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &three_queued());
    tui.wait_for(WAIT, |text| text.contains("third.wav"));

    // Down, then Up: the rows take the keyboard (the upload area has it first), on the first row.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[A");

    // Shift-End on the first row sends it to the bottom.
    tui.press(b"\x1b[1;2F");
    let reorder = service.next_command(WAIT);
    assert!(
        reorder.request().line.starts_with("PUT /api/queue/order "),
        "{:?}",
        reorder.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&reorder.request().body).unwrap(),
        json!({ "taskIds": [7, 9, 5] }),
        "{}",
        reorder.request().body
    );
    reorder.answer("200 OK", r#"{"paused":false,"taskIds":[7,9,5]}"#);
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[7,9,5]}"#);
    tui.wait_for(WAIT, |text| {
        order(text) == ["memo.wav", "third.wav", "lecture.m4a"]
    });

    // The cursor is a row, and the rows moved under it: down twice reaches lecture.m4a at the
    // bottom, where Shift-Home takes it back to the top.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[1;2H");
    let reorder = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&reorder.request().body).unwrap(),
        json!({ "taskIds": [5, 7, 9] }),
        "{}",
        reorder.request().body
    );
    reorder.answer("200 OK", r#"{"paused":false,"taskIds":[5,7,9]}"#);
    stream.send("queue-updated", r#"{"paused":false,"taskIds":[5,7,9]}"#);
    tui.wait_for(WAIT, |text| {
        order(text) == ["lecture.m4a", "memo.wav", "third.wav"]
    });

    // Refused: up twice puts the cursor on lecture.m4a again; Shift-End asks to send it down, the
    // service refuses, and the order stays.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[1;2F");
    let refused = service.next_command(WAIT);
    refused.answer(
        "409 Conflict",
        r#"{"error":"conflict","message":"the queue changed elsewhere"}"#,
    );
    let screen = tui.wait_for(WAIT, |text| text.contains("the queue changed elsewhere"));
    assert_eq!(
        order(&screen),
        ["lecture.m4a", "memo.wav", "third.wav"],
        "a refused reorder leaves the order:\n{screen}"
    );
}
