//! Roadmap item 67 (the user's report 2026-11-08: merge and reassign are unclear; the workflow should be like the GUI): while a speaker is marked for a merge, or segments are marked for a reassignment, the transcript says so in place and names the next step - so neither flow has to be guessed from the key reference.//!
//! The line appears under the Speakers panel while a mark stands and goes with it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::open_transcript;

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn a_speaker_being_merged_or_selected_segments_say_what_comes_next() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    tui.wait_for(WAIT, |text| text.contains("Transcript: interview.mp3"));

    // `m` marks the selected speaker: the line names it and the next step, and `m` again unmarks.
    tui.press(b"m");
    let screen = tui.wait_for(WAIT, |text| text.contains("Merging Speaker 1"));
    assert!(
        screen.contains("go to another speaker and press m to merge it into that one"),
        "the merge line names the next step:\n{screen}"
    );
    assert!(screen.contains("m cancels"), "{screen}");
    tui.press(b"m");
    tui.wait_for(WAIT, |text| !text.contains("Merging Speaker 1"));

    // `a` marks the current segment: the line counts them and names how to assign them.
    tui.press(b"a");
    let screen = tui.wait_for(WAIT, |text| text.contains("1 segment selected"));
    assert!(
        screen.contains("press Enter to choose where they go"),
        "the reassign line names the next step:\n{screen}"
    );
    assert!(screen.contains("a cancels"), "{screen}");
    tui.press(b"a");
    tui.wait_for(WAIT, |text| !text.contains("segments selected"));
}
