//! Roadmap item 67, second amendment: the selected setting shows a one-line description under the
//! list, in the shared catalog's words, and it is never clipped - at 60 columns it wraps over as
//! many lines as it needs.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// Each row's description, in the rows' order.
const DESCRIPTIONS: [&str; 6] = [
    "Device — where whisper transcribes: Auto picks a GPU that works, otherwise the CPU.",
    "Threads — how many threads whisper and the speaker model use; auto lets them choose.",
    "Language — the language new uploads transcribe as, unless the file says otherwise.",
    "Speakers — auto, off or a fixed count: how many speakers a task looks for.",
    "Default user interface — the frontend a task opens in when none is named; none asks for one.",
    "Terminal command — the terminal the TUI opens in; empty uses the OS default.",
];

/// `text` with every run of whitespace on one line, so a wrapped description reads as one.
fn one_line(text: &str) -> String {
    text.split_whitespace().collect::<Vec<&str>>().join(" ")
}

#[test]
fn the_selected_setting_shows_its_whole_description_under_the_list() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 60, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Terminal command"));

    // Down from the device to the terminal command: each row's own description, whole, under the
    // list - the last row's line and the notice line sit below it.
    let mut previous = DESCRIPTIONS[0];
    for (row, description) in DESCRIPTIONS.iter().enumerate() {
        if row > 0 {
            tui.press(b"\x1b[B");
        }
        let text = tui.wait_for(WAIT, |text| one_line(text).contains(description));
        if row > 0 {
            assert!(
                !one_line(&text).contains(previous),
                "the previous row's description is gone:\n{text}"
            );
        }
        previous = description;
        // Both searched on one line, so a wrapped description still reads: its tail, not its
        // head, since the head repeats the row's label.
        let flat = one_line(&text);
        let at = flat.find("Terminal command").unwrap();
        let tail = &description[description.len() - 20..];
        let description_at = flat
            .find(tail)
            .unwrap_or_else(|| panic!("{tail:?} is on screen:\n{text}"));
        assert!(
            description_at > at,
            "the description sits under the list:\n{text}"
        );
    }
}
