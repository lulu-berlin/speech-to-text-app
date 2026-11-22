//! Roadmap item 76: the TUI's `?` reference names the terminal's own reorder setting when the
//! environment identifies an emulator that reorders text itself (mlterm, through `TERM` or
//! `MLTERM`), and a terminal that names none shows no such line. The TUI orders each wrapped line
//! itself, so a terminal that applies its own bidi would double-reorder the TUI's visual order.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const WAIT: Duration = Duration::from_secs(20);

#[test]
fn the_help_names_the_terminals_reorder_setting_when_the_environment_names_one() {
    // The environment names mlterm: the reference says its bidi setting must be off.
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        200,
        24,
        &[("TERM", "mlterm")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("? help") && text.contains("memo.wav")
    });
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    assert!(
        help.contains("mlterm"),
        "the reference names mlterm:\n{help}"
    );
    assert!(
        help.contains("bidi"),
        "the reference names the bidi setting:\n{help}"
    );
    assert!(
        help.contains("off"),
        "the reference says why it must be off:\n{help}"
    );

    // A terminal that names no such emulator shows no such line.
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        200,
        24,
        &[("TERM", "xterm-256color"), ("MLTERM", "")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("? help") && text.contains("memo.wav")
    });
    tui.press(b"?");
    let help = tui.wait_for(WAIT, |text| text.contains("Queue keys"));
    assert!(
        !help.contains("mlterm"),
        "a terminal that names none shows no such line:\n{help}"
    );
    let notice = stt_client::text("tui.help.reorder", &[]);
    let first = notice
        .split_once(": ")
        .map(|(first, _)| first)
        .unwrap_or(&notice);
    assert!(
        !help.contains(first),
        "the notice's first line is absent:\n{help}"
    );
}
