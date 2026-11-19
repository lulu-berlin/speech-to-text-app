//! Roadmap item 67, the review (R11): an answer that arrives after the user left its screen does
//! not bring that screen back. A transcript's turns and the settings read come in late, after the
//! user went on to History; History stays. The late settings still store what they say - the
//! theme they carry is drawn - which is how the test knows both answers are in.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::path::Path;
use std::time::Duration;

use serde_json::json;
use settings_fixture::{FRONTENDS, SETTINGS, stored};
use terminal::{EventService, EventStream, Fetch, Tui, history_row};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);
/// A read the service cannot answer.
const BROKEN: &str = r#"{"error":"internal","message":"the database is locked"}"#;

/// The TUI on its queue, connected to a service that sent the snapshot; the state directory and the
/// event stream are kept for as long as the test holds the third value.
fn start() -> (EventService, Tui, (tempfile::TempDir, EventStream)) {
    start_in(None)
}

/// As [`start`], with the TUI working in `folder` - where its upload dialog opens - when given.
fn start_in(folder: Option<&Path>) -> (EventService, Tui, (tempfile::TempDir, EventStream)) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let program: &Path = env!("CARGO_BIN_EXE_stt-tui").as_ref();
    let tui = match folder {
        Some(folder) => Tui::start_in(program, state.path(), 120, 30, folder),
        None => Tui::start(program, state.path(), 120, 30),
    };
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Theme: dark")
    });
    (service, tui, (state, stream))
}

/// History, then interview.mp3's transcript, whose turns arrive at once.
fn open_interview(service: &EventService, tui: &mut Tui) {
    tui.press(b"H");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());
    tui.press(b"\r");
    service.next_get_from(tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));
}

/// Answers the held settings read with the light theme, then the frontends read the page asks
/// next - only both together complete the answer - and returns the screen once the header draws
/// the light theme.
fn answer_late_settings(settings: Fetch, service: &EventService, tui: &Tui) -> String {
    settings.answer("200 OK", &stored(SETTINGS, json!({ "themeMode": "light" })));
    service.next_get_from(tui, WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Theme: light"))
}

#[test]
fn answers_that_arrive_after_the_user_left_do_not_bring_their_screens_back() {
    let (service, mut tui, _connection) = start();

    // interview.mp3's transcript is asked for; before its turns come, the user goes to the queue.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());
    tui.press(b"\r");
    let turns = service.next_get_from(&tui, WAIT);
    tui.press(b"Q");
    tui.wait_for(WAIT, |text| text.contains("Queue 2 tasks"));

    // The settings are asked for; before they come, the user goes to History.
    service.capture_next_settings();
    tui.press(b"S");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));

    // Both answers come in, the settings with the light theme.
    turns.answer("200 OK", TURNS);
    // The light theme is drawn - the settings are in, the turns long before them - and History
    // is still on screen: neither the transcript nor the settings page came back.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        screen.contains("/ Search") && history_row(&screen, "interview.mp3").is_some(),
        "History stays on screen:\n{screen}"
    );
    assert!(
        !screen.contains("Speakers") && !screen.contains("Terminal command"),
        "neither the transcript nor the settings came back:\n{screen}"
    );
}

/// The second review (S1): leaving a transcript by Back after `s` reads the settings ends that
/// screen too, so the answer - or its failure - arriving afterwards leaves History standing.
#[test]
fn leaving_a_transcript_by_back_keeps_history_under_the_late_answer() {
    let (service, mut tui, _connection) = start();

    open_interview(&service, &mut tui);

    // `s` reads the settings; the user leaves by Back before the answer comes.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());

    // The answer stores its settings, which the header draws, and brings no settings page.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        history_row(&screen, "interview.mp3").is_some()
            && !screen.contains("Default user interface"),
        "History stays under the late answer:\n{screen}"
    );
}

/// The second review (S1): the settings read failing after the user left the transcript by Back
/// brings no settings page either; History stands.
#[test]
fn a_settings_failure_after_back_keeps_history_standing() {
    let (service, mut tui, _connection) = start();

    open_interview(&service, &mut tui);

    // `s` reads the settings; the user leaves by Back before the read fails.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| history_row(text, "interview.mp3").is_some());
    settings.answer("500 Internal Server Error", BROKEN);

    // A later answer on the same channel - `t`'s theme write, which leaves History as it is and
    // whose stored settings the header draws - comes after the failure; by then the failure is in.
    tui.press(b"t");
    service
        .next_command(WAIT)
        .answer("200 OK", &stored(SETTINGS, json!({ "themeMode": "light" })));
    let screen = tui.wait_for(WAIT, |text| text.contains("Theme: light"));
    assert!(
        !screen.contains("cannot load the settings"),
        "History stands after the failure:\n{screen}"
    );
    assert!(
        tui.frames()
            .iter()
            .all(|frame| !frame.contains("cannot load the settings")),
        "no frame showed the settings failure"
    );
}

/// The second review (S1): the status page's retry, which goes to the queue, ends the settings
/// page `s` asked for there; the answer arriving afterwards leaves the queue standing.
#[test]
fn retrying_from_the_status_page_keeps_the_queue_under_the_late_answer() {
    let (service, mut tui, _connection) = start();

    // History, then the failed call.ogg's status page.
    tui.press(b"H");
    tui.wait_for(WAIT, |text| history_row(text, "call.ogg").is_some());
    tui.press(b"j");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("retry puts it back in the queue")
    });

    // `s` reads the settings; before the answer comes, `r` retries and the page goes to the queue.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"r");
    service.next_command(WAIT).answer(
        "200 OK",
        r#"{"id":4,"filename":"call.ogg","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));

    // The answer stores its settings, which the header draws, and brings no settings page.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        screen.contains("lecture.m4a") && !screen.contains("Default user interface"),
        "the queue stays under the late answer:\n{screen}"
    );
}

/// The second review (S1): deleting a transcript's task, which goes back to History, ends the
/// settings page `s` asked for on the transcript; the answer arriving afterwards leaves History
/// standing.
#[test]
fn deleting_a_transcripts_task_keeps_history_under_the_late_answer() {
    let (service, mut tui, _connection) = start();

    open_interview(&service, &mut tui);

    // `s` reads the settings; before the answer comes, the task is deleted after its confirmation.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"\x1b[3~");
    tui.wait_for(WAIT, |text| text.contains("Delete interview.mp3?"));
    tui.press(b"\r");
    service.next_command(WAIT).answer("204 No Content", "");
    tui.wait_for(WAIT, |text| {
        text.contains("All statuses ▾") && !text.contains("Speakers")
    });

    // The answer stores its settings, which the header draws, and brings no settings page.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        screen.contains("All statuses ▾") && !screen.contains("Default user interface"),
        "History stays under the late answer:\n{screen}"
    );
}

/// The second review (S1): Enter on a failed task's queue notice, which goes to History, ends the
/// settings page `s` asked for on the queue; the answer arriving afterwards leaves History standing.
#[test]
fn a_queue_notice_to_history_keeps_history_under_the_late_answer() {
    let (service, mut tui, (_state, mut stream)) = start();

    // call.ogg fails: its notice shows under the queue's rows.
    stream.send(
        "task-updated",
        r#"{"id":4,"filename":"call.ogg","status":"FAILED","transcriptionProgress":0.5,"error":"boom"}"#,
    );
    tui.wait_for(WAIT, |text| text.contains("call.ogg failed — see History"));

    // `s` reads the settings; before the answer comes, Enter on the notice goes to History.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    // Focus starts on the upload box: down past both rows to the notice.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));

    // The answer stores its settings, which the header draws, and brings no settings page.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        screen.contains("/ Search") && !screen.contains("Default user interface"),
        "History stays under the late answer:\n{screen}"
    );
}

/// The second review (S1): Enter on a duplicate upload's notice whose existing task failed goes to
/// History and ends the settings page `s` asked for on the queue; the answer arriving afterwards
/// leaves History standing.
#[test]
fn a_duplicate_notice_to_history_keeps_history_under_the_late_answer() {
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF-FAKE-A").unwrap();
    let (service, mut tui, _connection) = start_in(Some(folder.path()));

    // a.mp3 is the file the service already has as task 4, the failed call.ogg.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    service.next_upload(WAIT).answer(
        "409 Conflict",
        r#"{"error":"duplicate-audio","message":"This file was already uploaded as task 4","taskId":4}"#,
    );
    tui.wait_for(WAIT, |text| {
        text.contains("a.mp3 already uploaded — Enter open")
    });

    // `s` reads the settings; before the answer comes, Enter on the notice goes to History.
    service.capture_next_settings();
    tui.press(b"s");
    let settings = service.next_get_from(&tui, WAIT);
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));

    // The answer stores its settings, which the header draws, and brings no settings page.
    let screen = answer_late_settings(settings, &service, &tui);
    assert!(
        screen.contains("/ Search") && !screen.contains("Default user interface"),
        "History stays under the late answer:\n{screen}"
    );
}
