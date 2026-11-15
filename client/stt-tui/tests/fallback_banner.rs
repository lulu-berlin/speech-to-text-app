//! Roadmap item 67, parity amendment: the device-fallback banner. The stored settings (read at
//! startup and kept current by `settings-updated`) say when whisper-cli fell back to the CPU; the
//! queue says so, and `x` dismisses it with DELETE /api/settings/device-fallback. Without a
//! fallback, `x` sends nothing.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const FALLBACK: &str = r#"{"device":"cpu","threads":4,"defaultLanguage":"de","speakersMode":"auto","speakersCount":null,"maxSpeakers":8,"deviceFallback":"gpu:1","defaultFrontend":null,"terminalCommand":null,"availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{}}"#;
const CLEARED: &str = r#"{"device":"cpu","threads":4,"defaultLanguage":"de","speakersMode":"auto","speakersCount":null,"maxSpeakers":8,"deviceFallback":null,"defaultFrontend":null,"terminalCommand":null,"availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{}}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_fallback_banner_shows_and_x_dismisses_it() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The settings read at startup carry the fallback: the queue says so.
    service.next_get_from(&tui, WAIT).answer("200 OK", FALLBACK);
    let screen = tui.wait_for(WAIT, |text| text.contains("gpu:1"));
    assert!(screen.contains("running on the CPU"), "{screen}");
    assert!(screen.contains("x dismisses"), "{screen}");

    // `x`: the DELETE, and the answer clears it.
    tui.press(b"x");
    let dismiss = service.next_command(WAIT);
    assert!(
        dismiss
            .request()
            .line
            .starts_with("DELETE /api/settings/device-fallback "),
        "{:?}",
        dismiss.request().line
    );
    dismiss.answer("200 OK", CLEARED);
    // The banner is gone and the queue is drawn: a frame caught mid-repaint lacks the banner too.
    let screen = tui.wait_for(WAIT, |text| {
        !text.contains("running on the CPU") && text.contains("memo.wav")
    });
    assert!(!screen.contains("gpu:1"), "{screen}");

    // With no fallback, `x` sends nothing.
    tui.press(b"x");
    assert!(
        !service.command_arrived(),
        "no banner, no request:\n{screen}"
    );
}
