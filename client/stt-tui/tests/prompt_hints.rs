//! Roadmap item 67 (the user, 2026-11-11: "while editing, the shortcut keys are disabled, so they
//! shouldn't be visible"): while a text prompt has the keyboard - a segment edit, a speaker rename,
//! History's search - the footer shows only that prompt's keys, not the page's.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use terminal::{OpenTranscript, offers, open_transcript_with};

const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);
/// The page keys a prompt takes away.
const PAGE: &[&str] = &["edit", "Space play", "rename", "back", "? help"];

#[test]
fn a_text_prompt_shows_only_its_own_keys() {
    // "Good morning." was edited, so its prompt can revert it.
    let mut edited: Value = serde_json::from_str(TURNS).unwrap();
    edited["turns"][0]["segments"][0]["text"] = json!("Good morning!");
    edited["turns"][0]["segments"][0]["edited"] = json!(true);
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_with(&edited.to_string());
    tui.wait_for(WAIT, |text| offers(text, &["edit", "Space play"], &[]));

    // The edited segment's prompt: save, revert, cancel and erase - nothing of the page.
    tui.press(b"e");
    tui.wait_for(WAIT, |text| {
        text.contains("Segment text: Good morning!")
            && offers(
                text,
                &[
                    "Enter save",
                    "Ctrl-R revert",
                    "Esc cancel",
                    "Backspace erase",
                ],
                PAGE,
            )
    });

    // Closed, the page's keys come back. An unedited segment's prompt has nothing to revert.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| offers(text, &["edit"], &["Enter save"]));
    tui.press(b"l");
    tui.press(b"e");
    tui.wait_for(WAIT, |text| {
        text.contains("Segment text: Thanks for joining us today.")
            && offers(
                text,
                &["Enter save", "Esc cancel", "Backspace erase"],
                &["Ctrl-R revert", "edit", "Space play"],
            )
    });
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| offers(text, &["edit"], &["Enter save"]));

    // The rename prompt: save, cancel and erase.
    tui.press(b"r");
    tui.wait_for(WAIT, |text| {
        text.contains("Rename:")
            && offers(
                text,
                &["Enter save", "Esc cancel", "Backspace erase"],
                &["Ctrl-R revert", "edit", "Space play", "back"],
            )
    });
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| offers(text, &["edit"], &["Enter save"]));

    // History's search: search, cancel and erase - not History's own keys.
    tui.press(b"b");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    tui.press(b"/");
    tui.wait_for(WAIT, |text| {
        offers(
            text,
            &["Enter search", "Esc cancel", "Backspace erase"],
            &["Enter open", "/search", "filter", "Space play", "back"],
        )
    });
}
