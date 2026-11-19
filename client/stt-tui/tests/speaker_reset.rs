//! Roadmap item 67: a speaker's name can be reset to its default from the Speakers panel - R while
//! the panel has the keyboard - by sending null, and the panel and the turns read `Speaker N`
//! again.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, open_transcript, turn};

/// `turns.json` again: the speakers back at their defaults, as a reset leaves them.
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn resetting_the_focused_speakers_name_sends_null_and_restores_the_default() {
    let OpenTranscript {
        service,
        mut tui,
        mut stream,
        state: _state,
    } = open_transcript();

    // The service reports the first speaker renamed, as another client would leave it.
    stream.send(
        "speakers-renamed",
        r#"{"id":3,"speakers":[{"speakerId":0,"speaker":"Alice"},{"speakerId":1,"speaker":"Speaker 2"}]}"#,
    );
    tui.wait_for(WAIT, |text| turn(text, "00:00", "Alice", "Good morning."));

    // R on the Speakers panel resets the focused speaker's name - the one above the first
    // segment, reached by Up twice.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.press(b"R");
    let reset = service.next_command(WAIT);
    assert!(
        reset
            .request()
            .line
            .starts_with("PUT /api/tasks/3/speakers/0 "),
        "{:?}",
        reset.request().line
    );
    assert_eq!(reset.request().body, r#"{"speaker":null}"#);
    reset.answer(
        "200 OK",
        r#"{"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Speaker 2"}]}"#,
    );

    // The turns are read again and the default name is back on both the panel and the turn.
    let refetch = service.next_get_from(&tui, WAIT);
    assert!(
        refetch
            .request()
            .line
            .starts_with("GET /api/tasks/3/turns "),
        "{:?}",
        refetch.request().line
    );
    refetch.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        turn(text, "00:00", "Speaker 1", "Good morning.") && !text.contains("Alice")
    });
}

#[test]
fn reset_on_a_speaker_named_by_default_says_so_and_sends_nothing() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    tui.wait_for(WAIT, |text| {
        text.contains("Speaker 1") && text.contains("Good morning.")
    });

    // The panel has the keyboard on Speaker 1, whose name is its default already: the footer offers
    // no reset, and R - pressed anyway - resets nothing and says what it found.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.wait_for(WAIT, |text| {
        text.contains("Enter rename") && !text.contains("Reset name")
    });
    tui.press(b"R");
    // The notice proves the key was handled, so the check below can fail when a write goes out
    // (the review, T3).
    tui.wait_for(WAIT, |text| text.contains("Speaker 1 is its default name"));
    assert!(!service.command_arrived(), "no write for a default name");
}
