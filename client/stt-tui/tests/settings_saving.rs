//! Roadmap item 67, third amendment: the settings page says "Saving…" while a write runs, as the
//! browser's status line does, and stops saying it once the service has answered.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::json;
use settings_fixture::{FRONTENDS, SETTINGS, stored, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_settings_page_says_saving_while_a_write_runs() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"s");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let screen = tui.wait_for(WAIT, |text| value(text, "Threads") == "4");
    assert!(
        !screen.contains("Saving…"),
        "nothing is being saved:\n{screen}"
    );

    // Threads raised to 5: while the write waits for its answer, the page says so.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| value(text, "Threads").starts_with("4▏"));
    tui.press(b"\x1b[A\r");
    let write = service.next_command(WAIT);
    tui.wait_for(WAIT, |text| text.contains("Saving…"));

    // Answered, the page shows the stored value and no longer says it is saving.
    write.answer("200 OK", &stored(SETTINGS, json!({ "threads": 5 })));
    tui.wait_for(WAIT, |text| {
        value(text, "Threads") == "5" && !text.contains("Saving…")
    });
}
