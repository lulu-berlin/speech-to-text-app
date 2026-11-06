//! Roadmap item 67: history and the transcript. `h` lists the completed tasks from
//! `GET /api/tasks`; Enter opens the selected one's turns (`GET /api/tasks/{id}/turns`) and shows
//! each turn's speaker and text; `b` goes back. Both reads carry the per-install secret.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn history_lists_completed_tasks_and_opens_a_transcript_with_its_speaker_turns() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // `h`: the completed tasks, from GET /api/tasks.
    tui.press(b"h");
    let list = service.next_get(WAIT);
    assert!(
        list.request().line.starts_with("GET /api/tasks "),
        "{:?}",
        list.request().line
    );
    assert_eq!(
        list.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    list.answer("200 OK", TASK_LIST);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    assert!(
        !screen.contains("lecture.m4a"),
        "only completed tasks:\n{screen}"
    );
    assert!(
        !screen.contains("memo.wav"),
        "only completed tasks:\n{screen}"
    );

    // Enter: the transcript's turns.
    tui.press(b"\r");
    let turns = service.next_get(WAIT);
    assert!(
        turns.request().line.starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        turns.request().line
    );
    assert_eq!(
        turns.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    turns.answer("200 OK", TURNS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Speaker 1") && text.contains("Good morning. Thanks for joining us today.")
    });
    assert!(screen.contains("Speaker 2"), "{screen}");
    assert!(screen.contains("Happy to be here."), "{screen}");
    assert!(
        screen.contains("interview.mp3"),
        "the transcript names its task:\n{screen}"
    );

    // `b` back to the history, `b` back to the queue.
    tui.press(b"b");
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && !text.contains("Happy to be here.")
    });
    tui.press(b"b");
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && !text.contains("interview.mp3")
    });
}
