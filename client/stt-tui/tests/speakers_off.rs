//! Roadmap item 67, third amendment: with speakers off the transcript shows no speaker names and
//! no speaker actions - no panel, no rename or merge, nothing to mark for a reassignment - while
//! reading and editing work.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// `turns.json` as the service answers a task transcribed with speakers off: no speakers at all,
/// and every turn's speaker null.
fn speakers_off() -> Value {
    let mut turns: Value = serde_json::from_str(TURNS).unwrap();
    turns["speakers"] = json!([]);
    for turn in turns["turns"].as_array_mut().unwrap() {
        turn["speakerId"] = Value::Null;
    }
    turns
}

#[test]
fn with_speakers_off_no_names_or_speaker_actions_show_and_reading_and_editing_work() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // History's interview.mp3 opened; its turns come with no speakers at all.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service
        .next_get(WAIT)
        .answer("200 OK", &speakers_off().to_string());
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Transcript: interview.mp3") && text.contains("Good morning.")
    });

    // Reading: the segments show with no name before them, and the cursor moves between them.
    assert!(!screen.contains("Speaker 1"), "{screen}");
    assert!(!screen.contains("Speaker 2"), "{screen}");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"\x1b[B");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    let screen = tui.text();

    // No speaker actions: no panel, no rename or merge key, nothing to mark for a reassignment.
    assert!(!screen.contains("Speakers"), "no speakers panel:\n{screen}");
    assert!(!screen.contains("[r]ename"), "no rename:\n{screen}");
    assert!(!screen.contains("[m]erge"), "no merge:\n{screen}");
    assert!(!screen.contains("m[a]rk"), "nothing to mark:\n{screen}");
    assert!(!screen.contains("[A]ll of the turn"), "{screen}");

    // Editing still works: `e` opens the prompt, Enter sends the PUT, and the answer relabels it.
    tui.press(b"e");
    tui.wait_for(WAIT, |text| {
        text.contains("Segment text: Thanks for joining us today.")
    });
    tui.press(b"!");
    tui.press(b"\r");
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/2 "),
        "{:?}",
        edit.request().line
    );
    let mut edited = speakers_off();
    edited["turns"][0]["segments"][1]["text"] = json!("Thanks for joining us today.!");
    edited["turns"][0]["segments"][1]["edited"] = json!(true);
    edit.answer("200 OK", &edited.to_string());
    tui.wait_for(WAIT, |text| text.contains("Thanks for joining us today.!"));
}
