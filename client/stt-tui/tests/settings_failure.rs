//! Roadmap item 67, third amendment: a settings read that fails shows on the settings page, as the
//! browser's page does - the error and a way to read again (the user, 2026-11-13: "page") - and
//! Enter reads them again.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);
/// A read the service cannot answer.
const BROKEN: &str = r#"{"error":"internal","message":"the database is locked"}"#;

#[test]
fn a_failed_settings_read_shows_on_the_page_and_enter_reads_again() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The read fails: the settings page opens with the error and the way to read again.
    service.capture_next_settings();
    tui.press(b"s");
    let read = service.next_get_from(&tui, WAIT);
    assert!(
        read.request().line.starts_with("GET /api/settings "),
        "{:?}",
        read.request().line
    );
    read.answer("500 Internal Server Error", BROKEN);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("cannot load the settings: the database is locked")
    });
    assert!(
        screen.contains("Where transcription runs"),
        "the settings page is open:\n{screen}"
    );
    assert!(screen.contains("Enter retries"), "{screen}");

    // Enter reads them again; answered, the settings show.
    service.capture_next_settings();
    tui.press(b"\r");
    let read = service.next_get_from(&tui, WAIT);
    assert!(
        read.request().line.starts_with("GET /api/settings "),
        "{:?}",
        read.request().line
    );
    read.answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let screen = tui.wait_for(WAIT, |text| value(text, "Threads") == "4");
    assert!(!screen.contains("cannot load the settings"), "{screen}");
}
