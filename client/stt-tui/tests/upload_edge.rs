//! Roadmap item 67, parity amendment: the upload dialog's edges. Leaving `fixed` sends `auto`, the
//! speaker count stays within 1..=20, and a path that cannot be opened is reported with no request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui, multipart_field};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = r#"{"device":"auto","threads":4,"defaultLanguage":"de","speakersMode":"fixed","speakersCount":3,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","appLanguage":null,"themeMode":"dark","availableDevices":["cpu","auto"],"gpuNames":{}}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn leaving_fixed_sends_auto_counts_clamp_and_a_bad_path_sends_nothing() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("talk.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    let path = files.path().join("talk.mp3");
    let path = path.to_str().unwrap();

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

    // Leaving `fixed` through the chip's list (Tab to the settings, Right to Speakers, Down to
    // open, Up past 1 and off) sends `auto`.
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));
    tui.press(b"\t");
    tui.press(b"\x1b[C");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    for _ in 0..4 {
        tui.press(b"\x1b[A");
    }
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("Speakers   auto ▾"));
    assert!(
        !screen.contains("Speaker count"),
        "no separate count:\n{screen}"
    );
    tui.press(path.as_bytes());
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert_eq!(
        multipart_field(&upload.request().body, "speakers"),
        Some("auto")
    );
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"talk.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );

    // The count is the choice: Up from 3 through 2 to 1, the lowest count there is.
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));
    tui.press(b"\t");
    tui.press(b"\x1b[C");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("Speakers   1 ▾"));
    assert!(
        screen.contains("Speakers   1 ▾"),
        "the count is the choice's value:\n{screen}"
    );
    tui.press(path.as_bytes());
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert_eq!(
        multipart_field(&upload.request().body, "speakers"),
        Some("1")
    );
    assert_eq!(
        multipart_field(&upload.request().body, "maxSpeakers"),
        Some("1"),
        "a fixed count is its own maximum"
    );
    upload.answer(
        "201 Created",
        r#"{"id":10,"filename":"talk.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );

    // A path that cannot be opened: reported inside the dialog, no request.
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"/nowhere/missing.mp3\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("does not exist"));
    assert!(
        !service.upload_arrived(),
        "a missing path is not sent:\n{screen}"
    );
}
