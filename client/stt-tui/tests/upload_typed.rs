//! Roadmap item 67, second amendment: a typed path in the upload dialog. A folder path - absolute,
//! relative to the folder the dialog shows, or starting with `~` - and Enter open that folder; a
//! file path, relative ones included, chooses the file; a path that does not exist is reported
//! inside the dialog, which stays open and sends nothing.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn typed_folders_open_typed_files_upload_and_a_missing_path_is_reported_in_the_dialog() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    // base/ holds top.wav and sub/inner.wav; HOME holds music/song.wav.
    let base = tempfile::tempdir().unwrap();
    fs::create_dir(base.path().join("sub")).unwrap();
    fs::write(base.path().join("top.wav"), b"x").unwrap();
    fs::write(base.path().join("sub").join("inner.wav"), b"x").unwrap();
    let home = tempfile::tempdir().unwrap();
    fs::create_dir(home.path().join("music")).unwrap();
    fs::write(home.path().join("music").join("song.wav"), b"RIFF-FAKE").unwrap();
    let mut tui = Tui::start_in_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        110,
        24,
        base.path(),
        &[("HOME", home.path().to_str().unwrap())],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("top.wav"));

    // A path relative to the shown folder opens it.
    tui.press(b"sub\r");
    tui.wait_for(WAIT, |text| {
        text.contains("inner.wav") && !text.contains("top.wav")
    });

    // An absolute path opens it.
    tui.press(format!("{}\r", base.path().display()).as_bytes());
    tui.wait_for(WAIT, |text| {
        text.contains("top.wav") && !text.contains("inner.wav")
    });

    // `~` is the home folder.
    tui.press(b"~/music\r");
    tui.wait_for(WAIT, |text| {
        text.contains("song.wav") && !text.contains("top.wav")
    });

    // A path that does not exist is reported inside the dialog, which stays open; nothing is sent.
    tui.press(b"missing/x.wav\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("does not exist"));
    assert!(
        screen
            .lines()
            .any(|line| line.contains("does not exist") && line.contains('│')),
        "the message is inside the dialog's border:\n{screen}"
    );
    assert!(
        screen.contains("song.wav"),
        "the dialog stays open:\n{screen}"
    );
    assert!(!service.upload_arrived(), "a missing path sends nothing");

    // A file path relative to the shown folder chooses that file.
    tui.press(b"song.wav\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload
            .request()
            .body
            .contains(r#"name="file"; filename="song.wav""#),
        "{}",
        upload.request().body
    );
}

#[test]
fn tab_completes_a_typed_paths_files_and_folders() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let base = tempfile::tempdir().unwrap();
    fs::create_dir(base.path().join("clips")).unwrap();
    fs::write(base.path().join("top.wav"), b"x").unwrap();
    fs::write(base.path().join("clips").join("talk.mp3"), b"RIFF-FAKE").unwrap();
    fs::write(base.path().join("clips").join("talk.wav"), b"RIFF-FAKE").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        110,
        24,
        base.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("top.wav"));

    // `cl` + Tab completes the one folder, with the platform's separator (`\` on Windows).
    let sep = std::path::MAIN_SEPARATOR;
    tui.press(b"cl");
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| {
        text.contains(&format!("Or type a path   clips{sep}"))
    });

    // `tal` + Tab completes as far as the two files agree, `talk.`.
    tui.press(b"tal");
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| {
        text.contains(&format!("Or type a path   clips{sep}talk."))
    });

    // `mp3` + Tab completes the one file whole; Enter uploads it.
    tui.press(b"mp3");
    tui.press(b"\t");
    tui.wait_for(WAIT, |text| {
        text.contains(&format!("Or type a path   clips{sep}talk.mp3"))
    });
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload.request().body.contains(r#"filename="talk.mp3""#),
        "{}",
        upload.request().body
    );
}
