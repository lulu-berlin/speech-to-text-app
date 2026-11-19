//! Roadmap item 67: `A` marks every segment of the cursor's turn - the browser's per-turn
//! selection - and marks them off again when they all are; `n` then moves them all to a new
//! speaker in one request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, open_transcript};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn marking_a_whole_turn_marks_its_segments_and_moves_them_together() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    tui.wait_for(WAIT, |text| text.contains("Good morning."));

    // A on the first segment marks both segments of its turn: the panel's row says so, and the
    // guidance counts them.
    tui.press(b"A");
    tui.wait_for(WAIT, |text| {
        text.contains("Speaker 1 (selected)") && text.contains("2 segments selected")
    });

    // A again marks them off.
    tui.press(b"A");
    tui.wait_for(WAIT, |text| {
        !text.contains("(selected)")
            && !text.contains("segments selected")
            && text.contains("Good morning.")
    });

    // A once more, and n moves every segment of the turn to a new speaker in one request.
    tui.press(b"A");
    tui.wait_for(WAIT, |text| text.contains("2 segments selected"));
    tui.press(b"n");
    let reassign = service.next_command(WAIT);
    assert!(
        reassign
            .request()
            .line
            .starts_with("POST /api/tasks/3/speakers/reassign "),
        "{:?}",
        reassign.request().line
    );
    let body: Value = serde_json::from_str(&reassign.request().body).unwrap();
    assert_eq!(body["speakerId"], Value::Null, "{body}");
    assert_eq!(body["segmentIds"], json!([1, 2]), "{body}");
    reassign.answer(
        "200 OK",
        r#"{"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Speaker 2"},{"speakerId":2,"speaker":"Speaker 3"}]}"#,
    );
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
}
