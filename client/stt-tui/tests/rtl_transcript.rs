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
use terminal::{
    EventService, OpenTranscript, Tui, open_transcript_sized, open_transcript_sized_on, style,
    token,
};

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

/// Three one-segment turns with `texts`, 5 s apart, alternating the first speaker (named `first`)
/// and Speaker 2.
fn turns_of(texts: [&str; 3], first: &str) -> String {
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
            {"speakerId": 0, "speaker": first},
            {"speakerId": 1, "speaker": "Speaker 2"},
        ],
        "turns": [turn(0, texts[0]), turn(1, texts[1]), turn(2, texts[2])],
    })
    .to_string()
}

/// The mixed, the Arabic and the Latin turn, Speaker 1's and Speaker 2's.
fn turns() -> String {
    turns_of([MIXED, WITH_NUMBER, AFTER], "Speaker 1")
}

/// [`turns`] with the first speaker named in Arabic, for the rename caret and the playing row.
fn turns_with_arabic_name() -> String {
    turns_of([MIXED, WITH_NUMBER, AFTER], "أحمد")
}

/// One Hebrew turn of two segments: `שלום רב` first, `מה שלומך` second.
fn two_segment_hebrew_turn() -> String {
    json!({
        "hasMerges": false,
        "speakers": [{"speakerId": 0, "speaker": "Speaker 1"}],
        "turns": [{
            "speakerId": 0,
            "startMs": 0,
            "endMs": 4000,
            "text": "שלום רב מה שלומך",
            "segments": [
                {"id": 1, "startMs": 0, "endMs": 2000, "text": "שלום רב", "edited": false},
                {"id": 2, "startMs": 2000, "endMs": 4000, "text": "מה שלומך", "edited": false},
            ],
        }],
    })
    .to_string()
}

/// Opens [`turns`] with a clip, playing from the start, so a five-second seek reaches the RTL turn.
fn open_rtl_playing() -> OpenTranscript {
    let mut open = open_transcript_sized_on(&turns(), 80, &[("STT_AUDIO_SINK", "frozen")]);
    play_from_start(&open.service, &mut open.tui);
    open
}

/// Starts the transcript's playback with Space - the service answers the clip's GET - and waits
/// until it plays from the start.
fn play_from_start(service: &EventService, tui: &mut Tui) {
    tui.press(b" ");
    service
        .next_get_from(tui, WAIT)
        .answer_bytes("200 OK", CLIP);
    tui.wait_for(WAIT, |text| {
        text.contains("0:00 / 19:45") && text.contains('⏸')
    });
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
fn with_rtl_words_each_rtl_word_keeps_its_letters_in_typed_order() {
    // kitty's default mode turns each right-to-left word's letters itself and keeps the words left
    // to right; with STT_TUI_RTL_WORDS set (any value but `0`) the TUI orders the words and aligns
    // the rows, and writes each RTL word's letters in typed order for kitty to turn. The transcript:
    // an Arabic speaker name, an Arabic comma after a word, and a Hebrew word with niqqud.
    let seeded = turns_of(["WHO: مرحبا، بكم", WITH_NUMBER, "Thanks שָׁלוֹם."], "أحمد");
    let OpenTranscript {
        service: _service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized_on(&seeded, 80, &[("STT_TUI_RTL_WORDS", "yes")]);

    // The mixed turn (LTR): `WHO:`, then its Arabic run read from the right - `مرحبا`, the comma,
    // `بكم` - so left to right `بكم`, the comma, `مرحبا`, each word in typed order; the comma is
    // not a letter and stays where the order put it.
    let mixed = "WHO: بكم ،مرحبا";
    // The Arabic turn (RTL): from the right edge `في`, `عام`, `2024`, `بدأنا` - so left to right
    // `بدأنا 2024 عام في`, each word in typed order.
    let with_number = "بدأنا 2024 عام في";
    // The Latin turn: its Hebrew word, marks and all, in typed order.
    let latin = "Thanks שָׁלוֹם.";
    let screen = tui.wait_for(WAIT, |text| {
        text.contains(mixed) && text.contains(with_number) && text.contains(latin)
    });

    // The Arabic speaker name in both of its turns' headers is a word in typed order too.
    assert!(
        terminal::turn(&screen, "00:00", "أحمد", mixed),
        "the mixed turn reads left to right, its Arabic words in typed order:\n{screen}"
    );
    assert!(
        row_with(&screen, with_number).ends_with(&format!("{with_number}       │")),
        "the RTL turn's text is right-aligned, its words in typed order:\n{screen}"
    );
    assert!(
        terminal::turn(&screen, "00:10", "أحمد", latin),
        "the Latin turn's Hebrew word is in typed order:\n{screen}"
    );

    // The rename prompt: the Arabic name right-aligned in typed order, the cursor where it is in
    // the visual order - the free cell left of the name.
    tui.press(b"r");
    let (cells, column) = right_aligned("Rename: ", "أحمد");
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));
}

#[test]
fn in_kitty_the_rtl_words_mode_is_the_default() {
    // A stock kitty (`force_ltr no`) turns each right-to-left word's letters itself: where the
    // environment names kitty - its TERM, or its window id under another TERM - and
    // STT_TUI_RTL_WORDS is unset or empty, the TUI writes each RTL word's letters in typed order,
    // as it does with the variable set. The mixed turn: `WHO:`, then its Arabic run's words left
    // to right `بكم`, `مرحبا`, each in typed order; the Arabic turn: from the right edge `في`,
    // `عام`, `2024`, `بدأنا`.
    let typed = |text: &str| text.contains("WHO: بكم مرحبا") && text.contains("بدأنا 2024 عام في");
    // Otherwise the rows are in visual order, each RTL word reversed.
    let visual = |text: &str| text.contains(&format!("WHO: {}", reversed("مرحبا بكم")));
    let cases: [(&[(&str, &str)], bool); 5] = [
        (&[("TERM", "xterm-kitty")], true),
        (
            &[("TERM", "xterm-256color"), ("KITTY_WINDOW_ID", "1")],
            true,
        ),
        (&[("TERM", "xterm-kitty"), ("STT_TUI_RTL_WORDS", "")], true),
        // `0` is kitty with `force_ltr yes`: it draws the visual order as written.
        (
            &[("TERM", "xterm-kitty"), ("STT_TUI_RTL_WORDS", "0")],
            false,
        ),
        // A terminal that names no kitty keeps the visual order.
        (&[("TERM", "xterm-256color")], false),
    ];
    for (env, words) in cases {
        let OpenTranscript {
            service: _service,
            tui,
            stream: _stream,
            state: _state,
        } = open_transcript_sized_on(&turns(), 80, env);
        let screen = tui.wait_for(WAIT, |text| typed(text) || visual(text));
        assert_eq!(
            typed(&screen),
            words,
            "under {env:?} each RTL word is in typed order: {words}\n{screen}"
        );
    }
}

#[test]
fn with_rtl_words_a_click_and_the_cursor_land_where_the_words_are_drawn() {
    // [`two_segment_hebrew_turn`]: read from the right edge, the first segment's words come first
    // - so left to right the row holds the second segment's words, then the first's, each word in
    // typed order for kitty to turn.
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript_sized_on(
        &two_segment_hebrew_turn(),
        80,
        &[("STT_TUI_RTL_WORDS", "1"), ("STT_AUDIO_SINK", "frozen")],
    );
    let second = "שלומך מה";
    let screen = tui.wait_for(WAIT, |text| text.contains(&format!("{second} רב שלום")));

    // A click inside `שלומך`, the row's leftmost word, marks the second segment - the one kitty
    // shows there - and not the first.
    let (column, row) = terminal::position_of(&screen, second);
    tui.click(column + 2, row);
    tui.wait_for_highlight(WAIT, second);
    assert!(
        !tui.marked_text().contains("רב"),
        "only the second segment is marked: {:?}",
        tui.marked_text()
    );

    // `a` marks it for a move: each segment's box comes before its words in reading order - at
    // their right - so the ticked box frames the second segment's words and the empty one the
    // first's.
    tui.press(b"a");
    tui.wait_for(WAIT, |text| text.contains(&format!("{second} ☑ רב שלום ☐")));

    // Its edit prompt: the draft right-aligned, its words in typed order, and the cursor on the
    // free cell left of it, where kitty shows the next letter of its last word.
    tui.press(b"e");
    let label = "Segment text: ";
    let (cells, column) = right_aligned(label, second);
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));

    // A Latin-first draft that ends in Hebrew: left to right, the Hebrew word in typed order, and
    // the cursor on that word's first cell - where the next Hebrew letter shows, as without the
    // mode.
    erase(&mut tui, "מה שלומך");
    tui.press("WHO: שלום".as_bytes());
    let cells = format!("{label}WHO: שלום");
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, label.chars().count() + 5)
    });
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains(label));

    // Playing from the start, the first segment is the playhead's: its words are filled in the
    // accent role where kitty draws them.
    play_from_start(&service, &mut tui);
    let scene = tui.wait_for_scene(WAIT, |text| text.contains('⏸'));
    let first = "רב שלום";
    let row = scene.row_of(first);
    assert_eq!(
        style(&scene, row, first),
        (
            token(&["colors", "accentText"], "dark"),
            token(&["colors", "accent"], "dark")
        ),
        "the playing segment's words are filled:\n{}",
        scene.text()
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
    let (cells, column) = right_aligned(label, &reversed("أحمد"));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));

    // Retyped with a Latin first letter, the name is left to right again, the cursor after it.
    erase(&mut tui, "أحمد");
    tui.press(b"Sam");
    let cells = format!("{label}Sam");
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, cells.chars().count())
    });

    // A name without a letter is left to right, as a standalone name is: it does not take a
    // paragraph's direction.
    erase(&mut tui, "Sam");
    tui.press(b"12");
    let cells = format!("{label}12");
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, cells.chars().count())
    });

    // Digits first and then Arabic: the first letter makes the name RTL. Read from the right, `12`
    // comes first in its own order, then `د.` and `أحمد` - so left to right the cells hold the
    // Arabic part reversed and then ` 12`.
    erase(&mut tui, "12");
    tui.press("12 د. أحمد".as_bytes());
    let (cells, column) = right_aligned(label, &format!("{} 12", reversed("د. أحمد")));
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

/// A prompt's row at 80 columns - `label`, then `cells` (a right-to-left draft as drawn) against
/// the row's end - and the cursor's column: the free cell left of them, where the next letter
/// lands.
fn right_aligned(label: &str, cells: &str) -> (String, usize) {
    let width = cells.chars().count();
    let blanks = 80 - label.chars().count() - width;
    (
        format!("{label}{}{cells}", " ".repeat(blanks)),
        80 - width - 1,
    )
}

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
    let label = "Segment text: ";
    let draft = "שלום רב מה שלומך";
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains(&format!("{label}WHO:")));
    erase(&mut tui, MIXED);
    tui.press(draft.as_bytes());

    // A draft that is all Hebrew is one right-to-left run, right-aligned in the field after the
    // label: the cells hold it reversed, against the row's end. Each new letter lands left of the
    // run, so the cursor sits on the free cell there.
    let (cells, column) = right_aligned(label, &reversed(draft));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));

    // Erased back and retyped with a Latin first letter, the draft is left to right again, from
    // the field's start; its Arabic run is reversed in place, and the next Arabic letter lands at
    // that run's left end, so the cursor sits on the run's first cell.
    erase(&mut tui, draft);
    tui.press("WHO: مرحبا".as_bytes());
    let cells = format!("{label}WHO: {}", reversed("مرحبا"));
    tui.wait_for_screen(WAIT, |screen| {
        prompt_shows(screen, &cells, label.chars().count() + 5)
    });

    // A draft wider than the field shows its end, the part being typed: its start is cut behind a
    // `…`, which a right-to-left draft shows at the right; one cell stays free for the cursor.
    erase(&mut tui, "WHO: مرحبا");
    let long = [draft; 5].join(" ");
    tui.press(long.as_bytes());
    let field = 80 - label.chars().count();
    let tail: String = long
        .chars()
        .skip(long.chars().count() - (field - 2))
        .collect();
    let (cells, column) = right_aligned(label, &format!("{}…", reversed(&tail)));
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));

    // A draft without a letter takes its paragraph's direction: in the Arabic turn, `2024` is
    // right-aligned, the cursor left of it.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains(label));
    let with_number = format!("{} 2024 {}", reversed("بدأنا"), reversed("في عام"));
    tui.press(b"j");
    tui.wait_for_highlight(WAIT, &with_number);
    tui.press(b"e");
    tui.wait_for(WAIT, |text| text.contains(label));
    erase(&mut tui, WITH_NUMBER);
    tui.press(b"2024");
    let (cells, column) = right_aligned(label, "2024");
    tui.wait_for_screen(WAIT, |screen| prompt_shows(screen, &cells, column));
}
