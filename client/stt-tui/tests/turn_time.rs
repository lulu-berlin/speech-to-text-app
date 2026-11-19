//! Roadmap item 67 (the design, the user 2026-11-14): a turn reads `00:00  Speaker 1` on its first
//! row - the time in the muted colour, the speaker in its own tint - with the text on the rows
//! below, aligned past the time and in the page's text colour. A short name does not pull the text
//! left: the text always starts under the name.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{OpenTranscript, Scene, open_transcript, token};

/// `turns.json`'s speakers after "Speaker 2" (id 1) is renamed to "Bob".
const RENAMED: &str =
    r#"{"speakers":[{"speakerId":0,"speaker":"Speaker 1"},{"speakerId":1,"speaker":"Bob"}]}"#;

const WAIT: Duration = Duration::from_secs(20);

/// Row `row`'s text inside the transcript panel's borders.
fn inner(scene: &Scene, row: usize) -> String {
    scene
        .row_text(row)
        .trim_start_matches('│')
        .trim_end_matches(['│', ' '])
        .to_string()
}

/// The foreground of the first cell holding `needle` on `row`.
fn fg(scene: &Scene, row: usize, needle: &str) -> vt100::Color {
    terminal::style(scene, row, needle).0
}

#[test]
fn a_turns_time_leads_its_speaker_and_the_text_sits_below() {
    let mut open = open_transcript();
    let tui = &mut open.tui;
    tui.wait_for(WAIT, |text| text.contains("Happy to be here."));
    // Narrow enough that the first turn wraps, its text indented past the time.
    tui.resize(30, 26);
    // The redrawn frame, not the wide one clipped: the transcript panel closes inside 30 columns.
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.lines()
            .any(|line| line.starts_with("┌Transcript") && line.trim_end().ends_with('┐'))
    });
    let row = |needle: &str| {
        (0..scene.cells.len())
            .find(|row| scene.row_text(*row).contains(needle))
            .unwrap_or_else(|| panic!("{needle:?} is on screen:\n{}", scene.text()))
    };

    // The first row: the time, two spaces, the name.
    let first = row("00:00  Speaker 1");
    assert_eq!(inner(&scene, first), "00:00  Speaker 1", "{}", scene.text());
    // The next rows: the text, seven columns in, under the name.
    assert!(
        inner(&scene, first + 1).starts_with("       Good morning. Thanks"),
        "the text starts under the name:\n{}",
        scene.text()
    );
    assert!(
        inner(&scene, first + 2).starts_with("       for joining"),
        "every text row keeps the column:\n{}",
        scene.text()
    );

    // The second turn has the same shape.
    let short = row("00:05  Speaker 2");
    assert_eq!(
        inner(&scene, short + 1),
        "       Happy to be here.",
        "{}",
        scene.text()
    );

    // The colours: the time muted, the name in the speaker's tint, the text in the page's own.
    let muted = token(&["colors", "textMuted"], "dark");
    let clock = fg(&scene, short, "00:05");
    assert_eq!(clock, muted, "the time is drawn muted");
    assert_ne!(
        fg(&scene, short + 1, "Happy"),
        muted,
        "the text is the page's own colour, not the time's"
    );
    assert_ne!(fg(&scene, short, "Speaker"), muted, "the name is not muted");
}

#[test]
fn a_short_name_keeps_its_text_in_the_column_after_the_time() {
    let OpenTranscript {
        service,
        mut tui,
        stream: _stream,
        state: _state,
    } = open_transcript();
    // Speaker 2 becomes "Bob", narrower than the time.
    tui.press(b"l");
    tui.press(b"l");
    tui.press(b"r");
    tui.wait_for(WAIT, |text| text.contains("Rename: Speaker 2"));
    tui.press(&[0x7f; "Speaker 2".len()]);
    tui.press(b"Bob\r");
    service.next_command(WAIT).answer("200 OK", RENAMED);
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains("│Bob") && !text.contains("Rename:") && !text.contains("Speaker 2")
    });

    // Bob's turn: the time and the short name, its text still under the name.
    let short = scene.row_of("00:05  Bob");
    assert_eq!(inner(&scene, short), "00:05  Bob", "{}", scene.text());
    assert_eq!(
        inner(&scene, short + 1),
        "       Happy to be here.",
        "{}",
        scene.text()
    );
    // A name wider than the time does not move the text either.
    let long = scene.row_of("00:00  Speaker 1");
    assert!(
        inner(&scene, long + 1).starts_with("       Good morning."),
        "{}",
        scene.text()
    );
}
