//! Roadmap item 67, parity amendment: the pages. A tab bar names Queue, History and Settings, the
//! active one is bracketed, and `1`/`2`/`3` or `Tab`/`Shift-Tab` switch between them from any page;
//! History and Settings fetch what they need when opened.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
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
    let screen = tui.wait_for(WAIT, |text| text.contains("[1 Queue]"));
    assert!(
        screen.contains("2 History"),
        "the bar names every page:\n{screen}"
    );
    assert!(
        screen.contains("3 Settings"),
        "the bar names every page:\n{screen}"
    );

    // `2`: the history, from GET /api/tasks, and its tab is the active one.
    tui.press(b"2");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    let screen = tui.wait_for(WAIT, |text| text.contains("[2 History]"));
    assert!(screen.contains("interview.mp3"), "{screen}");

    // `3`: the settings, from GET /api/settings and the frontends.
    tui.press(b"3");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| {
        text.contains("[3 Settings]") && text.contains("Threads")
    });

    // `1` back to the queue, which needs no fetch.
    tui.press(b"1");
    let screen = tui.wait_for(WAIT, |text| text.contains("[1 Queue]"));
    assert!(screen.contains("memo.wav"), "{screen}");

    // Tab and Shift-Tab cycle from any page: from the queue, Shift-Tab wraps back to Settings.
    tui.press(b"\x1b[Z");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("[3 Settings]"));
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| text.contains("[1 Queue]"));
}
