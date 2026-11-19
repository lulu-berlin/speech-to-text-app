//! Roadmap item 67 (the user's report 2026-11-08: speakers in the text should bear the same
//! colour as they have on top): a transcript turn's speaker name carries the colour the Speakers
//! panel gives that speaker.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{Scene, open_transcript};

const WAIT: Duration = Duration::from_secs(20);

/// The foreground of the first cell holding `needle` on `row`.
fn fg(scene: &Scene, row: usize, needle: &str) -> vt100::Color {
    let text = scene.row_text(row);
    let byte = text
        .find(needle)
        .unwrap_or_else(|| panic!("{needle:?} is on row {row}:\n{text}"));
    let column = text[..byte].chars().count();
    scene.cell(row, column).unwrap().fg
}

#[test]
fn a_turns_speaker_name_carries_the_panels_colour() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    tui.wait_for(WAIT, |text| text.contains("Happy to be here."));
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("Happy to be here."));

    // The panel's colours come from its own rows - the first on screen with each name - so the
    // test does not repeat the tokens.
    let first_panel = fg(&scene, scene.row_of("Speaker 1"), "Speaker 1");
    let second_panel = fg(&scene, scene.row_of("Speaker 2"), "Speaker 2");
    assert_ne!(first_panel, second_panel, "the panel tints the speakers");

    let first_turn = fg(&scene, scene.row_of("00:00  Speaker 1"), "Speaker 1");
    let second_turn = fg(&scene, scene.row_of("00:05  Speaker 2"), "Speaker 2");
    assert_eq!(first_turn, first_panel, "Speaker 1s turn");
    assert_eq!(second_turn, second_panel, "Speaker 2s turn");
}
