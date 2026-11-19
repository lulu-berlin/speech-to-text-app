//! Roadmap item 67, the review (R9): under `NO_COLOR` the upload dialog shows its selection as
//! every other list does - with `>` - since nothing is coloured: the file list's selected row, the
//! option chip the keyboard is on and the chooser's cursor. A click still lands on what is drawn
//! under it, the marker's columns counted.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::fs;
use std::time::Duration;

use terminal::{EventService, Tui, position_of};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The line of `text` that holds `needle`, or an empty one.
fn line_with<'a>(text: &'a str, needle: &str) -> &'a str {
    text.lines()
        .find(|line| line.contains(needle))
        .unwrap_or_default()
}

#[test]
fn under_no_color_the_upload_dialog_marks_its_selection_and_takes_clicks() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF").unwrap();
    fs::write(folder.path().join("b.mp3"), b"RIFF").unwrap();
    let mut tui = Tui::start_in_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
        folder.path(),
        &[("NO_COLOR", "1")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // Down from `../` onto a.mp3: its row is marked, b.mp3's is not.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("b.mp3")
    });
    tui.press(b"\x1b[B");
    let screen = tui.wait_for(WAIT, |text| text.contains("> [ ] a.mp3"));
    assert!(
        !line_with(&screen, "b.mp3").contains('>'),
        "only the selected row is marked:\n{screen}"
    );

    // A click on b.mp3's box, right of the marker's columns, ticks it.
    let (column, row) = position_of(&screen, "[ ] b.mp3");
    tui.click(column + 1, row);
    tui.wait_for(WAIT, |text| text.contains("[✓] b.mp3"));

    // Tab reaches the options: the language chip the keyboard is on is marked.
    tui.press(b"\t");
    let screen = tui.wait_for(WAIT, |text| text.contains("> Language"));
    assert!(
        !line_with(&screen, "Speakers").contains("> Speakers"),
        "only the focused chip is marked:\n{screen}"
    );

    // Down opens its choices with the cursor on the current one; Down moves it on.
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| text.contains("> ✓ auto"));
    tui.press(b"\x1b[B");
    tui.wait_for(WAIT, |text| {
        text.contains("  ✓ auto") && !text.contains("> ✓ auto") && text.contains(">   ")
    });
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| !text.contains("✓ auto"));

    // A click on the speakers chip's value opens its choices.
    let chips = line_with(&screen, "Speakers");
    let (_, row) = position_of(&screen, chips);
    let arrow = chips.rfind('▾').unwrap();
    let column = chips[..arrow].chars().count() as u16;
    tui.click(column - 2, row);
    tui.wait_for(WAIT, |text| text.contains("┌Speakers┐"));
}

/// Discovered in R9: under `NO_COLOR` the keys are named in brackets, so the guidance line is wider
/// than a colour terminal's - and the dialog is as wide as that line, drawn whole.
#[test]
fn under_no_color_the_guidance_line_stands_whole() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let folder = tempfile::tempdir().unwrap();
    fs::write(folder.path().join("a.mp3"), b"RIFF").unwrap();
    let mut tui = Tui::start_in_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        100,
        30,
        folder.path(),
        &[("NO_COLOR", "1")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"u");
    let guidance = stt_client::text("tui.browse.markHint", &[]);
    assert!(guidance.contains("[Space]"), "{guidance}");
    let screen = tui.wait_for(WAIT, |text| text.contains(guidance.as_str()));
    assert!(
        screen.lines().all(|line| line.chars().count() <= 100),
        "the screen fits its 100 columns:\n{screen}"
    );
}
