//! Roadmap item 67, third amendment: destructive actions confirm in one dialog style, the browser's
//! `ConfirmDialog` - a title asking the question, one sentence of what follows, and the confirm
//! named beside Escape's "Cancel". Deleting a task and cancelling a started one ask on the queue;
//! merging speakers, and reassigning in a task with merges, ask in a transcript (the user chose
//! these four for this cycle, 2026-11-09; restoring the default settings joins with its own line).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{EventService, OpenTranscript, Tui, open_transcript};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// `turns.json`'s speakers after "Speaker 1" (id 0) is merged into "Speaker 2" (id 1).
const MERGED: &str = r#"{"speakers":[{"speakerId":1,"speaker":"Speaker 2"}]}"#;
/// `turns.json` after the same merge: one speaker, and merges to reset.
const MERGED_TURNS: &str = r#"{"hasMerges":true,"speakers":[{"speakerId":1,"speaker":"Speaker 2"}],"turns":[{"speakerId":1,"startMs":0,"endMs":14250,"text":"Good morning. Thanks for joining us today. Happy to be here. Let's start with your background.","segments":[{"id":1,"startMs":0,"endMs":2100,"text":"Good morning.","edited":false},{"id":2,"startMs":2400,"endMs":5200,"text":"Thanks for joining us today.","edited":false},{"id":3,"startMs":5800,"endMs":9100,"text":"Happy to be here.","edited":false},{"id":4,"startMs":9600,"endMs":14250,"text":"Let's start with your background.","edited":false}]}]}"#;
const WAIT: Duration = Duration::from_secs(20);

/// Waits for a dialog with `title`, `body` and `confirm`, in the one style: the title, the
/// sentence, and a keys line naming Enter's confirm beside Escape's "Cancel".
fn dialog(tui: &Tui, title: &str, body: &str, confirm: &str) -> String {
    let screen = tui.wait_for(WAIT, |text| text.contains(title));
    assert!(
        screen.contains(body),
        "the dialog says what follows:\n{screen}"
    );
    let keys = screen
        .lines()
        .find(|line| line.contains("Enter") && line.contains(confirm))
        .unwrap_or_else(|| panic!("Enter names {confirm:?}:\n{screen}"));
    assert!(
        keys.contains("Esc") && keys.contains("Cancel"),
        "Escape's Cancel beside it: {keys:?}"
    );
    screen
}

#[test]
fn deleting_and_cancelling_a_started_task_ask_in_the_dialog() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("lecture.m4a") && text.contains("Live")
    });

    // `c` on the running lecture.m4a asks first; Escape keeps it running, and nothing is sent.
    tui.press(b"c");
    dialog(
        &tui,
        "Cancel lecture.m4a?",
        "Stops the task; it can be retried from History.",
        "Cancel task",
    );
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Cancel lecture.m4a?"));
    tui.settle();
    assert!(!service.command_arrived(), "Escape sends nothing");

    // Asked again, Enter cancels it.
    tui.press(b"c");
    dialog(
        &tui,
        "Cancel lecture.m4a?",
        "retried from History",
        "Cancel task",
    );
    tui.press(b"\r");
    let cancel = service.next_command(WAIT);
    assert!(
        cancel
            .request()
            .line
            .starts_with("POST /api/tasks/5/cancel "),
        "{:?}",
        cancel.request().line
    );

    // Deleting memo.wav asks in the same dialog.
    tui.press(b"j");
    tui.press(b"\x7f");
    dialog(
        &tui,
        "Delete memo.wav?",
        "Removes the task, its transcript and audio for good.",
        "Delete task",
    );
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete memo.wav?"));

    // memo.wav never started: `c` cancels it at once, as the browser does.
    tui.press(b"c");
    let cancel = service.next_command(WAIT);
    assert!(
        cancel
            .request()
            .line
            .starts_with("POST /api/tasks/7/cancel "),
        "{:?}",
        cancel.request().line
    );
    assert!(!tui.text().contains("Cancel memo.wav?"), "{}", tui.text());
}

#[test]
fn merging_and_reassigning_in_a_task_with_merges_ask_in_the_dialog() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // `m` marks Speaker 1; `m` on Speaker 2's segment asks, naming who is folded into whom.
    tui.press(b"m");
    tui.wait_for(WAIT, |text| text.contains("Merging Speaker 1"));
    tui.press(b"l");
    tui.press(b"l");
    tui.press(b"m");
    dialog(
        &tui,
        "Merge speakers?",
        "Merge Speaker 1 into Speaker 2? Their turns join Speaker 2; you can undo this.",
        "Merge",
    );
    tui.press(b"\r");
    let merge = service.next_command(WAIT);
    assert!(
        merge
            .request()
            .line
            .starts_with("POST /api/tasks/3/speakers/merge "),
        "{:?}",
        merge.request().line
    );
    merge.answer("200 OK", MERGED);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", MERGED_TURNS);
    tui.wait_for(WAIT, |text| !text.contains("Speaker 1"));

    // With merges, reassigning a segment to a new speaker asks first: the merges become
    // permanent. The cursor kept its place through the re-read, on "Happy to be here." (id 3),
    // where `l l` left it.
    tui.press(b"a");
    tui.wait_for(WAIT, |text| text.contains("1 segment selected"));
    tui.press(b"n");
    dialog(
        &tui,
        "Reassign speakers?",
        "makes those merges permanent",
        "Move",
    );
    tui.press(b"\r");
    let reassign = service.next_command(WAIT);
    let body: Value = serde_json::from_str(&reassign.request().body).unwrap();
    assert_eq!(body, json!({ "segmentIds": [3], "speakerId": null }));
    // Each waited for its answer: one merge and one reassignment went out, none before.
    tui.settle();
    assert!(
        !service.command_arrived(),
        "the merge and the reassignment each waited for the answer"
    );
}

#[test]
fn escaping_the_reassign_confirmation_sends_nothing_and_keeps_the_selection() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // Merge Speaker 1 into Speaker 2 first: with merges, a reassignment asks before it is sent.
    tui.press(b"m");
    tui.wait_for(WAIT, |text| text.contains("Merging Speaker 1"));
    tui.press(b"l");
    tui.press(b"l");
    tui.press(b"m");
    dialog(
        &tui,
        "Merge speakers?",
        "Merge Speaker 1 into Speaker 2?",
        "Merge",
    );
    tui.press(b"\r");
    service.next_command(WAIT).answer("200 OK", MERGED);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", MERGED_TURNS);
    tui.wait_for(WAIT, |text| !text.contains("Speaker 1"));

    // Mark the cursor's turn and ask for a new speaker: the confirmation comes up.
    tui.press(b"A");
    tui.wait_for(WAIT, |text| text.contains("4 segments selected"));
    tui.press(b"n");
    dialog(
        &tui,
        "Reassign speakers?",
        "makes those merges permanent",
        "Move",
    );

    // Escape closes it with nothing sent, and the selection stays for another try.
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| {
        !text.contains("Reassign speakers?") && text.contains("4 segments selected")
    });
    assert!(
        !service.command_arrived(),
        "a cancelled reassignment sends nothing:\n{screen}"
    );
}
