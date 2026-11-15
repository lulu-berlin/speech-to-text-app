//! Roadmap item 67, second amendment: the tab keys. `h`/`l` and Left/Right move between the tabs
//! (on Settings too - `settings_numbers.rs` presses Right there); each tab underlines its first
//! letter and Shift+`Q`/`H`/`S` opens it; a transcript keeps `h`/`l` for its segment cursor.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use terminal::{EventService, Tui, no_tab_marked, tab_shortcut_marked};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

/// The text role in the dark theme, as the terminal reports it: a tab's live key letter.
fn hint() -> vt100::Color {
    token(&["colors", "text"], "dark")
}

#[test]
fn h_l_and_the_arrows_move_between_tabs_and_shift_letters_open_them() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));

    // The open tab's letter is plain; the two a shortcut reaches are marked.
    tui.wait_for_screen(WAIT, |screen| {
        !tab_shortcut_marked(screen, "Queue", hint())
            && ["History", "Settings"]
                .iter()
                .all(|title| tab_shortcut_marked(screen, title, hint()))
    });

    // Right and `l` move to the next tab, Left and `h` to the previous one.
    tui.press(b"\x1b[C");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    tui.press(b"l");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Restore default settings"));
    tui.press(b"h");
    tui.wait_for(WAIT, |text| text.contains("/ Search"));
    tui.press(b"\x1b[D");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));

    // Shift and a tab's letter open it from anywhere.
    tui.press(b"S");
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Restore default settings"));
    tui.press(b"Q");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
    tui.press(b"H");
    tui.wait_for(WAIT, |text| {
        text.contains("/ Search") && text.contains("interview.mp3")
    });

    // In a transcript `l` and `h` still move the segment cursor, and Shift+`Q` still leaves it.
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"l");
    tui.wait_for_highlight(WAIT, "Thanks for joining us today.");
    tui.press(b"h");
    tui.wait_for_highlight(WAIT, "Good morning.");
    tui.press(b"Q");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
}

/// The user's rule (2026-11-11): a tab marks its shortcut letter only while a shortcut can open
/// it - the open tab and a held keyboard (a dialog, the key reference) have none.
#[test]
fn a_tab_marks_its_shortcut_only_while_the_shortcut_acts() {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        24,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.wait_for_screen(WAIT, |screen| {
        !tab_shortcut_marked(screen, "Queue", hint())
            && ["History", "Settings"]
                .iter()
                .all(|title| tab_shortcut_marked(screen, title, hint()))
    });

    // The upload dialog holds every key but quit: no tab is marked, and Escape brings the marks
    // back.
    tui.press(b"u");
    tui.wait_for(WAIT, |text| text.contains("Upload audio"));
    tui.wait_for_screen(WAIT, |screen| no_tab_marked(screen, hint()));
    tui.press(b"\x1b");
    tui.wait_for_screen(WAIT, |screen| {
        tab_shortcut_marked(screen, "History", hint())
    });

    // The key reference does the same.
    tui.press(b"?");
    tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    tui.wait_for_screen(WAIT, |screen| no_tab_marked(screen, hint()));
    tui.press(b"?");
    tui.wait_for_screen(WAIT, |screen| {
        tab_shortcut_marked(screen, "History", hint())
    });
}
