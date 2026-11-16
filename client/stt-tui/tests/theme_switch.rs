//! Roadmap item 67, the second amendment's theme: `t` cycles the stored theme mode
//! (`auto → light → dark → auto`) and saves it with the rest of the settings; a mode another
//! frontend stores repaints the open TUI live; and `auto` follows the OS's preference, then the
//! terminal's background (its answer to OSC 11), then dark.

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;
mod tokens;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::body;
use terminal::{EventService, EventStream, Reported, Tui, settings_in};
use tokens::token;
use vt100::Color;

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const DEFAULTS_EVENT: &str = include_str!("../../../docs/api/settings-updated.defaults.json");
const WAIT: Duration = Duration::from_secs(20);

/// The border role's colour in `mode`, as the terminal reports it: the queue's upload line draws its
/// `—` separator in it.
fn border(mode: &str) -> Color {
    token(&["colors", "border"], mode)
}

#[test]
fn t_cycles_the_stored_mode_and_another_frontends_change_repaints_live() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("dark"));
    tui.wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border("dark"));

    // `t` from dark: the next mode, auto, saved with every other setting as stored.
    tui.press(b"t");
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(&settings_in("dark"), json!({ "themeMode": "auto" }))
    );
    write.answer("200 OK", &settings_in("auto"));

    // `t` from auto: light, drawn once the service stores it.
    tui.press(b"t");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["themeMode"],
        json!("light")
    );
    write.answer("200 OK", &settings_in("light"));
    tui.wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border("light"));

    // Another frontend stores dark: the open TUI repaints without a key.
    let mut event: Value = serde_json::from_str(DEFAULTS_EVENT).unwrap();
    event["themeMode"] = json!("dark");
    stream.send("settings-updated", &event.to_string());
    tui.wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border("dark"));

    // And `t` goes on from the mode the browser stored.
    tui.press(b"t");
    let write = service.next_command(WAIT);
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap()["themeMode"],
        json!("auto")
    );
}

/// A started TUI with what must outlive it: the service it reads, and the directories holding its
/// bootstrap and its empty `PATH`.
struct Started {
    tui: Tui,
    _service: EventService,
    /// The open stream: dropped, the TUI would show itself reconnecting.
    _stream: EventStream,
    _dirs: [tempfile::TempDir; 2],
}

/// The TUI started with the stored mode `auto`, where the OS says nothing (no `gsettings` or
/// `defaults` on an empty `PATH`), in a terminal reporting `colours`. Unix only: on Windows the
/// TUI's `reg` is found in the system folder whatever `PATH` holds, so the OS always answers there
/// and the terminal is never asked (run 43871242612: the runner's light appearance won).
fn auto_in_terminal(colours: Option<Reported>) -> Started {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let empty = tempfile::tempdir().unwrap();
    service.capture_startup_settings();
    let tui = Tui::start_with_colours(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        120,
        24,
        &[
            ("PATH", empty.path().to_str().unwrap()),
            ("TERM", "xterm-256color"),
        ],
        colours,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &settings_in("auto"));
    Started {
        tui,
        _service: service,
        _stream: stream,
        _dirs: [dir, empty],
    }
}

#[test]
#[cfg_attr(
    windows,
    ignore = "Windows always answers through reg; see auto_in_terminal"
)]
fn auto_reads_a_light_terminal_background_when_the_os_says_nothing() {
    let started = auto_in_terminal(Some(Reported {
        foreground: "rgb:0000/0000/0000",
        background: "rgb:ffff/ffff/ffff",
    }));
    started
        .tui
        .wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border("light"));
}

#[test]
#[cfg_attr(
    windows,
    ignore = "Windows always answers through reg; see auto_in_terminal"
)]
fn auto_is_dark_when_neither_the_os_nor_the_terminal_says() {
    let started = auto_in_terminal(None);
    started.tui.wait_for_cell(WAIT, "memo.wav", |_| true);
    started
        .tui
        .wait_for_cell(WAIT, "—", |cell| cell.fgcolor() == border("dark"));
}
