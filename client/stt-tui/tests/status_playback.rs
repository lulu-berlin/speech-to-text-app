//! Roadmap item 67: a failed or canceled task's status page plays its audio when it has some, as
//! the browser's status page shows the player then; without audio it says why, as History's row
//! does. The frozen sink keeps the playhead still, so nothing here reads a clock.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, Tui, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const CLIP: &[u8] = include_bytes!("fixtures/playback.flac");
const WAIT: Duration = Duration::from_secs(20);
const NO_AUDIO: &str = "This task has no audio because it ended before chunking.";

#[test]
fn a_status_page_plays_the_tasks_audio_or_says_why_it_has_none() {
    // `snapshot.json` and one more finished task, notes.m4a, canceled before it had any audio.
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    let tasks = snapshot["tasks"].as_array_mut().unwrap();
    let mut notes = tasks.iter().find(|task| task["id"] == 4).unwrap().clone();
    notes["id"] = json!(8);
    notes["filename"] = json!("notes.m4a");
    notes["status"] = json!("CANCELED");
    notes["phase"] = json!("CHUNKING");
    notes["hasAudio"] = json!(false);
    notes["error"] = Value::Null;
    notes["transcribedUntilMs"] = Value::Null;
    notes["finishedAt"] = json!(1791536500000_i64);
    tasks.push(notes);

    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        30,
        &[("STT_AUDIO_SINK", "frozen")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &snapshot.to_string());
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("notes.m4a"));

    // call.ogg failed with its audio stored: its page offers Space, which reads the clip and plays
    // it with the player's bar; Space again pauses it.
    tui.press(b"j");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("Failed while transcribing") && offers(text, &["Space play"], &[])
    });
    tui.press(b" ");
    let audio = service.next_get_from(&tui, WAIT);
    assert!(
        audio.request().line.starts_with("GET /api/tasks/4/audio "),
        "{:?}",
        audio.request().line
    );
    audio.answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 10:00") && text.contains('⏸')
    });
    tui.press(b" ");
    tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 10:00") && text.contains('▶')
    });

    // Played again and left with `b`: the page's playback stops with it.
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains('⏸'));
    tui.press(b"b");
    let history = tui.wait_for(WAIT, |text| text.contains("/ Search"));
    assert!(
        !history.contains("♪ playing"),
        "leaving the page stops its playback:\n{history}"
    );

    // notes.m4a has no audio: its page says why, offers no Space, and Space reads nothing.
    tui.press(b"j");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("Canceled while chunking")
            && text.contains(NO_AUDIO)
            && offers(text, &["back"], &["Space play"])
    });
    tui.press(b" ");
    tui.settle();
    assert!(!service.get_arrived(), "a task without audio reads no clip");
}
