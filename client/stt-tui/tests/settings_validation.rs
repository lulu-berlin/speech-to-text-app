//! Roadmap item 67, third amendment: a value the service refuses reverts that setting alone with
//! the service's message, in the setting's row, as the browser shows each field's message under
//! that field. A number row takes only digits, so a draft is never invalid before Enter.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const INVALID_DEVICE: &str = include_str!("../../../docs/api/error.invalid-device.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_refused_value_reverts_alone_with_the_services_message() {
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
    tui.wait_for(WAIT, |text| value(text, "Threads") == "4");

    // A device the service refuses: the row keeps the stored one with the message, and every
    // other setting is as it was.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ Auto"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["device"],
        json!("gpu:0")
    );
    write.answer("400 Bad Request", INVALID_DEVICE);
    let message: Value = serde_json::from_str(INVALID_DEVICE).unwrap();
    let message = message["message"].as_str().unwrap();
    let screen = tui.wait_for(WAIT, |text| value(text, "Device").contains(message));
    assert!(
        value(&screen, "Device").starts_with("Auto"),
        "the device reverts to the stored one:\n{screen}"
    );
    assert_eq!(value(&screen, "Threads"), "4", "{screen}");
}
