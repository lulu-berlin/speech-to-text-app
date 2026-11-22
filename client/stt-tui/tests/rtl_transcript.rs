//! Roadmap item 76: the TUI draws a right-to-left transcript in the order the window draws it. A
//! terminal neither reorders nor shapes text, so the TUI orders each line itself (the Unicode bidi
//! algorithm, the turn's first letter deciding its direction) and writes the cells in visual
//! order; an RTL turn's row is mirrored - its header `<name>  <time>` at the right edge, its text
//! right-aligned under the name - and a Latin-first turn stays as it was.
//!
//! The expected rows are the screen's cells left to right. They are never written as visual-order
//! Arabic: each is built from the stored words, a right-to-left run's characters reversed with
//! `reversed`, the runs in the order they appear on screen.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::json;
use terminal::{OpenTranscript, open_transcript_sized, open_transcript_sized_on, style, token};

const WAIT: Duration = Duration::from_secs(20);
/// Twenty seconds of silence at 16 kHz, mono: long enough for the five-second seek into the RTL turn.
const CLIP: &[u8] = include_bytes!("fixtures/playback-20s.flac");

/// A Latin-first turn with an Arabic run: an LTR paragraph.
const MIXED: &str = "WHO: مرحبا بكم";
/// An Arabic-first turn with a number inside it: an RTL paragraph.
const WITH_NUMBER: &str = "في عام 2024 بدأنا";
/// A Latin turn after the Arabic one: LTR again.
const AFTER: &str = "Thanks.";

/// A right-to-left run's characters as the cells hold them, left to right.
fn reversed(run: &str) -> String {
    run.chars().rev().collect()
}

/// Three one-segment turns, 5 s apart, alternating Speaker 1 and Speaker 2.
fn turns() -> String {
    let turn = |index: u64, text: &str| {
        json!({
            "speakerId": index % 2,
            "startMs": index * 5000,
            "endMs": index * 5000 + 4000,
            "text": text,
            "segments": [{
                "id": index + 1,
                "startMs": index * 5000,
                "endMs": index * 5000 + 4000,
                "text": text,
                "edited": false,
            }],
        })
    };
    json!({
        "hasMerges": false,
        "speakers": [
            {"speakerId": 0, "speaker": "Speaker 1"},
            {"speakerId": 1, "speaker": "Speaker 2"},
        ],
        "turns": [turn(0, MIXED), turn(1, WITH_NUMBER), turn(2, AFTER)],
    })
    .to_string()
}

/// [`turns`] with the first speaker named in Arabic, for the rename caret and the playing row.
fn turns_with_arabic_name() -> String {
    turns().replace("Speaker 1", "أحمد")
}

/// Opens [`turns`] with a clip, playing from the start, so a five-second seek reaches the RTL turn.
fn open_rtl_playing() -> OpenTranscript {
    let mut open = open_transcript_sized_on(&turns(), 80, &[("STT_AUDIO_SINK", "frozen")]);
    open.tui.press(b" ");
    open.service
        .next_get_from(&open.tui, WAIT)
        .answer_bytes("200 OK", CLIP);
    open.tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('⏸')
    });
    open
}

/// The screen row whose text holds `needle`.
fn row_with<'a>(screen: &'a str, needle: &str) -> &'a str {
    screen
        .lines()
        .find(|line| line.contains(needle))
        .unwrap_or_else(|| panic!("a row holding {needle:?}:\n{screen}"))
}

#[test]
fn an_rtl_transcript_is_drawn_in_visual_order_and_its_rtl_turns_are_mirrored() {
    let OpenTranscript {
        service: _service,
        tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns(), 80);

    // The mixed turn (LTR): `WHO:` at the left, then its Arabic run, whose two words read from the
    // right - so the run's characters, reversed as one, follow `WHO: `.
    let mixed = format!("WHO: {}", reversed("مرحبا بكم"));
    // The Arabic turn (RTL): read from the right edge, `في عام`, then `2024` with its digits in
    // their own order, then `بدأنا` - so left to right the cells hold `بدأنا` reversed, the
    // number, and `في عام` reversed.
    let with_number = format!("{} 2024 {}", reversed("بدأنا"), reversed("في عام"));
    let screen = tui.wait_for(WAIT, |text| {
        text.contains(&mixed) && text.contains(&with_number) && text.contains(AFTER)
    });

    // An LTR turn as before: `00:00  Speaker 1` from the left border, its text indented under the
    // name, past the time.
    assert!(
        terminal::turn(&screen, "00:00", "Speaker 1", &mixed),
        "the mixed turn reads left to right:\n{screen}"
    );
    assert!(
        terminal::turn(&screen, "00:10", "Speaker 1", AFTER),
        "the Latin turn after the Arabic one stays left to right:\n{screen}"
    );

    // The RTL turn mirrored: its header `Speaker 2  00:05` against the right border, its text
    // right-aligned the same seven columns from it as an LTR text is from the left.
    let header = row_with(&screen, "Speaker 2  00:05");
    assert!(
        header.ends_with("Speaker 2  00:05│"),
        "the RTL turn's header sits at the right edge:\n{screen}"
    );
    let text = row_with(&screen, &with_number);
    assert!(
        text.ends_with(&format!("{with_number}       │")),
        "the RTL turn's text is right-aligned under its name:\n{screen}"
    );
}

#[test]
fn a_click_on_an_rtl_row_marks_the_segment_drawn_there() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns(), 80);

    let mixed = format!("WHO: {}", reversed("مرحبا بكم"));
    let with_number = format!("{} 2024 {}", reversed("بدأنا"), reversed("في عام"));
    let screen = tui.wait_for(WAIT, |text| {
        text.contains(&mixed) && text.contains(&with_number) && text.contains(AFTER)
    });

    // A click inside each row's text marks its segment: the hit map reads the cells left to right,
    // so the column under the click is the segment's, though the Arabic turn's text is drawn
    // mirrored. The mixed turn's row is LTR; the Arabic turn's row is RTL; the Latin turn's row is
    // LTR again.
    for needle in [&mixed, &with_number, AFTER] {
        let (column, row) = terminal::position_of(&screen, needle);
        tui.click(column + 1, row);
        tui.wait_for_highlight(WAIT, needle);
    }
}

#[test]
fn the_rename_caret_sits_at_the_end_of_an_rtl_name() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns_with_arabic_name(), 80);

    tui.wait_for(WAIT, |text| text.contains("أحمد"));
    tui.press(b"r");
    tui.wait_for_screen(WAIT, |screen| {
        if screen.hide_cursor() {
            return false;
        }
        let text = terminal::screen_text(screen);
        let Some((index, line)) = text
            .lines()
            .enumerate()
            .find(|(_, line)| line.contains("Rename: أحمد"))
        else {
            return false;
        };
        let prefix = "Rename: ";
        let start = line.chars().take(line.find(prefix).unwrap()).count();
        let draft = line[line.find(prefix).unwrap()..]
            .chars()
            .skip(prefix.chars().count())
            .take_while(|character| *character != '│')
            .collect::<String>();
        let draft = draft.trim_end().chars().count();
        let (row, column) = screen.cursor_position();
        index == row as usize && column as usize == start + prefix.chars().count() + draft
    });
}

#[test]
fn the_playing_segment_is_filled_in_an_rtl_row() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_rtl_playing();

    // The playhead starts in the first turn (LTR); `]` seeks five seconds on, into the Arabic turn.
    tui.press(b"]");
    tui.wait_for(WAIT, |text| text.contains("0:05 / 19:45"));
    let scene = tui.wait_for_scene(WAIT, |text| text.contains("0:05 / 19:45"));

    let with_number = format!("{} 2024 {}", reversed("بدأنا"), reversed("في عام"));
    let row = scene.row_of(&with_number);
    assert_eq!(
        style(&scene, row, &with_number),
        (
            token(&["colors", "accentText"], "dark"),
            token(&["colors", "accent"], "dark")
        ),
        "the playing segment's row is filled:\n{}",
        scene.text()
    );
}
