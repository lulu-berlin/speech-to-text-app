//! Roadmap item 67 (the user, 2026-11-12: "make sure that none appear when it cannot be used"): the
//! Speakers panel's footer offers `Reset name` only for a speaker whose name is not its default, and
//! names Enter by what it does - rename, or assign while segments are marked.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, offers, open_transcript};

const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_speakers_panel_offers_reset_name_and_names_enter_by_what_it_does() {
    let OpenTranscript {
        service: _service,
        mut tui,
        mut stream,
        state: _state,
    } = open_transcript();

    // Up from the first segment reaches the panel on its last speaker, Speaker 2 - a default name.
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |screen| {
        offers(
            screen,
            &["speakers", "Enter rename", "merge"],
            &["Reset name", "Enter assign"],
        )
    });

    // Renamed elsewhere, the speaker's name can be reset.
    stream.send(
        "speakers-renamed",
        r#"{"id":3,"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Bob"}]}"#,
    );
    tui.wait_for(WAIT, |screen| offers(screen, &["Reset name"], &[]));

    // A marked segment: Enter on the panel now moves it to the speaker.
    tui.press(b"\x1b[B");
    tui.press(b"a");
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["Enter assign"], &["Enter rename"])
    });
}
