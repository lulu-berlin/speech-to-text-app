//! Roadmap item 67 (the user, 2026-11-12: "'assign' and 'new' should not appear in the footer when
//! they can't be used"): a transcript's footer shows a key only while it can act. `assign` and
//! `new speaker` need marked segments, and without them Enter plays from the cursor's segment;
//! `revert` needs an edited segment; `merge` two speakers; the page, start and end keys two
//! segments.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, offers, open_transcript_with};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
/// One speaker who said one thing: nobody to merge into, nowhere to page to.
const ONE_SEGMENT: &str = r#"{"hasMerges":false,"speakers":[{"speakerId":0,"speaker":"Speaker 1"}],"turns":[{"speakerId":0,"startMs":0,"endMs":2100,"text":"Good morning.","segments":[{"id":1,"startMs":0,"endMs":2100,"text":"Good morning.","edited":false}]}]}"#;
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_transcript_footer_offers_a_key_only_while_it_can_act() {
    // The first segment was edited, so the cursor starts on one `v` can revert.
    let turns = TURNS.replacen(r#""edited": false"#, r#""edited": true"#, 1);
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_with(&turns);

    // Nothing marked: Enter plays from the cursor's segment, and there is nothing to assign.
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &[
                "Enter play from here",
                "revert",
                "merge",
                "PgUp/PgDn/^U/^D page",
                "Home/g start",
                "End/G end",
            ],
            &["Enter move to…", "new speaker"],
        )
    });

    // The next segment was not edited: nothing to revert.
    tui.press(b"l");
    tui.wait_for(WAIT, |screen| offers(screen, &["edit"], &["revert"]));

    // A marked segment can be moved - Enter opens where to, `n` makes a new speaker; Enter no longer
    // plays.
    tui.press(b"a");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["Enter move to…", "new speaker"],
            &["Enter play from here"],
        )
    });

    // Unmarked again, the two go.
    tui.press(b"a");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["Enter play from here"],
            &["Enter move to…", "new speaker"],
        )
    });
}

#[test]
fn a_one_segment_transcript_offers_no_merge_and_no_paging() {
    let OpenTranscript {
        service: _service,
        tui,
        stream: _stream,
        state: _state,
    } = open_transcript_with(ONE_SEGMENT);
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["edit", "rename"],
            &["merge", "PgUp/PgDn/^U/^D page", "Home/g start", "End/G end"],
        )
    });
}
