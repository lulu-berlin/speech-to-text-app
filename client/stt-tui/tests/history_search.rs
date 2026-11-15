//! Roadmap item 67, third amendment: `/` searches History by file name and `f` cycles the status
//! filter; when nothing matches it says so, and one key - Escape - clears both, as the browser's
//! Clear filters does.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The History row naming `name`, if one is on screen.
fn row<'a>(text: &'a str, name: &str) -> Option<&'a str> {
    text.lines()
        .find(|line| line.contains(name) && (line.contains("COMPLETED") || line.contains("FAILED")))
}

#[test]
fn slash_searches_and_f_filters_and_escape_clears_both() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        row(text, "interview.mp3").is_some() && row(text, "call.ogg").is_some()
    });

    // `/` takes a search: part of a name, ignoring case, hides the other rows.
    tui.press(b"/");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    tui.press(b"INTER");
    let screen = tui.wait_for(WAIT, |text| {
        controls(text).contains("INTER")
            && row(text, "interview.mp3").is_some()
            && row(text, "call.ogg").is_none()
    });
    assert!(
        screen.contains("interview.mp3"),
        "the match stays:\n{screen}"
    );

    // Escape clears the search and closes it: both rows are back.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        text.contains("/ Search")
            && row(text, "interview.mp3").is_some()
            && row(text, "call.ogg").is_some()
    });

    // `f` cycles the filter: Completed, Failed, Canceled, All.
    tui.press(b"f");
    tui.wait_for(WAIT, |text| {
        controls(text).contains("Completed ▾")
            && row(text, "interview.mp3").is_some()
            && row(text, "call.ogg").is_none()
    });
    tui.press(b"f");
    tui.wait_for(WAIT, |text| {
        controls(text).contains("Failed ▾")
            && row(text, "call.ogg").is_some()
            && row(text, "interview.mp3").is_none()
    });

    // Nothing matches: it says so, and one key - Escape - clears the filter too.
    tui.press(b"f");
    let screen = tui.wait_for(WAIT, |text| {
        controls(text).contains("Canceled ▾")
            && text.contains("No tasks match your search and filter.")
    });
    assert!(
        !screen.contains("interview.mp3") && !screen.contains("call.ogg"),
        "{screen}"
    );
    assert!(screen.contains("Esc clears them"), "{screen}");
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        controls(text).contains("All statuses ▾")
            && row(text, "interview.mp3").is_some()
            && row(text, "call.ogg").is_some()
    });

    // A search that matches nothing says so as well.
    tui.press(b"/");
    tui.press(b"zz");
    tui.wait_for(WAIT, |text| {
        controls(text).contains("zz") && text.contains("No tasks match your search and filter.")
    });
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| {
        row(text, "interview.mp3").is_some() && row(text, "call.ogg").is_some()
    });
}

/// History's controls row: the search field and the status chip.
fn controls(text: &str) -> &str {
    text.lines()
        .find(|line| line.contains("/ Search"))
        .unwrap_or_default()
}
