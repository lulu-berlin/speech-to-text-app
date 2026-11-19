//! Roadmap item 67, third amendment: a transcript opens with a header - the file name, the finish
//! date and the duration - says "Loading transcript…" while its turns are read, and offers a retry
//! when the read fails.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_transcript_shows_its_header_while_it_loads_and_retries_a_failed_read() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));

    // Enter opens it at once: the header (name, finish date, duration) and the loading words,
    // before the service answers the turns.
    tui.press(b"\r");
    let turns = service.next_get_from(&tui, WAIT);
    let loading = tui.wait_for(WAIT, |text| text.contains("Loading transcript…"));
    assert!(
        loading.contains("Transcript: interview.mp3"),
        "the file name is the title:\n{loading}"
    );
    assert!(
        loading.contains("Oct 9, 2026"),
        "the finish date:\n{loading}"
    );
    assert!(loading.contains("19:45"), "the duration:\n{loading}");
    assert!(loading.contains('╭'), "the waiting tile:\n{loading}");

    // The read fails: the header stays, the reason and the retry show.
    turns.answer(
        "500 Internal Server Error",
        r#"{"error":"turns-failed","message":"the turns are gone"}"#,
    );
    let failed = tui.wait_for(WAIT, |text| {
        text.contains("cannot load the transcript: the turns are gone")
    });
    assert!(failed.contains("Enter retries"), "{failed}");
    assert!(
        failed.contains("Oct 9, 2026"),
        "the header stays:\n{failed}"
    );
    assert!(!failed.contains("Loading transcript…"), "{failed}");

    // Enter reads again; the turns replace the waiting words, and the header stays above them.
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    let loaded = tui.wait_for(WAIT, |text| {
        text.contains("Good morning.") && !text.contains("Loading transcript…")
    });
    assert!(
        loaded.contains("Oct 9, 2026"),
        "the header stays with the turns:\n{loaded}"
    );
    assert!(loaded.contains("19:45"), "the duration stays:\n{loaded}");
}
