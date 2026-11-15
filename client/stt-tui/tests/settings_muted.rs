//! Roadmap item 67, the design pass (the user's report, 2026-11-15): the Settings page's
//! introduction and the names of the settings it does not have selected are drawn in the muted
//! colour, as `docs/design/tui/settings-dark.svg` draws them; the selected setting's name and the
//! values keep the text colour.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::json;
use settings_fixture::{FRONTENDS, SETTINGS, stored};
use terminal::{EventService, Tui};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_settings_intro_and_unselected_names_are_muted() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    let dark = stored(SETTINGS, json!({ "themeMode": "dark" }));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", &dark);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);

    let muted = token(&["colors", "textMuted"], "dark");
    let text = token(&["colors", "text"], "dark");
    tui.wait_for_cell(WAIT, "Where transcription", |cell| cell.fgcolor() == muted);
    let scene = tui.wait_for_scene(WAIT, |screen| screen.contains("Threads"));
    assert_eq!(scene.fg("Threads"), muted, "an unselected setting's name");
    assert_eq!(scene.fg("Language"), muted, "an unselected setting's name");
    assert_eq!(scene.fg("Device"), text, "the selected setting's name");
    assert_eq!(
        scene.fg("Restore default settings"),
        muted,
        "the restore row, not selected"
    );
}
