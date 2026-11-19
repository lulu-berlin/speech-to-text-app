//! Roadmap item 67, the second review (S14, ADR 0070): the frame report is the pty tests' alone.
//! A TUI started without `STT_TUI_FRAME_LOG` - as a user starts it - writes no file: neither in
//! the folder it runs in nor beside its service files.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::path::Path;
use std::thread;
use std::time::{Duration, Instant};

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

/// The names of the files in `dir`, sorted.
fn files(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir)
        .unwrap()
        .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}

#[test]
fn without_the_variable_the_tui_writes_no_frame_file() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    let before = (files(state.path()), files(folder.path()));
    let mut tui = Tui::start_unreported(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        folder.path(),
    );
    let mut stream = service.next_stream(Duration::from_secs(20));
    stream.send("snapshot", SNAPSHOT);

    // Frames drawn: the queue, then the key reference opened over it. No report is waited for -
    // there is none - so the screen is read as it stands.
    let shown = |tui: &Tui, text: &str| {
        let deadline = Instant::now() + Duration::from_secs(20);
        while !tui.text().contains(text) {
            assert!(
                Instant::now() < deadline,
                "{text:?} never drawn:\n{}",
                tui.text()
            );
            thread::sleep(Duration::from_millis(20));
        }
    };
    shown(&tui, "memo.wav");
    tui.press(b"?");
    shown(&tui, "Esc");

    assert_eq!(
        (files(state.path()), files(folder.path())),
        before,
        "the TUI wrote no file"
    );
}
