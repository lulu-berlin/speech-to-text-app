//! Roadmap item 67: a transcript's speakers. The transcript lists the task's speakers above its
//! turns; Up/Down select one. `r` opens a rename prompt holding its name, Enter sends the trimmed
//! name with `PUT /api/tasks/{id}/speakers/{speakerId}`, and the speakers in the answer relabel the
//! turns; Escape closes the prompt without a request and leaves the transcript open. `m` marks the
//! selected speaker for a merge (again on it, unmarks it); `m` on another speaker merges the marked
//! one into it with `POST /api/tasks/{id}/speakers/merge`, and the transcript's turns are fetched
//! anew, since a merge joins them.

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
/// `turns.json`'s speakers after "Speaker 1" (id 0) is merged into "Speaker 2" (id 1).
const MERGED: &str = r#"{"speakers":[{"speakerId":1,"speaker":"Speaker 2"}]}"#;
/// `turns.json` after the same merge: every segment is Speaker 2's, so the three turns join into one.
const MERGED_TURNS: &str = r#"{"hasMerges":true,"speakers":[{"speakerId":1,"speaker":"Speaker 2"}],"turns":[{"speakerId":1,"startMs":0,"endMs":14250,"text":"Good morning. Thanks for joining us today. Happy to be here. Let's start with your background.","segments":[{"id":1,"startMs":0,"endMs":2100,"text":"Good morning.","edited":false},{"id":2,"startMs":2400,"endMs":5200,"text":"Thanks for joining us today.","edited":false},{"id":3,"startMs":5800,"endMs":9100,"text":"Happy to be here.","edited":false},{"id":4,"startMs":9600,"endMs":14250,"text":"Let's start with your background.","edited":false}]}]}"#;
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

#[test]
fn merging_the_marked_speaker_into_the_selected_one_refetches_the_turns() {
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

    // `m` marks the selected speaker; `m` on it again unmarks it, and nothing is sent.
    tui.press(b"m");
    tui.wait_for(WAIT, |text| text.contains("Speaker 1 (merging)"));
    tui.press(b"m");
    let screen = tui.wait_for(WAIT, |text| !text.contains("(merging)"));
    assert!(
        !service.command_arrived(),
        "unmarking sends nothing:\n{screen}"
    );

    // Marked, then `m` on the second speaker merges the first into it.
    tui.press(b"m");
    tui.wait_for(WAIT, |text| text.contains("Speaker 1 (merging)"));
    tui.press(b"\x1b[B");
    tui.press(b"m");
    let merge = service.next_command(WAIT);
    assert!(
        merge
            .request()
            .line
            .starts_with("POST /api/tasks/3/speakers/merge "),
        "{:?}",
        merge.request().line
    );
    assert_eq!(
        merge.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    assert_eq!(merge.request().body, r#"{"group":[0,1],"into":1}"#);
    merge.answer("200 OK", MERGED);

    // The merge joins turns, so the transcript's turns are read again.
    let refetch = service.next_get(WAIT);
    assert!(
        refetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        refetch.request().line
    );
    assert_eq!(
        refetch.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    refetch.answer("200 OK", MERGED_TURNS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains(
            "Speaker 2  00:00  Good morning. Thanks for joining us today. Happy to be here.",
        )
    });
    assert!(
        !screen.contains("Speaker 1") && !screen.contains("(merging)"),
        "only the surviving speaker is left:\n{screen}"
    );
}
