//! Roadmap item 67 (the user, 2026-11-11: "download should open a dialog like upload where folders
//! can be navigated"): the download dialog browses like the upload dialog - the folder's subfolders
//! and files listed, `../` and Backspace going up, Enter descending into a folder - while typing
//! names the target file and Tab still chooses the format.//!
//! The dialog opens in the folder the TUI was started in, the transcript's own folder being no part
//! of the terminal's notion of where files go.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{OpenTranscript, open_transcript_in};

const TRANSCRIPT: &str = "1\n00:00:00,000 --> 00:00:02,100\nGood morning.\n";
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_download_dialog_browses_folders_like_the_upload_dialog() {
    let folder = tempfile::tempdir().unwrap();
    fs::create_dir(folder.path().join("keep")).unwrap();
    fs::write(folder.path().join("keep").join("a.txt"), "").unwrap();
    fs::write(folder.path().join("top.txt"), "").unwrap();
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_in(folder.path());

    // `d` opens on that folder's list: the folders and files it holds, the parent row first.
    tui.press(b"d");
    let opened = tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("keep") && text.contains("top.txt")
    });
    assert!(opened.contains("../"), "the parent row:\n{opened}");
    assert!(
        opened.contains("↑/↓ move"),
        "two rows and the parent: the footer offers the arrows:\n{opened}"
    );

    // Enter on `keep` descends into it: its file, and the parent row.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let inside = tui.wait_for(WAIT, |text| {
        text.contains("a.txt") && !text.contains("top.txt")
    });
    assert!(inside.contains("../"), "the parent row:\n{inside}");

    // The `../` row goes up; so does Backspace with nothing typed.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("top.txt"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("a.txt") && !text.contains("top.txt")
    });
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| text.contains("top.txt"));

    // Typing names the target file in the shown folder, and its extension picks the format;
    // Enter saves there.
    tui.press(b"out.srt");
    tui.wait_for(WAIT, |text| {
        text.contains("out.srt") && text.contains("srt")
    });
    tui.press(b"\r");
    let fetch = service.next_get_from(&tui, WAIT);
    assert!(
        fetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/transcript?format=srt "),
        "{:?}",
        fetch.request().line
    );
    fetch.answer("200 OK", TRANSCRIPT);
    tui.wait_for(WAIT, |text| text.contains("saved"));
    assert_eq!(
        fs::read_to_string(folder.path().join("out.srt")).unwrap(),
        TRANSCRIPT
    );

    // Tab still reaches the format, where Left and Right change it without moving in the list.
    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    tui.press(b"memo.txt");
    tui.press(b"\t");
    tui.press(b"\x1b[C");
    tui.wait_for(WAIT, |text| text.contains("srt"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Download"));
}
