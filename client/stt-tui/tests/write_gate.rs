//! Roadmap item 67, third amendment: while the event stream is not live every write is refused in
//! place, as the browser's write gate refuses it: nothing is sent, and a write key pressed anyway
//! names the refused action and the browser's reason on the notice line. The footer leaves the write
//! keys out meanwhile - no hint shows a key that cannot act (the user, 2026-11-12, replacing the
//! muted keys chosen on 2026-11-09).

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use terminal::{EventService, Tui, WIDE, offers, position_of};

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
    tui.settle();
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

/// The review (R15): the header's theme label and the queue's upload area are the `t` and `u`
/// keys by mouse, so while the stream is down a click on them is refused as the key is.
#[test]
fn clicks_on_the_theme_label_and_the_upload_area_are_refused_while_the_stream_is_down() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), WIDE, 30);
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    service.next_get_from(&tui, WAIT).answer("200 OK", DEFAULTS);
    tui.wait_for(WAIT, |screen| {
        screen.contains("Live") && offers(screen, &["theme"], &[])
    });
    drop(first);
    let screen = tui.wait_for(WAIT, |text| text.contains("Reconnecting"));

    // The theme label: nothing is sent, the mode stays, and the notice says why.
    let (column, row) = position_of(&screen, "Theme: system");
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| notice(text, "theme").is_some());
    assert!(
        notice(&screen, "theme").unwrap().contains(REASON),
        "{screen}"
    );
    assert!(screen.contains("Theme: system"), "{screen}");

    // The upload area: the dialog does not open, and the notice says why.
    let (column, row) = position_of(&screen, "Choose audio files");
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| notice(text, "upload").is_some());
    assert!(
        notice(&screen, "upload").unwrap().contains(REASON),
        "{screen}"
    );
    tui.settle();
    let screen = tui.text();
    assert!(
        !screen.contains("Esc cancel"),
        "no dialog opened:\n{screen}"
    );
    assert!(
        !service.command_arrived(),
        "a refused click is not sent:\n{screen}"
    );
}

/// Discovered in R15 (the user: "double-click should do the same as Enter"): a double-click on a
/// file in an upload dialog opened while live is Enter on it, so once the stream drops it is refused
/// as Enter is - the reason on the notice line, the dialog left open, nothing sent.
#[test]
fn a_double_click_on_a_file_is_refused_as_enter_is_while_the_stream_is_down() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    let files = tempfile::tempdir().unwrap();
    // b.mp3 is the row below a.mp3, which the dialog's barrier moves to and back.
    for name in ["a.mp3", "b.mp3"] {
        std::fs::write(files.path().join(name), b"RIFF-FAKE-WAVE").unwrap();
    }
    let mut tui = Tui::start_in(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        WIDE,
        30,
        files.path(),
    );
    let mut first = service.next_stream(WAIT);
    first.send("snapshot", SNAPSHOT);
    tui.wait_for(WAIT, |text| {
        text.contains("Live") && text.contains("Choose audio files")
    });
    service.capture_next_settings();
    tui.press(b"u");
    service.next_get_from(&tui, WAIT).answer("200 OK", DEFAULTS);
    tui.wait_for(WAIT, |text| {
        text.contains("Upload audio") && text.contains("a.mp3")
    });
    drop(first);
    let screen = tui.wait_for(WAIT, |text| text.contains("Reconnecting"));

    // Back to back: the second click well inside the double-click's 500 ms on any machine.
    let (column, row) = position_of(&screen, "a.mp3");
    tui.click(column, row);
    tui.click(column, row);
    let screen = tui.wait_for(WAIT, |text| notice(text, "upload").is_some());
    assert!(
        notice(&screen, "upload").unwrap().contains(REASON),
        "{screen}"
    );
    tui.settle_in_list();
    let screen = tui.text();
    assert!(
        screen.contains("Upload audio"),
        "the dialog stays:\n{screen}"
    );
    assert!(
        !service.upload_arrived(),
        "a refused double-click uploads nothing:\n{screen}"
    );
}
