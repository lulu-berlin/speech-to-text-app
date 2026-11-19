//! Roadmap item 67, parity amendment: the upload dialog. `u` opens a file browser in the current
//! directory - directories first, Enter enters one, Enter on a file uploads it - and Escape closes
//! it without a request.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_upload_dialog_browses_and_uploads_the_chosen_file() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::create_dir(files.path().join("clips")).unwrap();
    fs::write(files.path().join("clips/talk.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    fs::write(files.path().join("notes.txt"), b"not audio").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));

    // `u`: the dialog, listing this directory - the subdirectory first.
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("clips/"));
    assert!(screen.contains("notes.txt"), "{screen}");
    assert!(
        screen.contains("Folder  "),
        "the dialog names its directory:\n{screen}"
    );

    // Down past `../`, Enter enters the directory; Down past its `../`, Enter on the recording
    // uploads it.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("talk.mp3"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    assert!(
        upload.request().body.contains(r#"filename="talk.mp3""#),
        "{}",
        upload.request().body
    );
    upload.answer(
        "201 Created",
        r#"{"id":9,"filename":"talk.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));

    // Escape closes it without a request.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    assert!(
        !service.upload_arrived(),
        "a cancelled dialog sends nothing:\n{screen}"
    );
}

#[test]
fn ticking_a_file_keeps_its_size_and_date_in_their_columns() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), vec![0u8; 1000]).unwrap();
    fs::write(files.path().join("b.wav"), vec![0u8; 2000]).unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("a.mp3") && text.contains("b.wav")
    });

    // Space ticks `a.mp3`: its mark is four columns wide either way, so the size - and the date
    // behind it - stay in their columns.
    tui.press(b"\x1b[B");
    tui.press(b" ");
    let screen = tui.wait_for(WAIT, |text| text.contains("[✓] a.mp3"));
    let column = |name: &str| {
        let line = screen
            .lines()
            .find(|line| line.contains(name))
            .unwrap_or_else(|| panic!("{name} is listed:\n{screen}"));
        let at = line
            .find("KB")
            .unwrap_or_else(|| panic!("{name}'s size is listed:\n{screen}"));
        line[..at].chars().count()
    };
    assert_eq!(
        column("a.mp3"),
        column("b.wav"),
        "the size column holds when a file is ticked:\n{screen}"
    );
}

#[test]
fn a_click_on_the_checkbox_ticks_and_unticks_the_file() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("[ ] a.mp3"));

    // A click on the empty box ticks the file; a click on the tick unticks it.
    let (column, row) = terminal::position_of(&screen, "[ ] a.mp3");
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| text.contains("[✓] a.mp3"));
    let (column, row) = terminal::position_of(&screen, "[✓] a.mp3");
    tui.click(column, row);
    tui.wait_for(WAIT, |text| text.contains("[ ] a.mp3"));
}

#[test]
fn the_dialog_shrinks_to_the_height_its_content_needs() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        100,
        40,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("Esc cancel"));

    // One file: the dialog ends right under its own keys - the row below them holds its bottom
    // corner where the keys' row holds its left border - and no row further down carries that
    // border.
    let lines: Vec<&str> = screen.lines().collect();
    let keys = lines
        .iter()
        .position(|line| line.contains("Upload 0 files"))
        .unwrap_or_else(|| panic!("the dialog's keys:\n{screen}"));
    let left = lines[keys]
        .chars()
        .position(|character| character == '│')
        .unwrap_or_else(|| panic!("the dialog's left border:\n{screen}"));
    assert_eq!(
        lines[keys + 1].chars().nth(left),
        Some('└'),
        "the dialog ends with its content:\n{screen}"
    );
    assert!(
        lines[keys + 2..]
            .iter()
            .all(|line| line.chars().nth(left) != Some('│')),
        "nothing of the dialog below its corner:\n{screen}"
    );
}

#[test]
fn the_dialog_is_only_as_wide_as_its_longest_filename_needs() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        100,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("Upload audio"));

    // A short name: the dialog is only as wide as its own words need.
    let top = screen
        .lines()
        .find(|line| line.contains("Upload audio"))
        .unwrap_or_else(|| panic!("the dialog's top:\n{screen}"));
    let right = top.find('┐').map_or(0, |at| top[..at].chars().count());
    assert!(
        right < 94,
        "the dialog is as wide as its content ({right}):\n{screen}"
    );
    // Centred: the same margin on both sides.
    let line = top.chars().count();
    let left = top.find('┌').map_or(0, |at| top[..at].chars().count());
    assert!(
        left.abs_diff(line.saturating_sub(1).saturating_sub(right)) <= 1,
        "the margins match ({left} / {}):\n{screen}",
        line.saturating_sub(1).saturating_sub(right)
    );

    // A long one widens it: the dialog reopens on a folder holding a longer name.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
    fs::write(
        files.path().join(format!("{}-dialog.mp3", "a".repeat(88))),
        b"RIFF-FAKE-WAVE",
    )
    .unwrap();
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("aaa"));
    let screen = tui.text();
    let top = screen
        .lines()
        .find(|line| line.contains("Upload audio"))
        .unwrap_or_else(|| panic!("the dialog:\n{screen}"));
    let right = top.find('┐').map_or(0, |at| top[..at].chars().count());
    assert!(
        right > 94,
        "the long name widens the dialog ({right}):\n{screen}"
    );
}
