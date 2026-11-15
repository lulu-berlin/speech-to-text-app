//! Roadmap item 67 (the user's report 2026-11-13: "merge and reassign are either/or"): merging and
//! reassigning exclude each other, as in the browser. While segments are marked, merging, renaming,
//! resetting a speaker's name and resetting the merges are refused and their hints gone; while a
//! speaker is marked for a merge, marking segments is refused and its hints gone.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{offers, open_transcript_with};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn merging_and_reassigning_exclude_each_other() {
    // A task with merges and a renamed speaker, so every key the marks refuse has its hint.
    let turns = TURNS
        .replace(r#""hasMerges": false"#, r#""hasMerges": true"#)
        .replace(r#""speaker": "Speaker 1""#, r#""speaker": "Alice""#);
    let mut open = open_transcript_with(&turns);
    let tui = &mut open.tui;

    // Nothing marked: the transcript offers merge, rename and the merges reset.
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["merge", "rename", "Reset merges", "mark"], &[])
    });

    // A marked segment takes them away, and their keys do nothing. The stream stays live: a
    // dropped one would hide the write keys for a reason of its own.
    tui.press(b"a");
    tui.wait_for(WAIT, |screen| {
        screen.contains("● Live")
            && screen.contains("1 segment selected")
            && offers(
                screen,
                &["Enter move to…"],
                &["merge", "rename", "Reset merges"],
            )
    });
    tui.press(b"m");
    tui.press(b"r");
    tui.press(b"R");

    // On the Speakers panel - Up twice reaches Alice - neither her name's reset nor a merge is
    // offered while the segment stays marked; Enter would move it there.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |screen| {
        screen.contains("● Live") && offers(screen, &["Enter assign"], &["Reset name", "merge"])
    });
    tui.press(b"R");
    tui.press(b"m");

    // Back on the segment, `a` unmarks it: nothing the refused keys would have done shows, and
    // nothing was sent.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"a");
    let screen = tui.wait_for(WAIT, |screen| {
        !screen.contains("segments selected") && offers(screen, &["merge"], &[])
    });
    assert!(!screen.contains("Merging"), "no merge began:\n{screen}");
    assert!(!screen.contains("Rename:"), "no rename began:\n{screen}");
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !open.service.command_arrived(),
        "no reset was sent while segments were marked"
    );

    // A speaker marked for a merge takes the segment marks away, and their keys do nothing.
    let tui = &mut open.tui;
    tui.press(b"m");
    tui.wait_for(WAIT, |screen| {
        screen.contains("Merging Alice") && offers(screen, &[], &["mark", "All of the turn"])
    });
    tui.press(b"a");
    tui.press(b"A");
    tui.press(b"m");
    let screen = tui.wait_for(WAIT, |screen| {
        !screen.contains("Merging") && offers(screen, &["mark"], &[])
    });
    assert!(
        !screen.contains("segments selected"),
        "no segment was marked while merging:\n{screen}"
    );
}
