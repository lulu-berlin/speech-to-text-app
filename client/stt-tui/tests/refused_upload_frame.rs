//! Roadmap item 67 (discovered, a local stress run 2026-11-16): an upload the service refuses never
//! shows its notice beside the upload's own progress line - the line goes first, so no frame
//! says the file is still on its way while it already says the service refused it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_refused_uploads_notice_never_shares_a_frame_with_its_progress_line() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    let path = files.path().join("podcast.mp3");
    fs::write(&path, b"RIFF-FAKE-WAVE").unwrap();
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(path.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    service.next_upload(WAIT).answer(
        "409 Conflict",
        include_str!("../../../docs/api/error.duplicate-audio.json"),
    );
    // The settled screen: the refusal's notice, and the upload's line gone.
    tui.wait_for(WAIT, |text| {
        text.contains("podcast.mp3 already uploaded") && !text.contains("uploading podcast.mp3")
    });

    let both: Vec<String> = tui
        .frames()
        .into_iter()
        .filter(|frame| {
            frame.contains("podcast.mp3 already uploaded")
                && frame.contains("uploading podcast.mp3")
        })
        .collect();
    assert!(
        both.is_empty(),
        "a frame shows the refusal beside the upload's progress line:\n{}",
        both.join("\n---\n")
    );
}
