//! Roadmap item 67 (the user's report 2026-11-08: "there is no way to select the speakers; the
//! arrow keys should reach the speakers when going up from the edge"): a transcript's Speakers
//! panel takes the keyboard. Up on the first segment selects the bottom-most speaker, Up/Down move
//! between speakers, Down past the last returns to the transcript, and Enter renames the focused
//! speaker unless segments are selected for moving. No speaker row is highlighted while the
//! segment cursor moves.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{open_transcript, screen_text};

const WAIT: Duration = Duration::from_secs(20);

/// The screen rows of the speakers in the panel: the two rows under the panel's title row, so a
/// row added above the panel does not move this test's aim.
fn speaker_rows(screen: &vt100::Screen) -> [usize; 2] {
    let top = screen_text(screen)
        .lines()
        .position(|line| line.contains("Speakers"))
        .expect("the Speakers panel is on screen");
    [top + 1, top + 2]
}

/// Whether the panel row `row` is tinted: a cell of it is off the page's background (the accent
/// tint); an unselected row sits on the page's.
fn tinted(screen: &vt100::Screen, row: usize) -> bool {
    let page = terminal::page_background(screen);
    (0..screen.size().1.get()).any(|column| {
        screen
            .cell(row as u16, column)
            .is_some_and(|cell| cell.bgcolor() != page)
    })
}

/// Whether the `index`th speaker's row is tinted.
fn row_tinted(screen: &vt100::Screen, index: usize) -> bool {
    tinted(screen, speaker_rows(screen)[index])
}

/// No speaker row is tinted: the panel does not have the keyboard.
fn none_tinted(screen: &vt100::Screen) -> bool {
    speaker_rows(screen).iter().all(|row| !tinted(screen, *row))
}

#[test]
fn up_from_the_first_segment_reaches_the_speakers_and_enter_renames_the_focused_one() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    tui.wait_for(WAIT, |text| text.contains("Transcript: interview.mp3"));
    // The cursor starts on the first segment: the segment is highlighted, no speaker row is.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.wait_for_screen(WAIT, none_tinted);

    // Up at the edge selects the bottom-most speaker; Up again the one above.
    tui.press(b"\x1b[A");
    tui.wait_for_screen(WAIT, |screen| row_tinted(screen, 1));
    tui.wait_for_screen(WAIT, |screen| !row_tinted(screen, 0));
    tui.press(b"\x1b[A");
    tui.wait_for_screen(WAIT, |screen| row_tinted(screen, 0));
    tui.wait_for_screen(WAIT, |screen| !row_tinted(screen, 1));

    // Enter renames the focused speaker - not the segment, and nothing is assigned.
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 1"));
    assert!(screen.contains("Transcript: interview.mp3"), "{screen}");
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Rename: Speaker 1"));

    // Down walks back down the speakers and past the last one returns to the transcript.
    tui.press(b"\x1b[B");
    tui.wait_for_screen(WAIT, |screen| row_tinted(screen, 1));
    tui.press(b"\x1b[B");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.wait_for_screen(WAIT, none_tinted);

    // Moving the segment cursor again does not rename anything: Enter on a segment is not the
    // Speakers panel's Enter, and no speaker row is highlighted.
    tui.press(b"\x1b[B");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.wait_for_screen(WAIT, none_tinted);
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| !text.contains("Rename: "));
}
