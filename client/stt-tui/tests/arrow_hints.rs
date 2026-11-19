//! Roadmap item 67, the review (R10): a click on a footer hint shown as an arrow does what the
//! arrow key does - in the upload dialog `[←]` goes up a folder and `[→]` opens the selected one -
//! and never types the arrow into the path.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The footer's column and row of `arrow` in the hint that names `words`: the screen's last line
/// that holds both.
fn hint_arrow(screen: &str, arrow: char, words: &str) -> (u16, u16) {
    let (row, line) = screen
        .lines()
        .enumerate()
        .filter(|(_, line)| line.contains(arrow) && line.contains(words))
        .last()
        .unwrap_or_else(|| panic!("a hint {arrow} {words:?} in the footer:\n{screen}"));
    let column = line[..line.find(arrow).unwrap()].chars().count();
    (column as u16, row as u16)
}

#[test]
fn clicking_an_arrow_hint_does_what_the_arrow_key_does() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let real = tempfile::tempdir().unwrap();
    // On Unix the folder is reached through a link, as macOS's temporary folder is (`/var` is
    // `/private/var`), so every Unix shows what macOS does; Windows keeps the path as given.
    #[cfg(unix)]
    let (folder, _links) = {
        let links = tempfile::tempdir().unwrap();
        let link = links.path().join("link");
        std::os::unix::fs::symlink(real.path(), &link).unwrap();
        (link, links)
    };
    #[cfg(not(unix))]
    let folder = real.path().to_path_buf();
    let sub = folder.join("sub");
    fs::create_dir(&sub).unwrap();
    fs::write(sub.join("x.mp3"), b"RIFF").unwrap();
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        &sub,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The dialog names a folder as the OS reports it (the second review, S12).
    let parent = format!("Folder  {}", terminal::os_folder(&folder).display());
    let inside = format!("Folder  {}", terminal::os_folder(&sub).display());

    // The dialog opens in `sub`; a click on the footer's ← goes up to its parent.
    tui.press(b"u");
    let screen = tui.wait_for(WAIT, |text| {
        text.contains(&inside) && text.contains("x.mp3") && text.contains("Backspace up")
    });
    let (column, row) = hint_arrow(&screen, '←', "Backspace up");
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| text.contains(&parent) && text.contains("sub/"));
    assert!(
        !screen.contains("Or type a path"),
        "the arrow was not typed into a path:\n{screen}"
    );

    // Down onto `sub/`, and a click on the footer's → opens it.
    tui.press(b"\x1b[B");
    let screen = tui.wait_for(WAIT, |text| text.contains("open folder"));
    let (column, row) = hint_arrow(&screen, '→', "open folder");
    tui.click(column, row);
    tui.wait_for(WAIT, |text| {
        text.contains(&inside) && text.contains("x.mp3")
    });
}
