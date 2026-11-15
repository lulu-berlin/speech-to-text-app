//! Roadmap item 67, third amendment: the header always carries the product name, the connection's
//! state (Connecting, Live, Reconnecting) and the theme mode, in the browser's words; a click on the
//! mode cycles it as `t` does (the user, 2026-11-08: "I'd be happy to have a visible toggle").

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::time::Duration;

use serde_json::{Value, json};
use settings_fixture::{body, stored};
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
/// The stored settings the startup read answers: every field the contract's defaults (mode auto).
const DEFAULTS: &str = include_str!("../../../docs/api/settings.json");
const WAIT: Duration = Duration::from_secs(20);

/// The header's first row: the product name and, right of it, the connection and the mode.
fn first_row(text: &str) -> &str {
    text.lines().next().unwrap_or_default()
}

#[test]
fn the_header_names_the_connection_and_the_mode_and_a_click_cycles_it() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 100, 30);
    let mut first = service.next_stream(WAIT);

    // Before the first snapshot the stream is still connecting.
    tui.wait_for(WAIT, |text| first_row(text).contains("Connecting"));

    // The snapshot makes it live; the stored mode, auto, reads as the browser's "system".
    first.send("snapshot", SNAPSHOT);
    service.next_get_from(&tui, WAIT).answer("200 OK", DEFAULTS);
    let screen = tui.wait_for(WAIT, |text| {
        first_row(text).contains("Live") && first_row(text).contains("Theme: system")
    });
    assert!(
        first_row(&screen).contains("Speech-to-Text"),
        "the product name stays beside them:\n{screen}"
    );

    // A dropped stream reconnects, and a new snapshot makes it live again.
    drop(first);
    tui.wait_for(WAIT, |text| first_row(text).contains("Reconnecting"));
    let mut second = service.next_stream(WAIT);
    second.send("snapshot", SNAPSHOT);
    let screen = tui.wait_for(WAIT, |text| first_row(text).contains("Live"));

    // A click on the mode does what `t` does: the next mode, light, saved with every other setting.
    let row = first_row(&screen);
    let byte = row.find("Theme: system").unwrap();
    tui.click(row[..byte].chars().count() as u16 + 2, 0);
    let write = service.next_command(WAIT);
    assert!(
        write.request().line.starts_with("PUT /api/settings "),
        "{:?}",
        write.request().line
    );
    assert_eq!(
        serde_json::from_str::<Value>(&write.request().body).unwrap(),
        body(DEFAULTS, json!({ "themeMode": "light" }))
    );
    write.answer("200 OK", &stored(DEFAULTS, json!({ "themeMode": "light" })));
    tui.wait_for(WAIT, |text| first_row(text).contains("Theme: light"));
}
