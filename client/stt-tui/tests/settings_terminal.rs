//! Roadmap item 67, second amendment: the terminal command is edited inside its row and starts
//! with the command the TUI would open in today. With none stored the row names the OS default:
//! on Linux the words placed before the TUI's command (`$TERMINAL -e` here), which the edit starts
//! with; on Windows and macOS, whose launchers no prefix reproduces, PowerShell or Terminal, and
//! the edit starts empty. Enter saves a changed command (an empty one as none, the OS default),
//! an unchanged one sends nothing, and Escape leaves the stored one.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{FRONTENDS, SETTINGS, body, stored, value};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

/// What opens the TUI on this OS when no terminal command is stored, with `$TERMINAL=myterm`, and
/// what the edit starts with.
fn os_default() -> (&'static str, &'static str) {
    if cfg!(target_os = "windows") {
        ("PowerShell", "")
    } else if cfg!(target_os = "macos") {
        ("Terminal", "")
    } else {
        ("myterm -e", "myterm -e")
    }
}

#[test]
fn the_terminal_command_is_edited_in_its_row_starting_with_what_opens_today() {
    let (default, prefill) = os_default();
    let none = stored(SETTINGS, json!({ "terminalCommand": null }));
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        30,
        &[("TERMINAL", "myterm")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service.next_get_from(&tui, WAIT).answer("200 OK", &none);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", FRONTENDS);

    // None stored: the row names what opens the TUI.
    let shown = format!("default: {default}");
    tui.wait_for(WAIT, |text| value(text, "Terminal command") == shown);

    // Enter edits it in the row, starting with the OS default, its keys beside it - no prompt at
    // the bottom. The row is the last field's: five Downs from the first (a sixth reaches the
    // restore row under it).
    for _ in 0..5 {
        tui.press(b"\x1b[B");
    }
    tui.press(b"\r");
    let hint = format!("Enter save · Esc cancel · empty: {default}");
    let editing = tui.wait_for(WAIT, |text| {
        value(text, "Terminal command").starts_with(&format!("{prefill}▏"))
    });
    assert!(
        value(&editing, "Terminal command").contains(&hint),
        "{editing}"
    );
    assert!(
        !editing.contains("Terminal: "),
        "no bottom prompt:\n{editing}"
    );

    // Enter on the unchanged default sends nothing: the OS default stays in charge.
    tui.press(b"\r");
    let screen = tui.wait_for(WAIT, |text| value(text, "Terminal command") == shown);
    assert!(
        !service.command_arrived(),
        "an unchanged command is not sent:\n{screen}"
    );

    // A typed command is saved.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    tui.press(&[0x7f; 20]);
    tui.press(b"foot -e\r");
    let write = service.next_command(WAIT);
    let after = stored(&none, json!({ "terminalCommand": "foot -e" }));
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(
            &none,
            json!({ "terminalCommand": "foot -e", "maxSpeakers": 3 })
        )
    );
    write.answer("200 OK", &after);
    tui.wait_for(WAIT, |text| value(text, "Terminal command") == "foot -e");

    // A stored command starts the edit; Escape leaves it.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| {
        value(text, "Terminal command").starts_with("foot -e▏")
    });
    tui.press(b"x");
    tui.wait_for(WAIT, |text| {
        value(text, "Terminal command").starts_with("foot -ex▏")
    });
    tui.press(b"\x1b");
    let screen = tui.wait_for(WAIT, |text| {
        value(text, "Terminal command") == "foot -e" && text.contains("Restore default settings")
    });
    assert!(
        !service.command_arrived(),
        "Escape sends nothing:\n{screen}"
    );

    // Emptied, it is saved as none: the OS default again.
    tui.press(b"\r");
    tui.wait_for(WAIT, |text| text.contains("Esc cancel"));
    tui.press(&[0x7f; 20]);
    tui.press(b"\r");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(&none, json!({ "maxSpeakers": 3 }))
    );
    write.answer("200 OK", &none);
    tui.wait_for(WAIT, |text| value(text, "Terminal command") == shown);
}
