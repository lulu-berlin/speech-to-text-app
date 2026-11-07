//! Roadmap item 67, parity amendment: the footer. Every screen states its own keys in words, so
//! nothing has to be guessed - the queue names reorder and upload, history names open, a transcript
//! names rename and merge, the settings name how to change a value.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const SETTINGS: &str = include_str!("../../../docs/api/settings.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn every_screen_states_its_own_keys() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 200, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    let queue = tui.wait_for(WAIT, |text| text.contains("u upload"));
    assert!(queue.contains("J/K"), "the queue names reorder:\n{queue}");
    assert!(queue.contains("j/k"), "the queue names moving:\n{queue}");

    tui.press(b"2");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    let history = tui.wait_for(WAIT, |text| text.contains("enter open"));
    assert!(
        history.contains("pages"),
        "the footer names the tab keys:\n{history}"
    );

    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    let transcript = tui.wait_for(WAIT, |text| text.contains("r rename"));
    assert!(transcript.contains("m merge"), "{transcript}");
    assert!(transcript.contains("d download"), "{transcript}");

    tui.press(b"3");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    let settings = tui.wait_for(WAIT, |text| text.contains("←/→ change"));
    assert!(settings.contains("enter edit"), "{settings}");
}
