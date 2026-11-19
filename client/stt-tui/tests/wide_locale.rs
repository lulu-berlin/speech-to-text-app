//! Roadmap item 67, the review (R7): in a locale with wide characters (zh-Hans) the screen's parts
//! fit the columns they are drawn in, and a click selects what is drawn under it. The header keeps
//! its theme label whole; the tabs take a click on any of their columns; the settings labels stand
//! whole beside one column of values; History's search caret sits after what was typed. In the
//! upload dialog the guidance stands whole, a file with a wide name keeps its size and date in the
//! columns an ASCII-named one has them, and a click on the speakers chip opens its choices.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::path::Path;
use std::time::Duration;

use serde_json::json;
use settings_fixture::{FRONTENDS, SETTINGS, stored};
use terminal::{EventService, EventStream, Scene, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const DEFAULTS_EVENT: &str = include_str!("../../../docs/api/settings-updated.defaults.json");
const ZH: &str = include_str!("../../../frontend/src/locales/zh-Hans.json");
const TURNS: &str = include_str!("../../../docs/api/turns.json");
const WAIT: Duration = Duration::from_secs(20);

/// The zh-Hans catalog's message for `key`.
fn zh(key: &str) -> String {
    let catalog: serde_json::Value = serde_json::from_str(ZH).unwrap();
    catalog[key].as_str().unwrap().to_string()
}

/// The column where the value beside settings label `label` starts: in the list's row that begins
/// with the label, the first cell after the label that is not blank.
fn value_column(scene: &Scene, label: &str) -> usize {
    let opening = format!("│{label}");
    let row = scene.row_where(|text| text.starts_with(&opening));
    let first = label.chars().next().unwrap().to_string();
    let last = label.chars().last().unwrap().to_string();
    let mut column = 0;
    while scene
        .cell(row, column)
        .is_some_and(|cell| cell.text != first)
    {
        column += 1;
    }
    while scene
        .cell(row, column)
        .is_some_and(|cell| cell.text != last)
    {
        column += 1;
    }
    // Past the label's last character - two cells, being wide - then past the blanks.
    column += 2;
    while scene
        .cell(row, column)
        .is_some_and(|cell| cell.text.trim().is_empty())
    {
        column += 1;
    }
    column
}

/// The TUI after the snapshot, working in `folder` when given, switched to zh-Hans by a settings
/// event and showing the queue in it; the state directory and the event stream are kept for as
/// long as the test holds the third value.
fn start_zh_hans(folder: Option<&Path>) -> (EventService, Tui, (tempfile::TempDir, EventStream)) {
    let service = EventService::start();
    let state = tempfile::tempdir().unwrap();
    support::write_bootstrap(state.path(), service.port);
    let program: &Path = env!("CARGO_BIN_EXE_stt-tui").as_ref();
    let tui = match folder {
        Some(folder) => Tui::start_in(program, state.path(), 120, 30, folder),
        None => Tui::start(program, state.path(), 120, 30),
    };
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    stream.send(
        "settings-updated",
        &stored(
            DEFAULTS_EVENT,
            json!({ "appLanguage": "zh-Hans", "themeMode": "dark" }),
        ),
    );
    tui.wait_for(WAIT, |text| text.contains(&zh("queue.title")));
    (service, tui, (state, stream))
}

#[test]
fn zh_hans_parts_fit_their_columns_and_clicks_land_on_what_is_drawn() {
    let (service, mut tui, _connection) = start_zh_hans(None);

    // The header keeps its theme label whole at the row's end, beside the connection.
    let theme = format!("◐ {}", zh("app.theme.dark"));
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.lines()
            .next()
            .is_some_and(|header| header.contains(&theme))
            && text.contains(&zh("queue.title"))
    });

    // A click on the right half of the Settings tab's last character opens the settings.
    let settings = zh("settings.title");
    let last = settings.chars().last().unwrap().to_string();
    let (column, row) = scene.cell_of(&last);
    service.capture_next_settings();
    tui.click(column + 1, row);
    service.next_get_from(&tui, WAIT).answer(
        "200 OK",
        &stored(SETTINGS, json!({ "appLanguage": "zh-Hans" })),
    );
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);

    // The settings labels stand whole, their values in one column.
    let wide = zh("settings.defaultFrontend");
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(&wide) && text.contains(&zh("settings.terminalCommand"))
    });
    let column = value_column(&scene, &zh("settings.device"));
    for label in [
        zh("settings.defaultFrontend"),
        zh("settings.terminalCommand"),
    ] {
        assert_eq!(
            value_column(&scene, &label),
            column,
            "{label}'s value starts where the device's does:\n{}",
            scene.text()
        );
    }

    // A click on the right half of the History tab's last character opens History.
    let history = zh("history.title");
    let last = history.chars().last().unwrap().to_string();
    let (column, row) = scene.cell_of(&last);
    tui.click(column + 1, row);
    let search = format!("/ {}", zh("history.search"));
    let failed = zh("status.failed");
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(&search) && text.contains(&failed) && text.contains("call.ogg")
    });

    // Every status chip is as wide as the widest status word's, as drawn: zh-Hans writes each of
    // the six in three CJK characters, six columns, so the failed task's two-character chip still
    // spans a space, six columns and a space.
    let first = failed.chars().next().unwrap().to_string();
    let (column, row) = scene.cell_of(&first);
    let chip = scene.cell(row.into(), column.into()).unwrap().bg;
    let start = usize::from(column) - 1;
    // The right half of a wide character carries no colour of its own here: it belongs to the
    // character before it.
    let width = (start..)
        .take_while(|at| {
            scene
                .cell(row.into(), *at)
                .is_some_and(|cell| cell.bg == chip || cell.text.is_empty())
        })
        .count();
    assert_eq!(width, 8, "the failed chip's width:\n{}", scene.text());

    // The search's caret sits right after what was typed.
    tui.press(b"/");
    tui.press(b"ab");
    tui.wait_for_screen(WAIT, |screen| {
        let scene = Scene::of(screen);
        let typed = scene.text().contains("ab");
        typed && {
            let (column, row) = scene.cell_of("b");
            screen.cursor_position() == (row, column + 1)
        }
    });
}

/// The column of the last cell of row `row` that holds `text`, a one-cell character.
fn last_cell(scene: &Scene, row: usize, text: &str) -> usize {
    let mut found = None;
    let mut column = 0;
    while let Some(cell) = scene.cell(row, column) {
        if cell.text == text {
            found = Some(column);
        }
        column += 1;
    }
    found.unwrap_or_else(|| panic!("{text:?} in row {row}:\n{}", scene.text()))
}

#[test]
fn the_zh_hans_upload_dialog_fits_its_columns_and_its_chips_take_clicks() {
    let folder = tempfile::tempdir().unwrap();
    // Two audio files of one size, written together: one name of wide characters, one of ASCII.
    let wide = "会议录音第一部分.mp3";
    std::fs::write(folder.path().join(wide), b"RIFF").unwrap();
    std::fs::write(folder.path().join("a.mp3"), b"RIFF").unwrap();
    let (_service, mut tui, _connection) = start_zh_hans(Some(folder.path()));

    // The dialog's guidance stands whole, as drawn - its keys without their brackets.
    tui.press(b"u");
    let guidance = zh("tui.browse.markHint").replace(['[', ']'], "");
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains(wide) && text.contains("a.mp3") && text.contains(&guidance)
    });

    // The wide-named file's size and date sit where the ASCII-named file's do, and read the same.
    let wide_row = scene.row_where(|text| text.contains(wide));
    let ascii_row = scene.row_where(|text| text.contains("a.mp3"));
    let unit = |row: usize| last_cell(&scene, row, "B");
    assert_eq!(
        unit(wide_row),
        unit(ascii_row),
        "both sizes end in one column:\n{}",
        scene.text()
    );
    // The size and the date, up to the dialog's right border; what lies behind the dialog differs.
    let detail = |row: usize| {
        let text = scene.row_text(row);
        let size = text.rfind(" B").unwrap();
        let end = size + text[size..].find('│').unwrap();
        text[size..end].to_string()
    };
    assert_eq!(
        detail(wide_row),
        detail(ascii_row),
        "the wide-named file shows its date whole:\n{}",
        scene.text()
    );

    // A click on the speakers chip's value - right of its wide label - opens its choices.
    let speakers = zh("queue.speakers");
    let chips = scene.row_where(|text| text.contains(&speakers) && text.matches('▾').count() == 2);
    let arrow = last_cell(&scene, chips, "▾");
    tui.click(arrow as u16 - 2, chips as u16);
    tui.wait_for(WAIT, |text| text.contains(&format!("┌{speakers}┐")));
}

/// The second review (S5): under a zh-Hans path label, a typed path wider than its row keeps its
/// end in view and the caret right after it - the label is measured in columns, not characters.
#[test]
fn a_long_path_under_the_zh_hans_label_keeps_its_end_and_caret() {
    let folder = tempfile::tempdir().unwrap();
    let (_service, mut tui, _connection) = start_zh_hans(Some(folder.path()));
    tui.press(b"u");
    let guidance = zh("tui.browse.markHint").replace(['[', ']'], "");
    tui.wait_for(WAIT, |text| text.contains(&guidance));

    // A path far wider than the dialog, ending in `/END`: its end stands whole before the dialog's
    // border, and the caret sits right after it.
    let path = format!("/{}/END", "x".repeat(150));
    tui.press(path.as_bytes());
    tui.wait_for_screen(WAIT, |screen| {
        let scene = Scene::of(screen);
        scene.text().contains("x/END") && {
            let row = scene.row_where(|text| text.contains("x/END"));
            let column = last_cell(&scene, row, "D");
            screen.cursor_position() == (row as u16, column as u16 + 1)
        }
    });
}

/// The second review (S5): the zh-Hans rename prompt's caret sits right after the name - its label
/// `重命名：` is measured in columns, not characters.
#[test]
fn the_zh_hans_rename_prompts_caret_follows_the_name() {
    let (service, mut tui, _connection) = start_zh_hans(None);
    tui.press(b"H");
    tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    tui.press(b"\r");
    service.next_get_from(&tui, WAIT).answer("200 OK", TURNS);
    tui.wait_for(WAIT, |text| text.contains("Good morning."));

    let prompt = zh("tui.speakers.rename").replace("{name}", "Speaker 1");
    tui.press(b"r");
    tui.wait_for_screen(WAIT, |screen| {
        let scene = Scene::of(screen);
        scene.text().contains(&prompt) && {
            let row = scene.row_where(|text| text.contains(&prompt));
            let column = last_cell(&scene, row, "1");
            screen.cursor_position() == (row as u16, column as u16 + 1)
        }
    });
}

/// The second review (S5): a zh-Hans query wider than History's search field is cut to the field
/// by whole characters - the status filter after it stays where it was - and the caret holds at
/// the field's last column.
#[test]
fn a_long_zh_hans_query_is_cut_to_the_search_field() {
    let (_service, mut tui, _connection) = start_zh_hans(None);
    tui.press(b"H");
    let search = format!("/ {}", zh("history.search"));
    let before = tui.wait_for_scene(WAIT, |text| text.contains(&search));
    let row = before.row_where(|text| text.contains(&search));
    // The status filter's key: the field's 28 columns and five blanks before it.
    let filter = last_cell(&before, row, "f");

    // Twenty wide characters - forty columns - into a field of 28.
    tui.press(b"/");
    tui.press("会议".repeat(10).as_bytes());
    tui.wait_for_screen(WAIT, |screen| {
        let scene = Scene::of(screen);
        scene.row_text(row).contains("会议会议")
            && last_cell(&scene, row, "f") == filter
            && screen.cursor_position() == (row as u16, filter as u16 - 6)
    });
}
