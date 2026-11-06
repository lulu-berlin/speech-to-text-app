//! Roadmap item 67 (discovered, 2026-11-06): the user is never trapped. `q`, Escape and Ctrl-C each
//! quit the TUI, and the terminal is left as it was - the main screen back and, on Unix, the
//! terminal cooked again (raw mode off).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{Tui, service_with_events};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

#[test]
fn q_escape_and_ctrl_c_each_quit_the_tui_and_give_the_terminal_back() {
    for (key, bytes) in [("q", &b"q"[..]), ("Escape", b"\x1b"), ("Ctrl-C", b"\x03")] {
        let (port, _) = service_with_events(("snapshot", SNAPSHOT));
        let dir = tempfile::tempdir().unwrap();
        support::write_bootstrap(dir.path(), port);
        let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
        tui.wait_for(Duration::from_secs(20), |text| text.contains("lecture.m4a"));
        tui.wait_for_screen(Duration::from_secs(5), vt100::Screen::alternate_screen);
        #[cfg(unix)]
        assert!(!tui.cooked(), "{key}: the TUI draws in raw mode");

        tui.press(bytes);

        let status = tui
            .exit_within(Duration::from_secs(5))
            .unwrap_or_else(|| panic!("{key} did not quit the TUI; screen:\n{}", tui.text()));
        assert!(status.success(), "{key}: {status:?}");
        tui.wait_for_screen(Duration::from_secs(5), |screen| !screen.alternate_screen());
        #[cfg(unix)]
        assert!(tui.cooked(), "{key}: the terminal is cooked again");
    }
}
