//! Roadmap item 67 (the user, 2026-11-12: "make sure that none appear when it cannot be used"): the
//! upload dialog's footer offers `up` only below the root, `open folder` only on a folder or `../`
//! while the file list has the focus, and `move` only with two rows or more.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::path::Path;
use std::time::Duration;

use terminal::{EventService, EventStream, Tui, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// A TUI started in `folder` with the upload dialog open on it. The stand-in, the stream and the
/// bootstrap directory are returned to last as long as the test.
fn dialog_in(folder: &Path) -> (EventService, Tui, EventStream, tempfile::TempDir) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        folder,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    (service, tui, stream, state)
}

#[test]
fn the_upload_dialog_offers_its_keys_only_while_they_can_act() {
    let folder = tempfile::tempdir().unwrap();
    fs::create_dir(folder.path().join("sub")).unwrap();
    fs::write(folder.path().join("talk.mp3"), b"x").unwrap();
    let (_service, mut tui, _stream, _state) = dialog_in(folder.path());

    // `../` is selected: it goes up, as Right opens it; three rows to move between.
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &[
                "↑/↓ move",
                "Enter open or upload",
                "← or Backspace up",
                "→ open folder",
                "Tab options",
                "Esc cancel",
            ],
            &[],
        )
    });

    // On a file Right opens nothing - Enter is the one that uploads it.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["Enter open or upload"], &["→ open folder"])
    });

    // On `sub/` it opens again; on an option row Right changes the option instead.
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |screen| offers(screen, &["→ open folder"], &[]));
    tui.press(b"\t");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["Tab options"], &["→ open folder"])
    });
}

/// The review (R18): the footer names the keys of the part that has the keyboard. On a chip Left
/// picks the other chip, so no way up is offered; while a path is typed Tab completes it and
/// Backspace erases it, so neither `options` nor the way up is.
#[test]
fn the_footer_names_only_the_keys_of_the_focused_part() {
    let folder = tempfile::tempdir().unwrap();
    fs::create_dir(folder.path().join("sub")).unwrap();
    fs::write(folder.path().join("talk.mp3"), b"x").unwrap();
    let (_service, mut tui, _stream, _state) = dialog_in(folder.path());
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["← or Backspace up", "Tab options"], &[])
    });

    // Tab to the chips: Left there picks the other chip.
    tui.press(b"\t");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["Tab options"],
            &["→ open folder", "← or Backspace up"],
        )
    });

    // Tab back to the files, and a typed path: Tab completes it, Backspace erases it.
    tui.press(b"\t");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["← or Backspace up", "→ open folder"], &[])
    });
    tui.press(b"s");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["Enter open or upload", "Esc cancel"],
            &["Tab options", "← or Backspace up"],
        )
    });
}

#[test]
fn an_empty_folder_offers_no_move() {
    let folder = tempfile::tempdir().unwrap();
    let (_service, tui, _stream, _state) = dialog_in(folder.path());
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["← or Backspace up", "→ open folder"],
            &["↑/↓ move"],
        )
    });
}

#[test]
fn the_root_offers_no_way_up() {
    let (_service, tui, _stream, _state) = dialog_in(Path::new("/"));
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["↑/↓ move"], &["← or Backspace up"])
    });
}
