//! Roadmap item 67, parity amendment: the pages. A tab bar names Queue, History and Settings, the
//! open one in an accent chip, and `1`/`2`/`3` or `Tab`/`Shift-Tab` switch between them from any page;
//! History and Settings fetch what they need when opened.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = include_str!("../../../docs/api/settings.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_tab_bar_names_the_pages_and_switches_between_them() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    for page in ["Queue", "History", "Settings"] {
        assert!(
            screen.contains(page),
            "the bar names every page ({page}):\n{screen}"
        );
    }

    // `2`: the history, from the stream's snapshot, and its tab is the active one.
    let history_column = position_of(&screen, "History").0;
    tui.press(b"2");
    let screen = tui.wait_for(WAIT, |text| text.contains("/ Search"));
    assert!(screen.contains("interview.mp3"), "{screen}");
    assert_eq!(
        position_of(&screen, "History").0,
        history_column,
        "the chip's padding keeps every title in place:\n{screen}"
    );

    // `3`: the settings, from GET /api/settings and the frontends.
    service.capture_next_settings();
    tui.press(b"3");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| {
        text.contains("Restore default settings") && text.contains("Threads")
    });

    // `1` back to the queue, which needs no fetch.
    tui.press(b"1");
    let screen = tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    assert!(screen.contains("memo.wav"), "{screen}");

    // Tab and Shift-Tab cycle from any page: from the queue, Shift-Tab wraps back to Settings.
    service.capture_next_settings();
    tui.press(b"\x1b[Z");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Restore default settings"));
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
}
