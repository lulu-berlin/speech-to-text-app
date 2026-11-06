//! Roadmap item 67: renaming a speaker. The transcript lists the task's speakers above its turns;
//! Up/Down select one, `r` opens a rename prompt holding its name, Enter sends the trimmed name with
//! `PUT /api/tasks/{id}/speakers/{speakerId}`, and the speakers in the answer relabel the turns.
//! Escape closes the prompt without a request and leaves the transcript open.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
/// `turns.json`'s speakers after "Speaker 2" (id 1) is renamed to "Bob".
const RENAMED: &str =
    r#"{"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Bob"}]}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn renaming_the_selected_speaker_relabels_its_turns() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Into the transcript: history, then the completed task.
    tui.press(b"h");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        text.contains("Speakers") && text.contains("Happy to be here.")
    });

    // `r` opens the prompt with the first speaker's name; Escape closes it, nothing is sent, and the
    // transcript stays open.
    tui.press(b"r");
    tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 1"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Rename:"));
    assert!(
        screen.contains("Happy to be here."),
        "Escape in the prompt leaves the transcript open:\n{screen}"
    );
    assert!(
        !service.command_arrived(),
        "a cancelled rename sends nothing:\n{screen}"
    );

    // Down selects the second speaker; its name is replaced and sent trimmed.
    tui.press(b"\x1b[B");
    tui.press(b"r");
    tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 2"));
    tui.press(&[0x7f; "Speaker 2".len()]);
    tui.press(b"Bob \r");
    let rename = service.next_command(WAIT);
    assert!(
        rename
            .request()
            .line
            .starts_with("PUT /api/tasks/3/speakers/1 "),
        "{:?}",
        rename.request().line
    );
    assert_eq!(
        rename.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    assert_eq!(rename.request().body, r#"{"speaker":"Bob"}"#);
    rename.answer("200 OK", RENAMED);
    let screen = tui.wait_for(WAIT, |text| text.contains("Bob  00:05  Happy to be here."));
    assert!(
        !screen.contains("Speaker 2"),
        "the answer's speakers replace the old name everywhere:\n{screen}"
    );
    assert!(
        screen.contains("Speaker 1  00:00  Good morning."),
        "the other speaker keeps its name:\n{screen}"
    );
}
