//! Roadmap item 67, third amendment: while the event stream is not live every write is refused in
//! place, as the browser's write gate refuses it: nothing is sent, and a write key pressed anyway
//! names the refused action and the browser's reason on the notice line. The footer leaves the write
//! keys out meanwhile - no hint shows a key that cannot act (the user, 2026-11-12, replacing the
//! muted keys chosen on 2026-11-09).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, WIDE, offers};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// The stored settings the startup read answers, so `t` has settings to write.
const DEFAULTS: &str = include_str!("../../../docs/api/settings.json");
const REASON: &str = "Can't reach the app's service";
const WAIT: Duration = Duration::from_secs(20);

/// The notice line naming `action`, if one is on screen.
fn notice(text: &str, action: &str) -> Option<String> {
    let needle = format!("Not sent: {action}");
    text.lines()
        .find(|line| line.contains(&needle))
        .map(str::to_string)
}

#[test]
fn the_footer_leaves_out_the_selected_rows_write_keys_while_the_stream_is_down() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), WIDE, 30);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("memo.wav") && text.contains("Live")
    });
    tui.press(b"H");
    // Live, on the failed call.ogg: it retries and deletes.
    tui.wait_for(WAIT, |text| text.contains("call.ogg"));
    tui.press(b"j");
    tui.wait_for(WAIT, |screen| {
        offers(screen, &["retry", "Del delete", "Enter open"], &[])
    });

    // The stream drops: the row's writes leave the footer, its reads stay.
    drop(first);
    tui.wait_for(WAIT, |screen| {
        screen.contains("Reconnecting")
            && offers(
                screen,
                &["Enter open", "/search", "filter"],
                &["retry", "Del delete"],
            )
    });
}

#[test]
fn writes_are_refused_with_the_reason_while_the_stream_is_down() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), WIDE, 30);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    service.next_get_from(&tui, WAIT).answer("200 OK", DEFAULTS);

    // Live: the running lecture.m4a pauses and cancels, and `t` cycles the theme.
    tui.wait_for(WAIT, |screen| {
        screen.contains("Live") && offers(screen, &["pause", "cancel", "theme"], &[])
    });

    // The stream drops: the write keys leave the footer; moving and the help stay.
    drop(first);
    tui.wait_for(WAIT, |screen| {
        screen.contains("Reconnecting")
            && offers(
                screen,
                &["↑↓/j/k move", "? help"],
                &["pause", "cancel", "theme", "Space queue pause"],
            )
    });

    // `p` on the running lecture.m4a, and `t`, pressed anyway: nothing is sent, and each says why on
    // the notice line.
    tui.press(b"p");
    let screen = tui.wait_for(WAIT, |text| notice(text, "pause").is_some());
    assert!(
        notice(&screen, "pause").unwrap().contains(REASON),
        "the refusal carries the browser's reason:\n{screen}"
    );
    tui.press(b"t");
    let screen = tui.wait_for(WAIT, |text| notice(text, "theme").is_some());
    assert!(
        notice(&screen, "theme").unwrap().contains(REASON),
        "{screen}"
    );
    std::thread::sleep(Duration::from_millis(300));
    assert!(
        !service.command_arrived(),
        "a refused write is not sent:\n{screen}"
    );

    // A new snapshot makes the stream live again: the keys are back, and `p` is sent.
    let mut second = service.next_stream(WAIT);
    second.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |screen| {
        screen.contains("Live") && offers(screen, &["pause", "theme"], &[])
    });
    tui.press(b"p");
    let pause = service.next_command(WAIT);
    assert!(
        pause.request().line.starts_with("POST /api/tasks/5/"),
        "{:?}",
        pause.request().line
    );
}
