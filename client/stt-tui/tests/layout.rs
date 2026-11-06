//! Roadmap item 67: the terminal's shape and colors. A resize reflows the open screen without a
//! crash, and `NO_COLOR` emits no SGR (color or reverse-video) sequence, marking the selected row
//! with `>` instead so the selection still shows.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const SETTINGS: &str = include_str!("../../../docs/api/settings.json");
const FRONTENDS: &str = include_str!("../../../docs/api/frontends.json");
const WAIT: Duration = Duration::from_secs(20);

/// Whether `raw` holds an SGR sequence - `ESC [ params m` - that sets a color or an attribute.
/// A reset (`ESC[0m`, or no parameters) only restores the default and is not one.
fn has_sgr(raw: &str) -> bool {
    let bytes = raw.as_bytes();
    let mut from = 0;
    while let Some(found) = raw[from..].find("\x1b[") {
        let start = from + found + 2;
        let mut at = start;
        while at < bytes.len() && (bytes[at].is_ascii_digit() || bytes[at] == b';') {
            at += 1;
        }
        if at < bytes.len() && bytes[at] == b'm' {
            let params = &raw[start..at];
            if params
                .split(';')
                .any(|param| param != "0" && !param.is_empty())
            {
                return true;
            }
        }
        from = start;
        if from >= raw.len() {
            break;
        }
    }
    false
}

#[test]
fn a_resize_reflows_without_a_crash_and_no_color_emits_no_sgr() {
    // The settings open in a wide terminal; narrowed to 40x12 the screen still draws.
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| text.contains("memo.wav"));
    tui.press(b"s");
    service.next_get(WAIT).answer("200 OK", SETTINGS);
    service.next_get(WAIT).answer("200 OK", FRONTENDS);
    tui.wait_for(WAIT, |text| {
        text.contains("Threads") && text.contains("Settings")
    });

    tui.resize(40, 12);
    let small = tui.wait_for(WAIT, |text| text.contains("Settings"));
    assert!(
        small.lines().all(|line| line.chars().count() <= 40),
        "the screen fits the 40 columns:\n{small}"
    );
    assert!(
        tui.exit_within(Duration::from_millis(200)).is_none(),
        "the TUI survives the resize"
    );

    tui.resize(120, 24);
    tui.wait_for(WAIT, |text| {
        text.contains("Threads") && text.contains("Terminal command")
    });

    // With NO_COLOR the TUI writes no SGR and marks the selected row; without it, the selected
    // row is reversed, which is an SGR sequence.
    let plain = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        80,
        24,
        &[("NO_COLOR", "1")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    let screen = plain.wait_for(WAIT, |text| text.contains("> lecture.m4a"));
    assert!(
        !has_sgr(&plain.raw()),
        "NO_COLOR writes no SGR sequence; screen:\n{screen}"
    );

    let colored = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 80, 24);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    colored.wait_for(WAIT, |text| text.contains("lecture.m4a"));
    let raw = colored.raw();
    assert!(
        has_sgr(&raw),
        "the selected row is reversed without NO_COLOR; raw: {:?}",
        raw.chars().take(400).collect::<String>()
    );
}
