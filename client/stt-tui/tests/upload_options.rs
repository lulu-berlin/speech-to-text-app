//! Roadmap item 67, parity amendment: the upload dialog's options. They start from the stored
//! settings, as the GUI's upload panel does, and the chosen values travel as the contract's
//! `language`, `speakers` and `maxSpeakers` multipart fields.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui, multipart_field};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = r#"{"device":"auto","threads":4,"defaultLanguage":"de","speakersMode":"fixed","speakersCount":3,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","appLanguage":null,"themeMode":"dark","availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{},"deviceProbeError":null}"#;
const WAIT: Duration = Duration::from_secs(20);

/// The multipart field `name`'s value in `body`.
#[test]
fn the_dialog_starts_from_the_settings_and_sends_the_chosen_options() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("talk.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));

    // `u`: the settings are read, and the dialog's options start from them.
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    // The dialog opens at once and takes the stored options when the read is answered.
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));

    // Tab to the language and Enter opens its list; the next code after `de` is `es`.
    tui.press(b"\t");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Language   es ▾"));

    // A typed path with Enter uploads the file with these options.
    let path = files.path().join("talk.mp3");
    tui.press(path.to_str().unwrap().as_bytes());
    tui.press(b"\r");
    let upload = service.next_upload(WAIT);
    let body = &upload.request().body;
    assert_eq!(multipart_field(body, "language"), Some("es"), "{body}");
    assert_eq!(multipart_field(body, "speakers"), Some("3"), "{body}");
    assert_eq!(multipart_field(body, "maxSpeakers"), Some("3"), "{body}");
    assert!(body.contains(r#"filename="talk.mp3""#), "{body}");
}

#[test]
fn the_chips_open_their_lists_with_enter_and_a_click_and_choose() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));

    // Enter on the language chip opens its list at the stored code; Down and Enter choose another,
    // the dialog staying open.
    tui.press(b"\t");
    tui.press(b"\r");
    let list = tui.wait_for(WAIT, |text| {
        text.contains("✓ de  german") && text.contains("en  english")
    });
    assert!(list.contains("Upload audio"), "the dialog stays:\n{list}");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        let language = text
            .lines()
            .find(|line| line.contains("Language") && line.contains("▾"))
            .unwrap_or_default();
        language.contains("▾") && !language.contains("de ▾")
    });

    // Escape closes a list alone, keeping the value and the dialog.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("auto  auto-detect"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && !text.contains("auto  auto-detect")
    });

    // A click on the speakers chip opens its list; Down twice reaches the count `5`.
    let (column, row) = terminal::position_of(&screen, "3 ▾");
    tui.click(column, row);
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Speakers   5 ▾"));
}

#[test]
fn a_click_selects_a_file_and_a_click_outside_closes_the_dialog() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    fs::create_dir(files.path().join("clips")).unwrap();
    fs::write(files.path().join("clips/b.wav"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("a.mp3") && text.contains("clips/")
    });

    // A click selects the file: Space marks what the click selected.
    let (column, row) = terminal::position_of(&screen, "a.mp3");
    tui.click(column, row);
    tui.press(b" ");
    let screen = tui.wait_for(WAIT, |text| text.contains("[✓] a.mp3"));

    // A double click on the folder enters it.
    let (column, row) = terminal::position_of(&screen, "clips/");
    tui.click(column, row);
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| text.contains("b.wav"));
    assert!(
        screen.contains("Upload audio"),
        "the dialog stays:\n{screen}"
    );

    // A click outside the dialog closes it, as Escape does.
    tui.click(0, 0);
    tui.wait_for(WAIT, |text| !text.contains("Upload audio"));
}

#[test]
fn tab_and_the_arrows_move_between_the_files_and_the_two_chips() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();
    fs::write(files.path().join("b.wav"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));

    // Tab puts the keyboard on the settings area; Down opens the focused chip's list - the
    // language's - and Escape closes it alone.
    tui.press(b"\t");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("auto  auto-detect"));
    // The Escape's frame is awaited, so the Right below cannot reach the TUI with it as Alt-Right.
    // Left and right move between the two chips without changing either value.
    tui.press(b"\x1b[C");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Language   de ▾") && text.contains("Speakers   3 ▾")
    });

    // While a list was open the arrows moved it alone: up returns to the files, and the file
    // selection is still the first row - one Down selects `a.mp3`.
    assert!(
        screen.contains("Upload audio"),
        "the dialog stays open:\n{screen}"
    );
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.wait_for(WAIT, |text| text.contains("[✓] a.mp3"));
}

#[test]
fn typing_narrows_the_language_list_and_backspace_widens_it() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    fs::write(files.path().join("a.mp3"), b"RIFF-FAKE-WAVE").unwrap();

    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        140,
        30,
        files.path(),
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    tui.wait_for(WAIT, |text| text.contains("Speakers   3 ▾"));

    // Tab to Language, Enter opens its list; typing `span` narrows it to the Spanish code.
    tui.press(b"\t");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    tui.press(b"span");
    tui.wait_for(WAIT, |text| {
        text.contains("es  spanish") && !text.contains("de  german")
    });

    // Backspace widens it again; typing the filter once more and Enter chooses `es`.
    tui.press(b"\x7f\x7f\x7f\x7f");
    tui.wait_for(WAIT, |text| text.contains("de  german"));
    tui.press(b"span");
    tui.wait_for(WAIT, |text| text.contains("es  spanish"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Language   es ▾"));
}
