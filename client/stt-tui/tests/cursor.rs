//! Roadmap item 67 (the user's report 2026-11-08: "editing a segment should have a visible cursor";
//! "the upload dialog should have a visible cursor when typing a file name"): every text prompt
//! shows the terminal's cursor where typing goes, and it goes again with the prompt.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS};
use terminal::{EventService, Tui, open_transcript, screen_text};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// Whether the cursor is shown as a blinking block at the end of the line holding `prefix` - where
/// the draft continues, its trailing blanks aside.
fn cursor_after(screen: &vt100::Screen, prefix: &str) -> bool {
    if screen.hide_cursor() || screen.cursor_style() != vt100::CursorStyle::BlinkBlock {
        return false;
    }
    let (row, column) = screen.cursor_position();
    let contents = screen_text(screen);
    let Some((index, line)) = contents
        .lines()
        .enumerate()
        .find(|(_, line)| line.contains(prefix))
    else {
        return false;
    };
    let start = line[..line.find(prefix).unwrap()].chars().count();
    // The draft runs from the prefix to the panel's right border, or the line's end on a prompt
    // that fills the width: the dialog's row goes on beyond its border with what lies behind it.
    let draft = line[line.find(prefix).unwrap()..]
        .chars()
        .skip(prefix.chars().count())
        .take_while(|character| *character != '│')
        .collect::<String>();
    let draft = draft.trim_end().chars().count();
    index == row as usize && column as usize == start + prefix.chars().count() + draft
}

/// Whether the cursor is shown as a blinking block on the cell holding `mark`, in the line that
/// holds `needle`.
fn cursor_on(screen: &vt100::Screen, needle: &str, mark: char) -> bool {
    if screen.hide_cursor() || screen.cursor_style() != vt100::CursorStyle::BlinkBlock {
        return false;
    }
    let (row, column) = screen.cursor_position();
    let contents = screen_text(screen);
    let Some((index, line)) = contents
        .lines()
        .enumerate()
        .find(|(_, line)| line.contains(needle) && line.contains(mark))
    else {
        return false;
    };
    let at = line
        .chars()
        .take_while(|character| *character != mark)
        .count();
    index == row as usize && column as usize == at
}

#[test]
fn a_text_prompt_shows_the_cursor_where_typing_goes() {
    // The segment's edit prompt starts from the segment's text; typing adds to it.
    let mut open = open_transcript();
    open.tui.press(b"e");
    open.tui.press(b"Hi");
    open.tui.wait_for(WAIT, |text| {
        text.contains("Segment text: ") && text.contains("Hi")
    });
    open.tui
        .wait_for_screen(WAIT, |screen| cursor_after(screen, "Segment text: "));

    // The upload dialog's path line, with what has been typed so far.
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
    tui.press(b"u");
    tui.press(b"talk.mp3");
    tui.wait_for(WAIT, |text| text.contains("Or type a path   talk.mp3"));
    tui.wait_for_screen(WAIT, |screen| cursor_after(screen, "Or type a path   "));

    // The download dialog's target: no prefilled path - the dialog opens on the folder's list - so
    // the cursor follows a name as it is typed. Escape left the edit prompt above (waited for, so
    // the next key is not read as Alt+d with it).
    open.tui.press(b"\x1b");
    open.tui
        .wait_for(WAIT, |text| !text.contains("Segment text: "));
    open.tui.press(b"d");
    open.tui.wait_for(WAIT, |text| text.contains("Download"));
    open.tui.press(b"memo.txt");
    open.tui
        .wait_for(WAIT, |text| text.contains("Path: memo.txt"));
    open.tui
        .wait_for_screen(WAIT, |screen| cursor_after(screen, "Path: "));

    // A settings number: the cursor on the bar the draft ends at, inside its row.
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let mut tui = Tui::start(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        state.path(),
        120,
        30,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| text.contains("Threads"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("▏"));
    tui.wait_for_screen(WAIT, |screen| cursor_on(screen, "Threads", '▏'));
}
