//! Roadmap item 67: downloads. In a transcript, `d` opens one dialog with the path focused and the
//! format beside it; a path's extension picks the format, Tab and Left/Right change it, Enter
//! fetches `GET /api/tasks/{id}/transcript?format=…` and writes the body to the path, and Escape
//! cancels. A path that cannot be written is reported without a request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TASK_LIST: &str = include_str!("../../../docs/api/task-list.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const TRANSCRIPT: &str = "1\n00:00:00,000 --> 00:00:02,100\nGood morning.\n";
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_download_dialog_writes_the_chosen_format_and_reports_a_bad_path() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Into the transcript: history, then the completed task.
    tui.press(b"h");
    service.next_get(WAIT).answer("200 OK", TASK_LIST);
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3") && text.contains("History")
    });
    tui.press(b"\r");
    service.next_get(WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        text.contains("Good morning. Thanks for joining us today.")
    });

    // `d` opens the download dialog with the path focused; `.srt` picks the srt format.
    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    let target = files.path().join("memo.srt");
    tui.press(target.to_str().unwrap().as_bytes());
    tui.wait_for(WAIT, |text| text.contains("srt"));
    tui.press(b"\r");
    let fetch = service.next_get(WAIT);
    assert!(
        fetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/transcript?format=srt "),
        "{:?}",
        fetch.request().line
    );
    assert_eq!(
        fetch.request().header("Authorization"),
        Some("Bearer the-install-secret")
    );
    fetch.answer("200 OK", TRANSCRIPT);
    tui.wait_for(WAIT, |text| text.contains("saved"));
    assert_eq!(fs::read_to_string(&target).unwrap(), TRANSCRIPT);

    // Tab moves to the format row, and Left/Right change it.
    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    tui.press(b"memo.txt");
    tui.press(b"\t");
    tui.press(b"\x1b[C"); // Right
    tui.wait_for(WAIT, |text| text.contains("srt"));
    tui.press(b"\x1b"); // Escape cancels the dialog
    tui.wait_for(WAIT, |text| !text.contains("Download"));

    // A path that cannot be written: reported, no request, the transcript stays.
    tui.press(b"d");
    tui.press(b"/nowhere/memo.txt\r");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("/nowhere/memo.txt") && text.contains("download failed")
    });
    assert!(
        !service.get_arrived(),
        "a path that cannot be written is not fetched:\n{screen}"
    );
    assert!(
        screen.contains("Good morning."),
        "the transcript stays:\n{screen}"
    );
}
