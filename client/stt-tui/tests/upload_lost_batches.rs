//! Roadmap item 67, the review (R5): two upload batches the connection cuts off both wait for it
//! and both go again once it is back - no file of the first batch is dropped or goes unreported
//! because a second batch was cut off after it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui, uploaded_name};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn two_batches_cut_off_by_the_connection_both_go_again() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    for name in ["a.mp3", "b.mp3", "c.mp3"] {
        fs::write(folder.path().join(name), b"RIFF-FAKE").unwrap();
    }
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

    // The first batch: a.mp3 and b.mp3, marked; a.mp3 goes first and its answer is held back.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("c.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains("Upload 2 files"));
    tui.press(b"\r");
    let first = service.next_upload(WAIT);

    // The second batch, while the first waits for its answer: c.mp3 alone.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("c.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let second = service.next_upload(WAIT);

    // The connection goes away before either answer, the first batch's cut off first.
    first.cut_off();
    second.cut_off();
    drop(stream);
    tui.wait_for(WAIT, |text| {
        text.contains("Reconnecting")
            && text.contains("a.mp3 waiting for the connection…")
            && text.contains("c.mp3 waiting for the connection…")
    });

    // Back: every file of both batches goes again and is queued.
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let mut sent: Vec<String> = Vec::new();
    for id in 20..23 {
        let upload = service.next_upload(WAIT);
        let name = uploaded_name(&upload.request().body).to_string();
        upload.answer(
            "201 Created",
            &format!(
                r#"{{"id":{id},"filename":"{name}","status":"QUEUED","transcriptionProgress":0.0}}"#
            ),
        );
        sent.push(name);
    }
    assert_eq!(
        sent,
        ["a.mp3", "b.mp3", "c.mp3"],
        "both batches went again whole, in the order they were cut off, each batch's files in its own"
    );
    tui.wait_for(WAIT, |text| !text.contains("waiting for the connection"));
}
