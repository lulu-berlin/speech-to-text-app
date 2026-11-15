//! Roadmap item 67, second amendment: a letter key that occurs in its action is shown by
//! highlighting that letter in place (**p**ause, **r**etry, **u**pload); any other key is shown,
//! highlighted, before its action (`Shift-↑↓ or J/K reorder`). Without colours the key is bracketed
//! instead (`[p]ause`), since `NO_COLOR` writes no attribute at all.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::json;
use settings_fixture::stored;
use terminal::{EventService, Tui};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = include_str!("../../../docs/api/settings.json");
const WAIT: Duration = Duration::from_secs(20);

/// The accent role in the dark theme, as the terminal reports it.
fn accent() -> vt100::Color {
    token(&["colors", "accent"], "dark")
}

/// The footer: the screen's last row.
fn footer(screen: &vt100::Screen) -> String {
    let (rows, cols) = (screen.size().0.get(), screen.size().1.get());
    screen.contents_between(rows - 1, 0, rows - 1, cols)
}

/// Whether the footer shows the hint `hint` (between three spaces) with exactly the characters
/// `keys` (indices into it) highlighted: underlined in the accent colour.
fn highlighted(screen: &vt100::Screen, hint: &str, keys: std::ops::Range<usize>) -> bool {
    let row = footer(screen);
    let Some(at) = row
        .split("   ")
        .scan(0, |offset, part| {
            let start = *offset;
            *offset += part.chars().count() + "   ".chars().count();
            Some((start, part))
        })
        .find(|(_, part)| part.trim() == hint)
        .map(|(start, part)| start + (part.chars().count() - part.trim_start().chars().count()))
    else {
        return false;
    };
    let last = screen.size().0.get() - 1;
    hint.chars().enumerate().all(|(index, _)| {
        let cell = screen.cell(last, (at + index) as u16).unwrap();
        let key = cell.underline() && cell.fgcolor() == accent();
        key == keys.contains(&index)
    })
}

#[test]
fn a_letter_key_is_highlighted_inside_its_action_and_any_other_key_before_it() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    service.capture_startup_settings();
    let tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        200,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &stored(SETTINGS, json!({ "themeMode": "dark" })));

    // The queue's letter keys inside their words, the first letter or one further in.
    tui.wait_for_screen(WAIT, |screen| {
        [
            ("pause", 0..1),
            ("cancel", 0..1),
            ("upload", 0..1),
            ("theme", 0..1),
            ("quit", 0..1),
        ]
        .into_iter()
        .all(|(hint, keys)| highlighted(screen, hint, keys))
    });
    // A key that is not a letter of its action stands before it, highlighted whole.
    tui.wait_for_screen(WAIT, |screen| {
        highlighted(screen, "Shift-↑↓ or J/K reorder", 0..15) && highlighted(screen, "? help", 0..1)
    });
    let text = tui.text();
    let footer_text = text.lines().last().unwrap_or_default();
    for written in ["p pause", "c cancel", "q quit", "["] {
        assert!(
            !footer_text.contains(written),
            "the footer still writes {written:?} separately:\n{footer_text}"
        );
    }
}

#[test]
fn without_colours_the_key_is_bracketed_in_place() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        200,
        24,
        &[("NO_COLOR", "1")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let text = tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    let footer_text = text.lines().last().unwrap_or_default().to_string();
    for hint in ["[p]ause", "[c]ancel", "[q]uit", "[Shift-↑↓ or J/K] reorder"] {
        assert!(
            footer_text.contains(hint),
            "the footer lacks {hint:?}:\n{footer_text}"
        );
    }
}
