//! Roadmap item 67, the review (R13): a settings dropdown whose stored value is not among its
//! options still chooses. The service stored a GPU it no longer offers (`gpu:5`, not in
//! `availableDevices`); Enter on an offered device in the Device dropdown saves it, as the
//! footer's `[Enter] choose` says.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, body, stored};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn enter_in_a_dropdown_whose_stored_value_is_not_offered_saves_the_chosen_option() {
    // The fixture offers cpu, auto, gpu:0 and gpu:1, in that order; the stored device is none.
    let base = stored(SETTINGS, json!({ "device": "gpu:5" }));
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", &base);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("kitty -e"));

    // Enter on the Device row opens its list, no option ticked; Down to Auto, and Enter chooses it.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("Esc close") && !text.contains('✓')
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    // The settings whole, the device changed - and the fixed count's own maximum, as every write
    // from this page carries it (`settings_dropdown.rs`).
    let change = json!({ "device": "auto", "maxSpeakers": 3 });
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(&base, change.clone())
    );
    write.answer("200 OK", &stored(&base, change));
    tui.wait_for(WAIT, |text| {
        !text.contains("Esc close")
            && text
                .lines()
                .any(|line| line.contains("Device") && line.contains("Auto"))
    });
}
