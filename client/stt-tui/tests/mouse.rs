//! Roadmap item 67, parity amendment: the mouse does what the keyboard does. Clicking a tab switches
//! pages, clicking a queue, history or settings row selects it, double-clicking a history row opens
//! its transcript, and clicking the queue's upload area opens the upload dialog - wherever the
//! notices and upload lines above have put them.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

/// The tab bar's row: the header's second, right of the stenographer's tile.
const TAB_ROW: u16 = 1;
/// The tile's width: the tabs start right of it.
const TILE: u16 = 11;

/// The screen row (zero-based) of the first line holding `needle`.
fn row_of(screen: &str, needle: &str) -> u16 {
    screen
        .lines()
        .position(|line| line.contains(needle))
        .unwrap_or_else(|| panic!("{needle:?} on the screen:\n{screen}")) as u16
}

#[test]
fn clicking_tabs_rows_the_upload_area_and_a_history_row_twice_does_what_the_keys_do() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Clicking the History tab switches pages.
    tui.click(13 + TILE, TAB_ROW);
    let screen = tui.wait_for(WAIT, |text| text.contains("interview.mp3"));

    // Double-clicking the history row opens its transcript.
    let row = row_of(&screen, "interview.mp3");
    tui.click(3, row);
    std::thread::sleep(Duration::from_millis(120));
    tui.click(3, row);
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));

    // Clicking the Queue tab goes back; clicking the upload area opens the upload dialog.
    tui.click(2 + TILE, TAB_ROW);
    let screen = tui.wait_for(WAIT, |text| text.contains("lecture.m4a"));
    tui.click(3, row_of(&screen, "Choose audio files"));
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("Esc cancel"));
    std::thread::sleep(Duration::from_millis(100));

    // Clicking the second queue row - under the running row's progress line - selects it: `d`
    // offers that row's file.
    tui.click(3, row_of(&screen, "memo.wav"));
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
    // Escape is read alone only once its effect shows: a key or click sent before that can arrive
    // in the same read and be parsed together with it.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete memo.wav?"));

    // Clicking the Settings tab and a setting's row selects it: Enter, Up and Enter raise the
    // threads and save them.
    tui.click(24 + TILE, TAB_ROW);
    // The settings read is auto-answered with the contract's defaults; the frontends read is answered
    // here, and the screen shows both before the row's value is read.
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Threads"));
    let screen = tui.wait_for(WAIT, |text| text.contains("Threads"));
    // The row says `auto` until a thread count is chosen: Up then picks the first one.
    let before: u32 = match screen
        .lines()
        .find(|line| line.contains("Threads"))
        .and_then(|line| line.trim_matches('│').split_whitespace().nth(1))
    {
        Some("auto") => 0,
        Some(value) => value
            .parse()
            .unwrap_or_else(|_| panic!("the threads row's value in:\n{screen}")),
        None => panic!("the threads row in:\n{screen}"),
    };
    tui.click(25, row_of(&screen, "Threads"));
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Enter save"));
    tui.press(b"\x1b[A\r");
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    assert!(
        write
            .request()
            .body
            .contains(&format!("\"threads\":{}", before + 1)),
        "the clicked row's Up raised the threads: {}",
        write.request().body
    );
    let saved = write.request().body.clone();
    write.answer("200 OK", &saved);
    tui.wait_for(WAIT, |text| {
        text.lines()
            .any(|line| line.contains("Threads") && line.contains(&(before + 1).to_string()))
    });
}

#[test]
fn clicks_hit_the_upload_area_and_the_rows_below_the_notices_and_upload_lines() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    std::fs::write(folder.path().join("a.mp3"), b"RIFF-FAKE-A").unwrap();
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

    // A finished task's notice, and a marked file whose answer is held: a line each above the
    // upload area.
    stream.send(
        "task-updated",
        r#"{"id":3,"filename":"interview.mp3","status":"COMPLETED","transcriptionProgress":1.0}"#,
    );
    tui.wait_for(WAIT, |text| {
        text.contains("interview.mp3 transcribed — Enter open")
    });
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    tui.press(b"\x1b[B");
    tui.press(b" ");
    tui.press(b"\r");
    let held = service.next_upload(WAIT);
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("uploading a.mp3 100%") && !text.contains("Upload audio")
    });

    // Clicking memo.wav where it is drawn - under the running row's progress line - selects it: `d`
    // offers that row's file.
    tui.click(3, row_of(&screen, "memo.wav"));
    tui.press(b"d");
    tui.wait_for(WAIT, |text| text.contains("Delete memo.wav?"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Delete memo.wav?"));
    std::thread::sleep(Duration::from_millis(100));

    // Clicking inside the upload area opens the dialog.
    tui.click(3, row_of(&screen, "Choose audio files"));
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));

    held.answer(
        "201 Created",
        r#"{"id":11,"filename":"a.mp3","status":"QUEUED","transcriptionProgress":0.0}"#,
    );
}
