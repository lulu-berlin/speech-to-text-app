//! Roadmap item 67, parity amendment: the segment cursor and editing. Every arrow and `h`/`j`/`k`/`l`
//! moves the cursor between a transcript's segments and the current one is highlighted; `e` opens
//! the edit prompt prefilled with its text and Enter sends `PUT …/segments/{id}`, whose answer is the
//! updated turns; `v` sends `POST …/revert` for an edited segment and its text comes back.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, open_transcript};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// `turns.json` with the first turn's `segment` (by index) retexted to `text` and marked edited -
/// what the service answers an accepted edit with.
fn edited_turns(segment: usize, text: &str) -> Value {
    let mut turns: Value = serde_json::from_str(TURNS).unwrap();
    turns["turns"][0]["segments"][segment]["text"] = json!(text);
    turns["turns"][0]["segments"][segment]["edited"] = json!(true);
    turns
}

#[test]
fn the_cursor_moves_between_segments_and_e_edits_and_v_reverts_one() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // The first segment is the cursor's, and every arrow or vim key moves it.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"\x1b[C");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.press(b"j");
    tui.wait_for_highlight(WAIT, "Happy to be here.");
    tui.press(b"h");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.press(b"\x1b[D");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"l");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.press(b"k");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"\x1b[B");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.press(b"\x1b[A");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"j");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");

    // `e` opens the prompt prefilled with the segment's text; Enter sends the PUT.
    tui.press(b"e");
    tui.wait_for(WAIT, |text| {
        text.contains("Segment text: Thanks for joining us today.")
    });
    tui.press(b"!");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| !text.contains("Segment text:"));
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/2 "),
        "{:?}",
        edit.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&edit.request().body).unwrap(),
        json!({ "text": "Thanks for joining us today.!" })
    );
    let edited = edited_turns(1, "Thanks for joining us today.!");
    edit.answer("200 OK", &edited.to_string());
    tui.wait_for(WAIT, |text| text.contains("Thanks for joining us today.!"));

    // `v` puts the original text back.
    tui.press(b"v");
    let revert = service.next_command(WAIT);
    assert!(
        revert
            .request()
            .line
            .starts_with("POST /api/tasks/3/segments/2/revert "),
        "{:?}",
        revert.request().line
    );
    revert.answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| {
        text.contains("Thanks for joining us today.") && !text.contains("today.!")
    });
}

#[test]
fn an_edit_is_trimmed_before_it_is_sent() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // The contract trims an edit first: padding the prompt must not reach the service.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    tui.press(&[0x7f; "Good morning.".len()]);
    tui.press(b"  Hello there.  ");
    tui.press(b"\r");
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/1 "),
        "{:?}",
        edit.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&edit.request().body).unwrap(),
        json!({ "text": "Hello there." })
    );

    let edited = edited_turns(0, "Hello there.");
    edit.answer("200 OK", &edited.to_string());
    tui.wait_for(WAIT, |text| text.contains("Hello there."));
}

#[test]
fn an_unchanged_edit_sends_nothing() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // Enter on the untouched prompt closes it and sends nothing; the next edit is then the first
    // request the service sees, which pins the absence of the first.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| {
        !text.contains("Segment text:") && text.contains("Happy to be here.")
    });
    assert!(
        !service.command_arrived(),
        "an unchanged edit sends nothing:\n{screen}"
    );

    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    tui.press(b"!");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| !text.contains("Segment text:"));
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/1 "),
        "{:?}",
        edit.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&edit.request().body).unwrap(),
        json!({ "text": "Good morning.!" })
    );
}

#[test]
fn a_refused_edit_keeps_the_draft_with_the_services_reason() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // A refused edit puts the draft back in the prompt, the service's reason above it, and the
    // turn is left as it was.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    tui.press(&[0x7f; "Good morning.".len()]);
    tui.press(b"Hello there.");
    tui.press(b"\r");
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/1 "),
        "{:?}",
        edit.request().line
    );
    edit.answer(
        "400 Bad Request",
        r#"{"error":"invalid-text","message":"text is too long"}"#,
    );
    let screen = tui.wait_for(WAIT, |text| {
        text.contains("Segment text: Hello there.")
            && text.contains("edit failed: text is too long")
    });
    assert!(
        screen.contains("Good morning."),
        "the turn is unchanged:\n{screen}"
    );
}

#[test]
fn an_empty_segment_shows_empty_and_stays_selectable() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();

    // A segment the service answers with no text reads `[empty]`: the cursor holds it, and moved
    // off it is muted like a turn's clock.
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: Good morning."));
    tui.press(&[0x7f; "Good morning.".len()]);
    tui.press(b"\r");
    let edit = service.next_command(WAIT);
    assert!(
        edit.request()
            .line
            .starts_with("PUT /api/tasks/3/segments/1 "),
        "{:?}",
        edit.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&edit.request().body).unwrap(),
        json!({ "text": "" })
    );
    edit.answer("200 OK", &edited_turns(0, "").to_string());

    tui.wait_for(WAIT, |text| text.contains("[empty]"));
    tui.wait_for_highlight(WAIT, "[empty]");
    tui.press(b"l");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("[empty]"));
    assert_eq!(
        scene.fg("[empty]"),
        scene.fg("00:00"),
        "the empty segment is muted like the clock"
    );
}
