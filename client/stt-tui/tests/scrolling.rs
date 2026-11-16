//! Roadmap item 67, parity amendment: a transcript longer than the terminal. The view follows the
//! segment cursor, so the current segment stays visible; PageUp/PageDown and Ctrl-U/Ctrl-D page it,
//! Home/End and `g`/`G` jump to the first and last segment, and the footer says so.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::json;
use terminal::{EventService, Tui, WIDE, screen_text};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// A transcript of 30 one-segment turns, alternating between the two speakers, so it fills more than
/// the 24-row terminal.
fn long_turns() -> String {
    let turns: Vec<_> = (1..=30)
        .map(|n| {
            json!({
                "speakerId": if n % 2 == 0 { 1 } else { 0 },
                "startMs": (n - 1) * 1000,
                "endMs": n * 1000,
                "text": format!("Line {n:02}."),
                "segments": [{
                    "id": n,
                    "startMs": (n - 1) * 1000,
                    "endMs": n * 1000,
                    "text": format!("Line {n:02}."),
                    "edited": false,
                }],
            })
        })
        .collect();
    json!({
        "hasMerges": false,
        "speakers": [
            {"speakerId": 0, "speaker": "Speaker 1"},
            {"speakerId": 1, "speaker": "Speaker 2"},
        ],
        "turns": turns,
    })
    .to_string()
}

/// The segment numbers the screen shows, in order (`Line 07.` is 7): how tall the viewport is.
fn visible_segments(screen: &str) -> Vec<u32> {
    screen
        .split("Line ")
        .skip(1)
        .filter_map(|rest| rest.get(..2)?.parse().ok())
        .collect()
}

/// The turns whose header (`00:04  Speaker 1`) the transcript panel draws: what a page step moves
/// by, a turn cut off at the viewport's bottom counting once its header shows.
fn turn_starts(screen: &str) -> u32 {
    screen
        .lines()
        .filter_map(|line| line.strip_prefix('│'))
        .filter(|row| {
            row.get(..5).is_some_and(|clock| {
                clock.as_bytes()[2] == b':' && clock.replace(':', "").parse::<u32>().is_ok()
            }) && row[5..].starts_with("  Speaker")
        })
        .count() as u32
}

#[test]
fn the_view_follows_the_cursor_and_the_vim_keys_page_and_jump() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        WIDE,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &long_turns());
    // The transcript's first frame: the first segment is marked, and the footer (drawn last) is
    // there too, so the segments the screen shows are the whole viewport, not a frame in progress.
    tui.wait_for_highlight(WAIT, "Line 01.");
    tui.wait_for_screen(WAIT, |screen| {
        let shown = screen_text(screen);
        shown.contains("? help") && visible_segments(&shown).first() == Some(&1)
    });
    let screen = tui.text();
    assert!(
        !screen.contains("Line 30."),
        "a 30-turn transcript does not fit 24 rows:\n{screen}"
    );
    assert!(
        screen.contains("PgUp"),
        "the footer states the paging keys:\n{screen}"
    );

    // PageDown pages by the viewport, whatever its height is (the footer's rows take theirs) -
    // the turns that start in it - and Ctrl-D and Ctrl-U do the same either way.
    let visible = visible_segments(&screen);
    let rows = turn_starts(&screen);
    assert_eq!(
        visible.first(),
        Some(&1),
        "the transcript opens at the first segment:\n{screen}"
    );
    let paged = 1 + rows;
    let twice = (paged + rows).min(30);
    // Ctrl-U is the inverse of Ctrl-D, so it lands one viewport back up.
    let back = (twice - rows).max(1);
    tui.press(b"\x1b[6~");
    tui.wait_for_highlight(WAIT, &format!("Line {paged:02}."));
    tui.press(b"\x04");
    tui.wait_for_highlight(WAIT, &format!("Line {twice:02}."));
    tui.press(b"\x15");
    tui.wait_for_highlight(WAIT, &format!("Line {back:02}."));

    // `G` and End reach the last segment, a single `g` and Home the first.
    tui.press(b"G");
    tui.wait_for(WAIT, |text| text.contains("Line 30."));
    tui.wait_for_highlight(WAIT, "Line 30.");
    tui.press(b"g");
    tui.wait_for(WAIT, |text| text.contains("Line 01."));
    tui.wait_for_highlight(WAIT, "Line 01.");
    tui.press(b"\x1b[F");
    tui.wait_for_highlight(WAIT, "Line 30.");
    tui.press(b"\x1b[H");
    tui.wait_for_highlight(WAIT, "Line 01.");
}
