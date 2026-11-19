//! Roadmap item 67: a screen counts as painted when it is the frame the TUI reports having drawn
//! (the second review, S14; ADR 0070), so no pty test depends on how fast the machine is. What
//! changes from one frame to the next without being what a test reads - the stenographer's tile,
//! a running task's elapsed clock - is left out of that comparison (the review, T4; the second
//! review, S9), or a screen with a running task would never match its frame.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

/// A parsed screen with `text` written from its first row, as a frame would leave it.
fn screen(text: &str) -> vt100::Screen {
    let mut parser = vt100::Parser::new(
        std::num::NonZeroU16::new(6).unwrap(),
        std::num::NonZeroU16::new(40).unwrap(),
        0,
    );
    parser.process(text.as_bytes());
    parser.screen().clone()
}

#[test]
fn a_frame_that_differs_only_in_the_tile_or_clock_settles() {
    let drawn = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text   transcribing 1 task\r\n\
                 queue row\r\n\
                 lecture.m4a   Transcribing… · 933:24:08\r\n";
    let later = "\x1b[H\x1b[2J▚▞  ✎ Speech-to-Text   transcribing 1 task\r\n\
                 queue row\r\n\
                 lecture.m4a   Transcribing… · 933:24:09\r\n";
    assert_eq!(
        terminal::settled_text(&screen(drawn)),
        terminal::settled_text(&screen(later)),
        "the tile and the clock do not keep the screen and its frame apart"
    );

    let changed = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text   idle\r\n\
                   queue row\r\n\
                   lecture.m4a   Transcribing… · 933:24:08\r\n";
    assert_ne!(
        terminal::settled_text(&screen(drawn)),
        terminal::settled_text(&screen(changed)),
        "a screen the TUI drew differently is not the same one"
    );
}

/// The second review (S9): a task running for less than an hour shows its elapsed time as `mm:ss`;
/// that clock is left out as the `h:mm:ss` one is. A time elsewhere - a transcript turn's start -
/// still tells two screens apart.
#[test]
fn a_running_rows_mm_ss_clock_is_left_out_and_other_times_are_not() {
    let drawn = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text   transcribing 1 task\r\n\
                 queue row\r\n\
                 lecture.m4a   Transcribing… · 00:12\r\n";
    let later = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text   transcribing 1 task\r\n\
                 queue row\r\n\
                 lecture.m4a   Transcribing… · 00:13\r\n";
    assert_eq!(
        terminal::settled_text(&screen(drawn)),
        terminal::settled_text(&screen(later)),
        "the running row's mm:ss clock does not keep the screen and its frame apart"
    );

    // Below the stenographer's tile, which is left out whatever it holds.
    let turn = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text\r\n\r\n\r\n\
                00:05  Speaker 1\r\n";
    let other_turn = "\x1b[H\x1b[2J▞▚  ✎ Speech-to-Text\r\n\r\n\r\n\
                      00:06  Speaker 1\r\n";
    assert_ne!(
        terminal::settled_text(&screen(turn)),
        terminal::settled_text(&screen(other_turn)),
        "a turn's start time is the TUI's drawing, not a running clock"
    );
}
