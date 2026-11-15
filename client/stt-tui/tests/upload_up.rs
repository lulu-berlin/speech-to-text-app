//! Roadmap item 67, second amendment: going up a folder in the upload dialog. The list starts with
//! `../`; Enter on it, Backspace with no typed path and Left each go up one folder, the folder line
//! saying where the dialog is; at the root there is no `../` and going up stays there.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn enter_on_dotdot_backspace_and_left_each_go_up_a_folder() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    // recordings/2026/q4/ with a file at each level, so each folder is recognisable by its file.
    let files = tempfile::tempdir().unwrap();
    let q4 = files.path().join("recordings").join("2026").join("q4");
    fs::create_dir_all(&q4).unwrap();
    fs::write(files.path().join("recordings").join("top.wav"), b"x").unwrap();
    fs::write(
        files
            .path()
            .join("recordings")
            .join("2026")
            .join("year.wav"),
        b"x",
    )
    .unwrap();
    fs::write(q4.join("deep.wav"), b"x").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        100,
        24,
        &q4,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The dialog opens in q4/, its list headed by `../`, which is selected first.
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| text.contains("deep.wav"));
    let dotdot = screen.lines().position(|line| line.contains("../"));
    let file = screen.lines().position(|line| line.contains("deep.wav"));
    assert!(
        dotdot.is_some_and(|dotdot| file.is_some_and(|file| dotdot < file)),
        "`../` heads the list:\n{screen}"
    );

    // Enter on the selected `../` goes up to 2026/.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        text.contains("year.wav") && !text.contains("deep.wav")
    });

    // Backspace with nothing typed goes up to recordings/.
    tui.press(b"\x7f");
    tui.wait_for(WAIT, |text| {
        text.contains("top.wav") && !text.contains("year.wav")
    });

    // Left goes up too: the temporary directory that holds recordings/.
    tui.press(b"\x1b[D");
    tui.wait_for(WAIT, |text| {
        text.contains("recordings/") && !text.contains("top.wav")
    });

    // At the root `../` is gone and going up keeps the root: its folder line reads "Folder  /"
    // followed by blanks, which no deeper folder's line does.
    let at_root = |text: &str| text.contains("Folder  / ");
    tui.press(b"/\r");
    let root = tui.wait_for(WAIT, at_root);
    assert!(!root.contains("../"), "the root has no `../`:\n{root}");
    tui.press(b"\x1b[D");
    tui.press(b"\x7f");
    std::thread::sleep(Duration::from_millis(300));
    let still = tui.text();
    assert!(
        at_root(&still),
        "going up at the root stays there:\n{still}"
    );
}
