//! Roadmap item 67 (the user, 2026-11-11: "there's revert, but it should be inside the edit dialog
//! too"): in a segment's edit prompt `Ctrl-R` reverts the segment to whisper's text and keeps the
//! prompt open with that text, as the browser's editor offers the revert there.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, open_transcript_with};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn ctrl_r_in_the_edit_prompt_reverts_the_segment_and_keeps_the_prompt_open() {
    // "Good morning." was edited to "Good morning!".
    let mut edited: Value = serde_json::from_str(TURNS).unwrap();
    edited["turns"][0]["segments"][0]["text"] = json!("Good morning!");
    edited["turns"][0]["segments"][0]["edited"] = json!(true);
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_with(&edited.to_string());

    // A draft under way; Ctrl-R reverts the segment instead of typing.
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning!"));
    tui.press(b"?");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning!?"));
    tui.press(b"\x12");
    let revert = service.next_command(WAIT);
    assert!(
        revert
            .request()
            .line
            .starts_with("POST /api/tasks/3/segments/1/revert "),
        "{:?}",
        revert.request().line
    );
    revert.answer("200 OK", TURNS);

    // The prompt stays open, holding whisper's text.
    let reverted = tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    assert!(
        !reverted.contains("Segment text: Good morning.r"),
        "Ctrl-R types nothing:\n{reverted}"
    );

    // Nothing is left to revert: Ctrl-R again sends nothing and types nothing.
    tui.press(b"\x12");
    std::thread::sleep(Duration::from_millis(500));
    assert!(
        !service.command_arrived(),
        "an unedited segment has nothing to revert"
    );
    let screen = tui.text();
    assert!(
        screen.contains("Segment text: Good morning.") && !screen.contains("Good morning.r"),
        "the draft is unchanged:\n{screen}"
    );

    // Enter on whisper's own text closes the prompt and sends nothing.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| !text.contains("Segment text:"));
    assert!(
        !service.command_arrived(),
        "an unchanged edit sends nothing"
    );
}
