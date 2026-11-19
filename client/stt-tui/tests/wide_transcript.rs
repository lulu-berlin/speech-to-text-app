//! Roadmap item 67, the review (R6): transcript text in wide scripts is shown whole. A CJK or emoji
//! character takes two columns and a combining mark none, every character of a turn reaches the
//! screen over as many rows as it needs, and a click lands on the segment drawn under it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::json;
use terminal::{OpenTranscript, open_transcript_sized};

const WAIT: Duration = Duration::from_secs(20);

/// The first segment: Japanese, a word with a combining accent among it on Unix - Windows's ConPTY
/// advances a column for a combining mark, shifting the row and dropping the cell after it, so
/// there the word is plain (`docs/known-issues.md`, Tests) - longer than a row of a 120-column
/// terminal (eight times eleven wide characters is 176 columns).
fn first_segment() -> String {
    let word = if cfg!(windows) {
        "Cafeで"
    } else {
        "Cafe\u{301}で"
    };
    "東京で会議を開きます。".repeat(4) + word + &"東京で会議を開きます。".repeat(4)
}

/// The second segment: Japanese with an emoji, drawn after the first.
const SECOND: &str = "第二の段落です👋。";

/// One turn of Speaker 1 with two segments, `first` then `second`.
fn two_segments(first: &str, second: &str) -> String {
    json!({
        "hasMerges": false,
        "speakers": [{"speakerId": 0, "speaker": "Speaker 1"}],
        "turns": [{
            "speakerId": 0,
            "startMs": 0,
            "endMs": 9000,
            "text": format!("{first} {second}"),
            "segments": [
                {"id": 1, "startMs": 0, "endMs": 6000, "text": first, "edited": false},
                {"id": 2, "startMs": 6000, "endMs": 9000, "text": second, "edited": false},
            ],
        }],
    })
    .to_string()
}

/// The screen's text with the borders and each row's padding taken out, the rows run together: a
/// text wrapped over rows reads here as it was written.
fn run_together(text: &str) -> String {
    text.lines()
        .map(|line| line.replace(['│', '┌', '┐', '└', '┘', '─'], ""))
        .map(|line| line.trim().to_string())
        .collect()
}

#[test]
fn a_wide_script_transcript_shows_every_character_and_clicks_land_on_their_segment() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&two_segments(&first_segment(), SECOND), 120);
    let first = first_segment();

    // Every character of both segments is on screen, in order, wrapped over the rows it needs;
    // the segments are drawn one space apart, as every turn draws them.
    let scene = tui.wait_for_scene(WAIT, |text| run_together(text).contains(SECOND));
    let shown = run_together(&scene.text());
    assert!(
        shown.contains(&format!("{first} {SECOND}")),
        "the whole turn is drawn:\n{}",
        scene.text()
    );

    // A click on the second segment's 段, wherever the wrapping drew it, moves the cursor there.
    tui.wait_for_highlight(WAIT, &first);
    let (column, row) = scene.cell_of("段");
    tui.click(column, row);
    tui.wait_for_highlight(WAIT, SECOND);
}

/// The second review (S6): a click after emoji with a skin-tone modifier - one character of two
/// code points, drawn in two columns - lands on the segment drawn there.
#[test]
fn a_click_after_modifier_emoji_lands_on_the_segment_drawn_there() {
    let first = "👍🏽".repeat(10) + " good";
    let second = "目標です";
    let turns = two_segments(&first, second);
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns, 120);

    // A click on the second segment's 標 moves the cursor there.
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("標"));
    tui.wait_for_highlight(WAIT, "good");
    let (column, row) = scene.cell_of("標");
    tui.click(column, row);
    tui.wait_for_highlight(WAIT, second);
}

/// The second review (S6): a click after Arabic ligatures - `لا`, lam and alef, which a font joins
/// into one shape - lands on the segment drawn there.
#[test]
fn a_click_after_an_arabic_ligature_lands_on_the_segment_drawn_there() {
    let first = "لا".repeat(10) + " good";
    let second = "目標です";
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&two_segments(&first, second), 120);

    // A click on the second segment's 標 moves the cursor there.
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("標"));
    tui.wait_for_highlight(WAIT, "good");
    let (column, row) = scene.cell_of("標");
    tui.click(column, row);
    tui.wait_for_highlight(WAIT, second);
}
