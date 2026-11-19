//! Roadmap item 67, the design pass (the user's report, 2026-11-15, after btop's help): the `?`
//! reference is a table - a `Key:` column with each key in the accent and a `Description:` column
//! with its words muted - in the dialogs' frame; a list taller than the frame scrolls inside it with
//! the arrows, and says so on its cut edge.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{
    EventService, OpenTranscript, Scene, Tui, open_transcript, settings_in, table_cells, table_row,
    token,
};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// The table row whose key cell is `key` and whose description is `words`: its screen row and the
/// columns its key and its description start at. The cells sit between two `│` borders - the
/// reference's, wherever the page's own panels are around it.
fn row_columns(scene: &Scene, key: &str, words: &str) -> Option<(usize, usize, usize)> {
    (0..scene.cells.len()).find_map(|row| {
        let line = scene.row_text(row);
        let mut start = 0;
        for inner in line.split('│') {
            let at = start;
            start += inner.len() + '│'.len_utf8();
            if table_cells(inner) != [key, words] {
                continue;
            }
            let column = |needle: &str| {
                let byte = at + inner.find(needle).unwrap();
                line[..byte].chars().count()
            };
            return Some((row, column(key), column(words)));
        }
        None
    })
}

#[test]
fn the_reference_is_a_key_and_description_table() {
    let service = EventService::start();
    service.capture_startup_settings();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    let accent = token(&["colors", "accent"], "dark");
    let muted = token(&["colors", "textMuted"], "dark");
    tui.wait_for_cell(WAIT, "memo.wav", |cell| {
        cell.fgcolor() == token(&["colors", "text"], "dark")
    });

    tui.press(b"?");
    let scene = tui.wait_for_scene(WAIT, |screen| {
        screen.contains("Queue keys") && table_row(screen, "p", "pause")
    });
    let screen = scene.text();
    assert!(
        screen
            .lines()
            .any(|line| line.contains("Key:") && line.contains("Description:")),
        "the table's header:\n{screen}"
    );
    let (row, key, words) = row_columns(&scene, "p", "pause").unwrap();
    assert_eq!(scene.cell(row, key).unwrap().fg, accent, "the key");
    assert_eq!(scene.cell(row, words).unwrap().fg, muted, "the description");
    let (_, reorder_key, reorder_words) = row_columns(&scene, "Shift-↑↓ or J/K", "reorder")
        .unwrap_or_else(|| panic!("reorder's row:\n{screen}"));
    // The keys centred in their column, as btop's are: the widest key (15 columns) fills it, `p`
    // sits ⌊(15 - 1) / 2⌋ = 7 columns in; the descriptions share one left edge.
    assert_eq!(
        key,
        reorder_key + 7,
        "a key centred in its column:\n{screen}"
    );
    assert_eq!(words, reorder_words, "one description column:\n{screen}");
    assert!(table_row(&screen, "Space", "queue pause"), "{screen}");
}

#[test]
fn a_reference_taller_than_the_frame_scrolls_with_the_arrows() {
    let OpenTranscript { mut tui, .. } = open_transcript();
    // A short terminal: the transcript's keys outnumber the rows the reference can take.
    tui.resize(120, 16);
    tui.wait_for(WAIT, |screen| screen.lines().count() <= 16);
    tui.press(b"?");
    let screen = tui.wait_for(WAIT, |screen| {
        screen.contains("Transcript keys") && table_row(screen, "arrows/hjkl", "move")
    });
    assert!(screen.contains("↓ more"), "the cut edge says so:\n{screen}");
    assert!(
        !table_row(&screen, "?", "help"),
        "the last key is below the edge:\n{screen}"
    );

    // Down scrolls the table: its last row comes into view, the first leaves it.
    for _ in 0..30 {
        tui.press(b"\x1b[B");
    }
    let screen = tui.wait_for(WAIT, |screen| table_row(screen, "?", "help"));
    assert!(screen.contains("↑ more"), "{screen}");
    assert!(!screen.contains("↓ more"), "{screen}");
    // Up scrolls back.
    for _ in 0..30 {
        tui.press(b"\x1b[A");
    }
    tui.wait_for(WAIT, |screen| {
        screen.contains("↓ more") && !screen.contains("↑ more")
    });
}

/// The reference's table rows as they show from its top and, after End, from its bottom: every row
/// of a table taller than the frame.
fn reference(tui: &mut Tui, title: &str) -> String {
    tui.press(b"?");
    let top = tui.wait_for(WAIT, |screen| screen.contains(title));
    tui.press(b"\x1b[F");
    let bottom = tui.wait_for(WAIT, |screen| table_row(screen, "?", "help"));
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |screen| !screen.contains(title));
    format!("{top}\n{bottom}")
}

#[test]
fn the_reference_lists_only_the_keys_that_act_now() {
    // The service and its stream stay up: a write's key (`n`) acts only while the stream is live.
    let OpenTranscript {
        mut tui,
        service: _service,
        stream: _stream,
        state: _state,
    } = open_transcript();
    // Nothing marked: Enter plays from the cursor; moving marked segments does not apply.
    let rows = reference(&mut tui, "Transcript keys");
    assert!(table_row(&rows, "Enter", "play from here"), "{rows}");
    assert!(!table_row(&rows, "Enter", "move to…"), "{rows}");
    assert!(!table_row(&rows, "n", "new speaker"), "{rows}");

    // A segment marked: Enter moves the marked segments; playing from here does not apply.
    tui.press(b"a");
    tui.wait_for(WAIT, |screen| screen.contains("segment selected"));
    let rows = reference(&mut tui, "Transcript keys");
    assert!(table_row(&rows, "Enter", "move to…"), "{rows}");
    assert!(table_row(&rows, "n", "new speaker"), "{rows}");
    assert!(!table_row(&rows, "Enter", "play from here"), "{rows}");
}
