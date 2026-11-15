//! Roadmap item 67, third amendment: a failed device probe shows on the settings page, as the
//! browser's callout does - the probe's failure and its detail - above the settings.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// The settings a failed probe leaves: cpu and auto only, and why.
const PROBE_FAILED: &str = include_str!("../../../docs/api/settings.probe-failed.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_failed_device_probe_shows_on_the_settings_page() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"s");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", PROBE_FAILED);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("The device probe failed — Executable not found: whisper-cli")
    });

    // The probe's devices are the ones offered, and the row reads the stored one.
    assert_eq!(value(&screen, "Device"), "Auto", "{screen}");
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("✓ Auto"));
    assert!(screen.contains("CPU"), "{screen}");
    assert!(
        !screen.contains("GPU 0") && !screen.contains("GPU 1"),
        "no GPU was probed:\n{screen}"
    );
}
