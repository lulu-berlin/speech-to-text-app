//! Roadmap item 67: downloads. In a transcript, `d` opens one dialog with the path focused and the
//! format beside it; a path's extension picks the format, Tab and Left/Right change it, Enter
//! fetches `GET /api/tasks/{id}/transcript?format=…` and writes the body to the path, and Escape
//! cancels. A path that cannot be written is reported without a request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{OpenTranscript, offers, open_transcript};

const TRANSCRIPT: &str = "1\n00:00:00,000 --> 00:00:02,100\nGood morning.\n";
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_download_dialog_writes_the_chosen_format_and_reports_a_bad_path() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    let files = tempfile::tempdir().unwrap();

    // `d` opens the download dialog with the path focused; `.srt` picks the srt format.
    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && text.contains("txt")
    });
    let target = files.path().join("memo.srt");
    tui.press(target.to_str().unwrap().as_bytes());
    tui.wait_for(WAIT, |text| text.contains("srt"));
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

#[test]
fn tab_reaches_the_format_where_left_and_right_step_it() {
    let OpenTranscript {
        mut tui,
        service: _service,
        stream: _stream,
        state: _state,
    } = open_transcript();
    tui.press(b"d");
    tui.wait_for(WAIT, |text| {
        text.contains("Download") && !text.contains("> Format:")
    });

    // Tab puts the focus on the format, where Left and Right step it.
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| {
        text.contains("> Format:") && text.contains("Format: txt")
    });
    tui.press(b"\x1b[C");
    tui.wait_for(WAIT, |text| {
        text.contains("> Format:") && text.contains("Format: srt")
    });

    // Tab returns to the folder list, where Up and Down move - not to the format as before.
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| !text.contains("> Format:"));

    // The dialog's footer names the browser's keys; a folder with one row offers no move.
    tui.wait_for(WAIT, |text| {
        text.contains("Enter opens a folder or saves the transcript")
            && text.contains("← or Backspace goes up")
            && text.contains("→ open folder")
            && !text.contains("↑/↓ move")
    });
}

/// The review (R18): the footer names the keys of the part that has the keyboard. On the folder
/// list Left and Right go up and open, and Tab reaches the format, so the footer says so; on the
/// format they step it, so neither the way up nor the folder's opening is offered there.
#[test]
fn the_footer_names_the_keys_of_the_list_or_of_the_format() {
    let OpenTranscript {
        mut tui,
        service: _service,
        stream: _stream,
        state: _state,
    } = open_transcript();
    tui.press(b"d");
    tui.wait_for(WAIT, |screen| {
        !screen.contains("> Format:")
            && offers(
                screen,
                &["← or Backspace up", "→ open folder", "Tab format"],
                &["←/→ format"],
            )
    });
    tui.press(b"\t");
    tui.wait_for(WAIT, |screen| {
        screen.contains("> Format:")
            && offers(
                screen,
                &["←/→ format", "Enter download", "Esc cancel"],
                &["← or Backspace up", "→ open folder", "Tab format"],
            )
    });
}

/// Discovered in R18: a typed name takes Backspace, so the footer offers no way up while one is
/// typed, as the upload dialog's does not.
#[test]
fn a_typed_name_leaves_no_way_up_in_the_footer() {
    let OpenTranscript {
        mut tui,
        service: _service,
        stream: _stream,
        state: _state,
    } = open_transcript();
    tui.press(b"d");
    tui.wait_for(WAIT, |screen| offers(screen, &["← or Backspace up"], &[]));
    // Typed, Backspace erases it: no hint offers it as a way up any more.
    tui.press(b"memo");
    let screen = tui.wait_for(WAIT, |text| text.contains("Path: memo"));
    assert!(
        !screen.contains("← or Backspace up"),
        "Backspace erases the typed name:\n{screen}"
    );
}
