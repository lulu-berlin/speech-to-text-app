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
fn the_rename_prompt_draws_an_rtl_name_in_visual_order_with_the_cursor_where_typing_goes() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns_with_arabic_name(), 80);

    // `r` opens the rename prompt on the first speaker's Arabic name: an RTL name, right-aligned in
    // the field after the label - the cells hold it reversed against the row's end - and the next
    // Arabic letter lands left of it, so the cursor sits on the free cell there.
    tui.wait_for(WAIT, |text| text.contains("أحمد"));
    tui.press(b"r");
    let label = "Rename: ";
    let field = 80 - label.chars().count();
    let right_aligned = |name: &str| {
        let width = name.chars().count();
        (
            format!("{label}{}{name}", " ".repeat(field - width)),
            label.chars().count() + field - width - 1,
        )
    };
    let (cells, column) = right_aligned(&reversed("أحمد"));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));

    // Retyped with a Latin first letter, the name is left to right again, the cursor after it.
    erase(&mut tui, "أحمد");
    tui.press(b"Sam");
    let cells = format!("{label}Sam");
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, 8 + 3));

    // A name without a letter is left to right, as a standalone name is: it does not take a
    // paragraph's direction.
    erase(&mut tui, "Sam");
    tui.press(b"12");
    let cells = format!("{label}12");
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, 8 + 2));

    // Digits first and then Arabic: the first letter makes the name RTL. Read from the right, `12`
    // comes first in its own order, then `د.` and `أحمد` - so left to right the cells hold the
    // Arabic part reversed and then ` 12`.
    erase(&mut tui, "12");
    tui.press("12 د. أحمد".as_bytes());
    let (cells, column) = right_aligned(&format!("{} 12", reversed("د. أحمد")));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));
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

/// The edit prompt's label.
const LABEL: &str = "Segment text: ";
/// The prompt's field at 80 columns: the row after the label.
const FIELD: usize = 80 - 14;

/// Whether `screen` shows a prompt's row as `cells`, left to right from its label (its trailing
/// blanks aside), with the cursor shown on that row at `column`.
fn prompt_shows(screen: &vt100::Screen, cells: &str, column: usize) -> bool {
    if screen.hide_cursor() {
        return false;
    }
    let text = terminal::screen_text(screen);
    let Some((index, _)) = text
        .lines()
        .enumerate()
        .find(|(_, line)| line.trim_end() == cells)
    else {
        return false;
    };
    let (row, at) = screen.cursor_position();
    index == row as usize && at as usize == column
}

/// Erases `text`'s characters from the prompt, one Backspace each.
fn erase(tui: &mut terminal::Tui, text: &str) {
    for _ in text.chars() {
        tui.press(b"\x7f");
    }
}

#[test]
fn the_edit_prompt_draws_a_hebrew_draft_in_visual_order_with_the_cursor_where_typing_goes() {
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized(&turns(), 80);
    tui.wait_for(WAIT, |text| text.contains(AFTER));

    // The first segment's text replaced by a Hebrew one, as the user did: `e` opens the prompt on
    // the first segment's text, Backspace clears its characters, and the Hebrew is typed.
    let draft = "שלום רב מה שלומך";
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains("Segment text: WHO:"));
    erase(&mut tui, MIXED);
    tui.press(draft.as_bytes());

    // A draft that is all Hebrew is one right-to-left run, right-aligned in the field after the
    // label: the cells hold it reversed, against the row's end. Each new letter lands left of the
    // run, so the cursor sits on the free cell there.
    let width = draft.chars().count();
    let cells = format!("{LABEL}{}{}", " ".repeat(FIELD - width), reversed(draft));
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, 14 + FIELD - width - 1)
    });

    // Erased back and retyped with a Latin first letter, the draft is left to right again, from
    // the field's start; its Arabic run is reversed in place, and the next Arabic letter lands at
    // that run's left end, so the cursor sits on the run's first cell.
    erase(&mut tui, draft);
    tui.press("WHO: مرحبا".as_bytes());
    let cells = format!("{LABEL}WHO: {}", reversed("مرحبا"));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, 14 + 5));

    // A draft wider than the field shows its end, the part being typed: its start is cut behind a
    // `…`, which a right-to-left draft shows at the right; one cell stays free for the cursor.
    erase(&mut tui, "WHO: مرحبا");
    let long = [draft; 5].join(" ");
    tui.press(long.as_bytes());
    let tail: String = long
        .chars()
        .skip(long.chars().count() - (FIELD - 2))
        .collect();
    let cells = format!("{LABEL} {}…", reversed(&tail));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, 14));

    // A draft without a letter takes its paragraph's direction: in the Arabic turn, `2024` is
    // right-aligned, the cursor left of it.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains(LABEL));
    let with_number = format!("{} 2024 {}", reversed("بدأنا"), reversed("في عام"));
    tui.press(b"j");
    tui.wait_for_highlight(WAIT, &with_number);
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains(LABEL));
    erase(&mut tui, WITH_NUMBER);
    tui.press(b"2024");
    let cells = format!("{LABEL}{}2024", " ".repeat(FIELD - 4));
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, 14 + FIELD - 4 - 1)
    });
}
