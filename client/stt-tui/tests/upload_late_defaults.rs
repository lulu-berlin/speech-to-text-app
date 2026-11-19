//! Roadmap item 67, the review (R17): the upload dialog's options start from the stored settings,
//! read when the dialog opens. An answer that comes after the user changed an option leaves that
//! change as the user made it, and fills only what the user has not touched; the answer to a
//! previous dialog's read changes nothing in the open one.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use serde_json::json;
use settings_fixture::{SETTINGS, stored};
use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn late_defaults_keep_the_users_choice_and_a_previous_dialogs_answer_changes_nothing() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    // b.mp3 is the row below a.mp3, which the dialog's barrier moves to and back.
    let files = tempfile::tempdir().unwrap();
    for name in ["a.mp3", "b.mp3"] {
        fs::write(files.path().join(name), b"RIFF-FAKE-WAVE").unwrap();
    }
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));

    // A first dialog asks for the settings and is closed before they come; a second one asks again.
    service.capture_next_settings();
    tui.press(b"u");
    let first = service.next_get_from(&tui, WAIT);
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    service.capture_next_settings();
    tui.press(b"u");
    let second = service.next_get_from(&tui, WAIT);
    tui.wait_for(WAIT, |text| text.contains("Language   auto ▾"));

    // Before either answer, the user chooses English: the list's next code after `auto`. A click
    // then puts the cursor on a.mp3.
    tui.press(b"\t");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ auto  auto-detect"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("Language   en ▾"));
    let (column, row) = position_of(&screen, "a.mp3");
    tui.click(column, row);
    tui.wait_for_highlight(WAIT, "a.mp3");

    // The open dialog's answer: the speakers the user left take the stored five, the language
    // stays the user's.
    second.answer(
        "200 OK",
        &stored(
            SETTINGS,
            json!({ "defaultLanguage": "fr", "speakersMode": "fixed", "speakersCount": 5 }),
        ),
    );
    let screen = tui.wait_for(WAIT, |text| text.contains("Speakers   5 ▾"));
    assert!(screen.contains("Language   en ▾"), "{screen}");

    // The first dialog's answer, late, read by the TUI whole before anything else happens.
    first.answer_read(
        "200 OK",
        &stored(
            SETTINGS,
            json!({ "defaultLanguage": "it", "speakersMode": "off", "speakersCount": null }),
        ),
        WAIT,
    );

    // A third dialog's answer is handled after it (the second review, S11): it is only asked for
    // once the late answer was read, and goes the whole way round where the late one only has to
    // be parsed - so once the third dialog shows its defaults, the late answer has been handled.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer(
        "200 OK",
        &stored(
            SETTINGS,
            json!({ "defaultLanguage": "de", "speakersMode": "fixed", "speakersCount": 2 }),
        ),
    );
    tui.wait_for(WAIT, |text| text.contains("Speakers   2 ▾"));

    // No frame ever showed the late answer's values: the open dialog kept the user's language and
    // the speakers its own answer set, until it was closed.
    let frames = tui.frames();
    assert!(
        frames
            .iter()
            .all(|frame| !frame.contains("Language   it ▾") && !frame.contains("Speakers   off ▾")),
        "a frame showed the previous dialog's answer:\n{}",
        frames
            .iter()
            .find(|frame| frame.contains("it ▾") || frame.contains("off ▾"))
            .map_or("", String::as_str)
    );
}
