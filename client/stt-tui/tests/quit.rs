//! Roadmap item 67 (discovered, 2026-11-06): the user is never trapped. `q`, Escape and Ctrl-C each
//! quit the TUI, and the terminal is left as it was - the main screen back and, on Unix, the
//! terminal cooked again (raw mode off).

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use settings_fixture::{FRONTENDS, SETTINGS};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");

#[test]
fn q_escape_and_ctrl_c_each_quit_the_tui_and_give_the_terminal_back() {
    for (key, bytes) in [("q", &b"q"[..]), ("Escape", b"\x1b"), ("Ctrl-C", b"\x03")] {
        let service = EventService::start();
        let dir = tempfile::tempdir().unwrap();
        support::write_bootstrap(dir.path(), service.port);
        let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 24);
        let mut stream = service.next_stream(Duration::from_secs(20));
        stream.send("snapshot", SNAPSHOT);
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

#[test]
fn q_walks_back_page_by_page_as_escape_does_before_it_quits() {
    let open = terminal::open_transcript();
    let mut tui = open.tui;
    tui.wait_for(Duration::from_secs(20), |text| {
        text.contains("Transcript: interview.mp3")
    });

    tui.press(b"q");
    tui.wait_for(Duration::from_secs(5), |text| {
        text.contains("/ Search") && !text.contains("Transcript: interview.mp3")
    });
    assert!(
        tui.exit_within(Duration::from_millis(200)).is_none(),
        "the first `q` only leaves the transcript"
    );

    tui.press(b"q");
    tui.wait_for(Duration::from_secs(5), |text| {
        text.contains("Choose audio files")
    });
    assert!(
        tui.exit_within(Duration::from_millis(200)).is_none(),
        "the second `q` only returns to the queue"
    );

    tui.press(b"q");
    let status = tui
        .exit_within(Duration::from_secs(5))
        .unwrap_or_else(|| panic!("the third `q` quits; screen:\n{}", tui.text()));
    assert!(status.success(), "{status:?}");
}

#[test]
fn q_is_a_character_in_a_settings_dropdown_rather_than_escape() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 120, 30);
    let mut stream = service.next_stream(Duration::from_secs(20));
    stream.send("snapshot", SNAPSHOT);
    tui.wait_for(Duration::from_secs(20), |text| text.contains("memo.wav"));
    service.capture_next_settings();
    tui.press(b"S");
    service
        .next_get_from(&tui, Duration::from_secs(20))
        .answer("200 OK", SETTINGS);
    service
        .next_get_from(&tui, Duration::from_secs(20))
        .answer("200 OK", FRONTENDS);
    tui.wait_for(Duration::from_secs(20), |text| text.contains("Threads"));

    // Enter opens the device's dropdown; `q` is a character in it (the language list narrows on
    // it), so it neither closes the dropdown nor quits: Escape closes it.
    tui.press(b"\r");
    tui.wait_for(Duration::from_secs(20), |text| text.contains("✓ Auto"));
    tui.press(b"q");
    // The cursor's round trip behind `q` is drawn only by a TUI that is still running, with the
    // dropdown open.
    tui.settle_in_list();
    assert!(
        tui.text().contains("Esc close"),
        "the dropdown stays open:\n{}",
        tui.text()
    );
    assert!(
        tui.exit_within(Duration::ZERO).is_none(),
        "`q` did not quit"
    );
    tui.press(b"\x1b");
    tui.wait_for(Duration::from_secs(5), |text| !text.contains("Esc close"));
    assert!(
        tui.text().contains("Restore default settings"),
        "Settings stays open"
    );
}
