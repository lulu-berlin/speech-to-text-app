//! Roadmap item 67, third amendment: devices read as the browser names them - "Auto", "CPU", and
//! "GPU 0 — <name>" - in the Device row and its dropdown, and a choice still stores the service's
//! value (`gpu:0`).

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, GPU0, GPU1, SETTINGS, stored, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn devices_read_as_the_browser_names_them() {
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
    tui.wait_for(WAIT, |text| value(text, "Device") == "Auto");

    // The dropdown lists the probe's devices in the browser's words.
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("✓ Auto"));
    assert!(screen.contains("CPU"), "{screen}");
    assert!(screen.contains(GPU0), "{screen}");
    assert!(screen.contains(GPU1), "{screen}");

    // Choosing GPU 0 stores the service's value, and the row reads the browser's words.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["device"],
        json!("gpu:0")
    );
    write.answer("200 OK", &stored(SETTINGS, json!({ "device": "gpu:0" })));
    tui.wait_for(WAIT, |text| value(text, "Device") == GPU0);
}
