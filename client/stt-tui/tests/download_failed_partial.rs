//! Roadmap item 67, the review (discovered in R3): a download that fails leaves nothing behind - no
//! partial file beside its target, and the target untouched. The body is written to a hidden
//! `.name.part` beside the target and takes its place only once whole.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::open_transcript;

const WAIT: Duration = Duration::from_secs(20);
/// The service's refusal, in the contract's error shape.
const REFUSED: &str = r#"{"error":"internal","message":"the service broke"}"#;

#[test]
fn a_failed_download_leaves_no_partial_file() {
    let open = open_transcript();
    let mut tui = open.tui;
    let service = open.service;
    let files = tempfile::tempdir().unwrap();
    let target = files.path().join("memo.txt");

    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    tui.press(target.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    let fetch = service.next_get_from(&tui, WAIT);
    assert!(
        fetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/transcript?format=txt "),
        "{:?}",
        fetch.request().line
    );
    fetch.answer("500 Internal Server Error", REFUSED);
    let screen = tui.wait_for(WAIT, |text| text.contains("download failed"));

    // Nothing was left behind: the target was never written, and the partial file is gone.
    let left: Vec<String> = fs::read_dir(files.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    assert!(
        left.is_empty(),
        "the failed download left {left:?}:\n{screen}"
    );
}
