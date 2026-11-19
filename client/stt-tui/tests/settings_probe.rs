//! Roadmap item 67, third amendment: a failed device probe shows on the settings page, as the
//! browser's callout does - the probe's failure and its detail - above the settings.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::Value;
use settings_fixture::{FRONTENDS, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// The settings a failed probe leaves: cpu and auto only, and why.
const PROBE_FAILED: &str = include_str!("../../../docs/api/settings.probe-failed.json");
const WAIT: Duration = Duration::from_secs(20);
/// The page's line for that failure.
const PROBE_LINE: &str = "The device probe failed — Executable not found: whisper-cli";

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
    let screen = tui.wait_for(WAIT, |text| text.contains(PROBE_LINE));

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

/// The review (R14): a `settings-updated` event carries no probe failure (`SettingsNotice` leaves
/// it out, as it does the device list), so the line stays, as the browser's callout does.
#[test]
fn a_settings_updated_event_keeps_the_probe_failure() {
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
    tui.wait_for(WAIT, |text| text.contains(PROBE_LINE));

    // Another client stores six threads: the event is the stored settings without the read-only
    // device list, GPU names and probe failure.
    let mut event: Value = serde_json::from_str(PROBE_FAILED).unwrap();
    let fields = event.as_object_mut().unwrap();
    for read_only in ["availableDevices", "gpuNames", "deviceProbeError"] {
        fields.remove(read_only);
    }
    fields.insert("threads".to_string(), 6.into());
    stream.send("settings-updated", &event.to_string());
    let screen = tui.wait_for(WAIT, |text| value(text, "Threads") == "6");
    assert!(screen.contains(PROBE_LINE), "{screen}");
}
