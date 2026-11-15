//! Roadmap item 67 (the user's report 2026-11-08: the layout should be such that turns are easy to
//! recognize): consecutive turns are separated by a blank row, and a turn's continuation lines are
//! indented, so a turn reads as one block - its speaker, time and text.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::open_transcript;

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn turns_are_separated_by_a_blank_row_and_their_continuations_are_indented() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    tui.wait_for(WAIT, |text| text.contains("Happy to be here."));
    // Narrow enough that the first turn wraps: its text continues under the speaker's line.
    tui.resize(40, 24);
    // The redrawn frame, not the wide one clipped: the transcript panel closes inside 40 columns.
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.lines()
            .any(|line| line.starts_with("┌Transcript") && line.trim_end().ends_with('┐'))
    });

    // Each turn's first row starts with its speaker's name.
    let first = scene.row_of("00:00  Speaker 1");
    let second = scene.row_of("00:05  Speaker 2");
    assert!(
        second >= first + 2,
        "a blank row stands between the turns:\n{}",
        scene.text()
    );

    // The first turn's continuation sits above that blank row, indented off the left edge, and is
    // not another turn (no speaker name on it).
    let continuation = (first + 1..second - 1)
        .map(|row| scene.row_text(row))
        .find(|line| !line.trim().is_empty())
        .unwrap_or_else(|| panic!("the turn wraps at 40 columns:\n{}", scene.text()));
    let continuation = continuation.trim_start_matches('│');
    assert!(
        continuation.starts_with(' ') && !continuation.trim_start().starts_with("Speaker"),
        "the continuation is indented under its turn: {continuation:?}"
    );
}
