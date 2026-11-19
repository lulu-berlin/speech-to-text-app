//! Roadmap item 67, third amendment: the settings page opens with the browser's introduction -
//! where transcription runs and the defaults of new uploads, and when a change applies - above the
//! settings.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS, value};
use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_settings_page_opens_with_the_browsers_introduction() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 160, 30);
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

    // The browser's words, whole on a wide terminal, above the first setting.
    let intro = "Where transcription runs and the defaults of new uploads. A saved change applies \
                 to the next task; one already running keeps its device.";
    assert!(screen.contains(intro), "{screen}");
    assert!(
        position_of(&screen, intro).1 < position_of(&screen, "Device").1,
        "the introduction is above the settings:\n{screen}"
    );
}
