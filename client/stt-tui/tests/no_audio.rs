//! Roadmap item 67, playback's edge line: a task without stored audio (`hasAudio: false`) offers no
//! playback and says why, in the browser's words for a task with no audio.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use terminal::{EventService, Tui, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
const NO_AUDIO: &str = "This task has no audio because it ended before chunking.";

#[test]
fn a_history_row_without_audio_offers_no_playback_and_says_why() {
    // `snapshot.json` with call.ogg (task 4, FAILED) left without a stored clip.
    let mut snapshot: Value = serde_json::from_str(SNAPSHOT).unwrap();
    for task in snapshot["tasks"].as_array_mut().unwrap() {
        if task["id"] == 4 {
            task["hasAudio"] = Value::Bool(false);
        }
    }
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", &snapshot.to_string());
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"H");

    // interview.mp3 has its audio: Space plays it, and nothing about it says otherwise.
    let history = tui.wait_for(WAIT, |screen| {
        screen.contains("/ Search") && offers(screen, &["Space play"], &[])
    });
    assert_eq!(
        history.matches(NO_AUDIO).count(),
        1,
        "only call.ogg's row says it has no audio:\n{history}"
    );

    // call.ogg has none: no Space in the footer, and Space reads nothing.
    tui.press(b"j");
    tui.wait_for(WAIT, |screen| offers(screen, &["retry"], &["Space play"]));
    tui.press(b" ");
    std::thread::sleep(Duration::from_millis(500));
    assert!(!service.get_arrived(), "a task without audio reads no clip");
    assert!(!service.command_arrived(), "nor does Space send anything");
}
