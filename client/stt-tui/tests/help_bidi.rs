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
    let help = help_under(200, &[("TERM", "mlterm")]);
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
    let help = help_under(200, &[("TERM", "xterm-256color"), ("MLTERM", "")]);
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

/// The `?` reference over the queue page of a TUI `columns` wide, started with `env`.
fn help_under(columns: u16, env: &[(&str, &str)]) -> String {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        columns,
        24,
        env,
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    // A narrow footer folds its hints behind `? more`.
    tui.wait_for(WAIT, |text| {
        (text.contains("? help") || text.contains("? more")) && text.contains("memo.wav")
    });
    tui.press(b"?");
    tui.wait_for(WAIT, |text| text.contains("Queue keys"))
}

/// Whether every word of the catalog text `key` is on `screen`: the whole notice can be read,
/// however it is wrapped (its first line ends where the colon was).
fn reads_whole(screen: &str, key: &str) -> bool {
    stt_client::text(key, &[])
        .split_whitespace()
        .all(|word| screen.contains(word.trim_end_matches(':')))
}

#[test]
fn the_help_names_kittys_settings_when_the_environment_names_kitty() {
    // kitty turns each right-to-left word's letters itself unless `force_ltr` is on: where the
    // environment names kitty - its TERM, or its window id, which a multiplexer inside kitty
    // keeps - the TUI writes for kitty's default mode, and the reference says so and names
    // `force_ltr` and STT_TUI_RTL_WORDS, unset or empty alike.
    for env in [
        &[("TERM", "xterm-kitty")][..],
        &[("TERM", "xterm-256color"), ("KITTY_WINDOW_ID", "1")],
        &[("TERM", "xterm-kitty"), ("STT_TUI_RTL_WORDS", "")],
    ] {
        let help = help_under(200, env);
        assert!(
            reads_whole(&help, "tui.help.kittyWords"),
            "under {env:?} the notice says the TUI writes for kitty's default mode:\n{help}"
        );
    }

    // At 80 columns the notice wraps to the reference's width: all of it can be read.
    let help = help_under(80, &[("TERM", "xterm-kitty")]);
    assert!(
        reads_whole(&help, "tui.help.kittyWords"),
        "the whole notice on kitty's default mode is on an 80-column screen:\n{help}"
    );

    // With the mode off (`0`), the notice says kitty needs `force_ltr yes`.
    let help = help_under(80, &[("TERM", "xterm-kitty"), ("STT_TUI_RTL_WORDS", "0")]);
    assert!(
        reads_whole(&help, "tui.help.kitty") && !reads_whole(&help, "tui.help.kittyWords"),
        "the notice asks for force_ltr yes:\n{help}"
    );

    // A terminal that names neither kitty nor mlterm shows no such line.
    let help = help_under(
        200,
        &[
            ("TERM", "xterm-256color"),
            ("KITTY_WINDOW_ID", ""),
            ("MLTERM", ""),
        ],
    );
    assert!(
        !help.contains("force_ltr") && !help.contains("STT_TUI_RTL_WORDS"),
        "a terminal that names no kitty shows no such line:\n{help}"
    );
}
