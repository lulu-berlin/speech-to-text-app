//! Roadmap item 67 (the user, 2026-11-12: "The selected segments are not marked in any way"): while
//! segments are marked every segment shows a box before it - `☑` on a marked one, `☐` on the others,
//! as the browser's reassign mode shows a checkbox - and the boxes go when the last mark does.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn marked_segments_carry_a_ticked_box_and_the_others_an_empty_one() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // Nothing marked: no boxes.
    tui.wait_for(WAIT, |screen| {
        screen.contains("Good morning.") && !screen.contains('☐') && !screen.contains('☑')
    });

    // `a` marks the cursor's segment: it is ticked, and every other segment shows an empty box.
    tui.press(b"a");
    tui.wait_for(WAIT, |screen| {
        screen.contains("☑ Good morning.")
            && screen.contains("☐ Thanks for joining us today.")
            && screen.contains("☐ Happy to be here.")
            && screen.contains("☐ Let's start with your background.")
    });

    // `A` marks the whole turn: both of its segments are ticked.
    tui.press(b"A");
    tui.wait_for(WAIT, |screen| {
        screen.contains("☑ Good morning.")
            && screen.contains("☑ Thanks for joining us today.")
            && screen.contains("☐ Happy to be here.")
    });

    // `A` again unmarks the turn - the last marks - and the boxes go.
    tui.press(b"A");
    tui.wait_for(WAIT, |screen| {
        screen.contains("Good morning.") && !screen.contains('☐') && !screen.contains('☑')
    });
}
