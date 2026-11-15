//! Roadmap item 67, second amendment: the upload dialog says what to do. Its title names the
//! dialog, a folder line names the folder it shows, and hint lines say how to open a folder or
//! upload a file, go up, and type a path; the footer lists the dialog's keys. The words come from
//! the shared catalog (ADR 0067), and none of them is cut off, at 120 columns or at 60.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The catalog's text for `key` as a colour terminal shows it: a marked key (`[Enter] opens`) is
/// drawn highlighted, without its brackets.
fn catalog(key: &str) -> String {
    stt_client::text(key, &[]).replace(['[', ']'], "")
}

#[test]
fn the_upload_dialog_names_its_folder_and_says_how_to_use_it() {
    // The dialog's line names every way through it, as its design draws it: marking with Space,
    // opening with Enter, going up with ← or Backspace.
    let mark = catalog("tui.browse.markHint");
    assert!(
        mark.contains("Space")
            && mark.contains("Enter")
            && mark.contains('←')
            && mark.contains("Backspace"),
        "{mark}"
    );
    let footer_files = stt_client::text("tui.upload.footerFiles", &[("count", "0")]);
    let footer_tab = catalog("tui.upload.footerTab");
    let footer_path = catalog("tui.upload.footerPath");
    let footer_cancel = catalog("tui.upload.footerCancel");

    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("talk.mp3"), b"x").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        folder.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    let title = catalog("tui.upload.title");
    let folder_line =
        catalog("tui.browse.folder").replace("{dir}", &folder.path().display().to_string());
    let footer = [footer_files, footer_tab, footer_path, footer_cancel].join("    ");
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("talk.mp3"));
    for (what, text) in [
        ("title", &title),
        ("folder line", &folder_line),
        ("mark hint", &mark),
        ("footer", &footer),
    ] {
        assert!(
            screen.contains(text.as_str()),
            "the {what} {text:?} is on screen:\n{screen}"
        );
    }

    // At 60 columns the dialog is clipped as the design's fixed-width mockup allows, but its
    // title still shows whole.
    tui.resize(60, 24);
    let screen = tui.wait_for(WAIT, |text| {
        text.lines().all(|line| line.chars().count() <= 60)
    });
    assert!(
        screen.contains(title.as_str()),
        "{title:?} is whole at 60 columns:\n{screen}"
    );
}

#[test]
fn right_opens_the_selected_folder_and_leaves_a_file_to_enter() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::create_dir(folder.path().join("sub")).unwrap();
    fs::write(folder.path().join("sub").join("alice.mp3"), b"x").unwrap();
    fs::write(folder.path().join("talk.mp3"), b"x").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        folder.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("talk.mp3") && text.contains("sub/")
    });
    assert!(
        screen.contains("→ open folder"),
        "the footer names the right arrow:\n{screen}"
    );

    // Down to `sub/`, Right opens it as Enter does.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[C");
    tui.wait_for(WAIT, |text| text.contains("alice.mp3"));

    // Left goes back up; on a file Right does nothing - Enter is the one that uploads.
    tui.press(b"\x1b[D");
    tui.wait_for(WAIT, |text| text.contains("talk.mp3"));
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[C");
    let screen = tui.wait_for(WAIT, |text| text.contains("talk.mp3"));
    assert!(
        screen.contains("sub/") && !screen.contains("alice.mp3"),
        "Right on a file stays where it is:\n{screen}"
    );
}
