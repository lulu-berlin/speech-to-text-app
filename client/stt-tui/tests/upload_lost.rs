//! Roadmap item 67: an upload cut off by a lost connection is held - the line above the queue
//! says it waits - and sent again once the connection is back, as the browser's upload row waits
//! for the connection and resends.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn an_upload_cut_off_by_a_lost_connection_waits_and_is_sent_again() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF-FAKE-A").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        folder.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The dialog lists `../` and a.mp3: Down selects a.mp3, Enter sends it; the request arrives
    // whole and the connection goes away before its answer.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    upload.cut_off();

    // The event stream drops too: the TUI is reconnecting and the file's line waits.
    drop(stream);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Reconnecting") && text.contains("a.mp3 waiting for the connection…")
    });
    assert!(
        screen.contains("a.mp3 waiting for the connection…"),
        "{screen}"
    );

    // The connection comes back: the held file goes again and is queued.
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let again = service.next_upload(WAIT);
    assert!(
        again
            .request()
            .body
            .contains(r#"name="file"; filename="a.mp3""#),
        "{}",
        again.request().body
    );
    again.answer(
        "201 Created",
        r#"{"id":9,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    let screen = tui.wait_for(WAIT, |text| text.contains("queued a.mp3"));
    assert!(
        !screen.contains("waiting for the connection"),
        "the waiting line goes with the resend:\n{screen}"
    );
}
