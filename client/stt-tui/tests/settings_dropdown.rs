//! Roadmap item 67, second amendment: the settings with a set of values open a dropdown. Enter on
//! the device, language, speakers or default frontend lists every option with the current one
//! marked; the list floats over the rows below without moving them; Up/Down and Enter choose (and
//! save, as any change does), and Escape closes the dropdown alone, keeping the value.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, GPU0, GPU1, SETTINGS, body, stored};
use terminal::{EventService, Tui};
use tokens::token;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const LANGUAGES: &str = include_str!("../../../docs/api/languages.json");
const WAIT: Duration = Duration::from_secs(20);
const LABELS: [&str; 6] = [
    "Device",
    "Threads",
    "Language",
    "Speakers",
    "Default user interface",
    "Terminal command",
];

/// The screen line each settings label starts on (its first line holding the label).
fn label_lines(screen: &str) -> Vec<Option<usize>> {
    LABELS
        .iter()
        .map(|label| {
            screen
                .lines()
                .position(|line| line.trim_start_matches(['│', ' ']).starts_with(label))
        })
        .collect()
}

#[test]
fn a_dropdown_lists_the_options_and_chooses_one_and_escape_closes_it_alone() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let before = tui.wait_for(WAIT, |text| text.contains("kitty -e"));
    let lines = label_lines(&before);
    assert!(lines.iter().all(Option::is_some), "{before}");

    // Enter on the language: every language, the stored one marked and under the cursor, floating
    // over the rows below - each label stays on its line.
    tui.press(b"\x1b[B");
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let open = tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    assert!(open.contains("en  english"), "{open}");
    // The list's rows carry the page's own foreground, not the terminal's default.
    let scene = tui.wait_for_scene(WAIT, |text| {
        text.contains("✓ de  german") && text.contains("en  english")
    });
    assert_eq!(
        scene.fg("en  english"),
        token(&["colors", "text"], "dark"),
        "the dropdown's rows use the text role"
    );
    assert!(open.contains("zh  chinese"), "{open}");
    assert!(
        open.contains("Enter choose · type filter · Esc close"),
        "{open}"
    );
    assert_eq!(label_lines(&open), lines, "the rows do not move:\n{open}");
    tui.wait_for_screen(WAIT, |screen| {
        reversed(screen).split_whitespace().next() == Some("de")
    });

    // Escape closes the dropdown alone: nothing is sent, Settings stays, the value is kept.
    tui.press(b"\x1b");
    let closed = tui.wait_for(WAIT, |text| {
        !text.contains("Esc close") && text.contains("kitty -e")
    });
    assert!(!closed.contains("✓"), "{closed}");
    assert!(!service.command_arrived(), "Escape sends nothing");

    // Enter, Down, Enter: the next language is chosen and saved whole.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert!(write.request().line.starts_with("PUT /api/settings "));
    let change = json!({ "defaultLanguage": "es", "maxSpeakers": 3 });
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(SETTINGS, change.clone())
    );
    write.answer("200 OK", &stored(SETTINGS, change));
    tui.wait_for(WAIT, |text| {
        !text.contains("Esc close")
            && text
                .lines()
                .any(|line| line.contains("Language") && line.contains("es"))
    });

    // A long list scrolls with its cursor: twelve places down from es is shown, then chosen.
    let codes: Vec<String> = serde_json::from_str::<Value>(LANGUAGES).unwrap()["languages"]
        .as_array()
        .unwrap()
        .iter()
        .map(|language| language["code"].as_str().unwrap().to_string())
        .collect();
    let far = &codes[codes.iter().position(|code| code == "es").unwrap() + 12];
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("✓ es"));
    for _ in 0..12 {
        tui.press(b"\x1b[B");
    }
    tui.wait_for_screen(WAIT, |screen| {
        reversed(screen).split_whitespace().next() == Some(far.as_str())
    });
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["defaultLanguage"],
        json!(far)
    );
    write.answer(
        "200 OK",
        &stored(SETTINGS, json!({ "defaultLanguage": far })),
    );
    tui.wait_for(WAIT, |text| !text.contains("Esc close"));

    // The device lists the devices with their GPU names; the Up arrow moves up and Enter chooses.
    tui.press(b"\x1b[A");
    tui.press(b"\x1b[A");
    tui.press(b"\r");
    let devices = tui.wait_for(WAIT, |text| text.contains("✓ Auto"));
    assert!(devices.contains("CPU"), "{devices}");
    assert!(devices.contains(GPU0), "{devices}");
    assert!(devices.contains(GPU1), "{devices}");
    tui.press(b"\x1b[A");
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    let change = json!({ "device": "cpu", "defaultLanguage": far, "maxSpeakers": 3 });
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(SETTINGS, change.clone())
    );
    write.answer("200 OK", &stored(SETTINGS, change));
    tui.wait_for(WAIT, |text| !text.contains("Esc close"));

    // The speakers and the default frontend list theirs; Escape leaves them as they were.
    for _ in 0..3 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    let modes = tui.wait_for(WAIT, |text| text.contains("✓ 3"));
    assert!(modes.contains("auto") && modes.contains("off"), "{modes}");
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Esc close"));
    // The Escape's frame is awaited, so the Down below cannot reach the TUI with it as Alt-Down.
    tui.press(b"\x1b[B");
    tui.press(b"\r");
    let frontends = tui.wait_for(WAIT, |text| text.contains("✓ browser"));
    assert!(frontends.contains("window"), "{frontends}");
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| !text.contains("Esc close"));
    assert!(!service.command_arrived(), "Escape sends nothing");

    // Escape with no dropdown open still leaves Settings: the dropdown's Escape was its own.
    tui.press(b"\x1b");
    tui.wait_for(WAIT, |text| text.contains("Choose audio files"));
}

/// The text of the screen's highlighted cells, row by row - reversed without colours, on the accent
/// tint with them: the cursor's option inside an open dropdown sits below the selected settings row,
/// so the last highlighted row is the dropdown's.
fn reversed(screen: &vt100::Screen) -> String {
    let (rows, columns) = screen.size();
    let page = terminal::page_background(screen);
    let mut last = String::new();
    for row in 0..rows.get() {
        let text: String = (0..columns.get())
            .filter_map(|column| screen.cell(row, column))
            .filter(|cell| terminal::cell_marked(cell, page))
            .map(|cell| cell.contents())
            .collect();
        if !text.trim().is_empty() {
            last = text.trim().trim_start_matches("✓").trim().to_string();
        }
    }
    last
}

#[test]
fn a_double_click_on_a_row_opens_its_dropdown() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);
    let screen = tui.wait_for(WAIT, |text| text.contains("kitty -e"));

    // Two clicks on the language row open its list, as Enter does.
    let (column, row) = terminal::position_of(&screen, "Language");
    tui.click(column, row);
    tui.click(column, row);
    tui.wait_for(WAIT, |text| text.contains("✓ de  german"));
}
