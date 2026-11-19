//! Roadmap item 67, the localization line (item 88's hand-over): the TUI speaks the App language
//! from the browser's catalogs (`frontend/src/locales/<locale>.json`) - the stored `appLanguage`
//! when it ships a catalog, otherwise the system's language, otherwise English - and writes its
//! numbers and dates in that language's format locale, as the browser's `Intl` does.
//!
//! The words are read from the catalogs, not repeated here; the formatted size and dates are the
//! browser's own output for the fixture (`Intl.NumberFormat` and `Intl.DateTimeFormat` with
//! `month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC'`, as `formatTaskMeta` asks).

mod settings_fixture;
#[path = "../../stt-client/tests/support/mod.rs"]
mod support;
mod terminal;

use std::collections::BTreeMap;
use std::time::Duration;

use serde_json::json;
use settings_fixture::stored;
use terminal::{EventService, Tui};

const SNAPSHOT: &str = include_str!("../../../docs/api/snapshot.json");
const DEFAULTS: &str = include_str!("../../../docs/api/settings.json");
const DEFAULTS_EVENT: &str = include_str!("../../../docs/api/settings-updated.defaults.json");
const WAIT: Duration = Duration::from_secs(20);

/// The catalog of `locale`, key to message.
fn catalog(locale: &str) -> BTreeMap<String, String> {
    let json = match locale {
        "en" => include_str!("../../../frontend/src/locales/en.json"),
        "de" => include_str!("../../../frontend/src/locales/de.json"),
        "fr" => include_str!("../../../frontend/src/locales/fr.json"),
        "pt-BR" => include_str!("../../../frontend/src/locales/pt-BR.json"),
        _ => unreachable!("the test reads only these catalogs"),
    };
    serde_json::from_str(json).unwrap()
}

/// Whether the tab bar names the three pages in `locale`'s words.
fn tabs_in(text: &str, locale: &str) -> bool {
    let words = catalog(locale);
    ["queue.title", "history.title", "settings.title"]
        .iter()
        .all(|key| text.contains(words[*key].as_str()))
}

/// The `settings-updated` event that stores `app_language`.
fn language_event(app_language: Option<&str>) -> String {
    stored(DEFAULTS_EVENT, json!({ "appLanguage": app_language }))
}

#[test]
fn the_tui_speaks_the_app_language_and_formats_in_its_locale() {
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let mut tui = Tui::start(env!("CARGO_BIN_EXE_stt-tui").as_ref(), dir.path(), 140, 30);
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);

    // The stored App language is German: the pages, the connection and the size are German.
    service
        .next_get_from(&tui, WAIT)
        .answer("200 OK", &stored(DEFAULTS, json!({ "appLanguage": "de" })));
    let german = catalog("de");
    let screen = tui.wait_for(WAIT, |text| tabs_in(text, "de") && text.contains("57,7 MB"));
    assert!(
        screen.contains(german["app.connection.live"].as_str()),
        "{screen}"
    );
    assert!(
        screen.contains("57,7 MB"),
        "a German decimal comma:\n{screen}"
    );

    // History writes the finish date in the German pattern: CLDR's medium date, `dd.MM.y`.
    tui.press(b"H");
    let screen = tui.wait_for(WAIT, |text| text.contains("interview.mp3"));
    assert!(screen.contains("09.10.2026"), "{screen}");

    // Another frontend stores French: the open TUI switches without a restart.
    stream.send("settings-updated", &language_event(Some("fr")));
    let screen = tui.wait_for(WAIT, |text| tabs_in(text, "fr"));
    assert!(screen.contains("9 oct. 2026"), "{screen}");

    // A stored language without a catalog falls back to the system's, here none: English.
    stream.send("settings-updated", &language_event(Some("sw")));
    let screen = tui.wait_for(WAIT, |text| tabs_in(text, "en"));
    assert!(screen.contains("Oct 9, 2026"), "{screen}");
    drop(tui);

    // No stored language: the system's, Brazilian Portuguese, in its own format locale.
    let service = EventService::start();
    let dir = tempfile::tempdir().unwrap();
    support::write_bootstrap(dir.path(), service.port);
    service.capture_startup_settings();
    let tui = Tui::start_with_env(
        env!("CARGO_BIN_EXE_stt-tui").as_ref(),
        dir.path(),
        140,
        30,
        &[("LC_ALL", "pt_BR.UTF-8")],
    );
    let mut stream = service.next_stream(WAIT);
    stream.send("snapshot", SNAPSHOT);
    service.next_get_from(&tui, WAIT).answer("200 OK", DEFAULTS);
    let screen = tui.wait_for(WAIT, |text| {
        tabs_in(text, "pt-BR") && text.contains("57,7 MB")
    });
    assert!(screen.contains("57,7 MB"), "{screen}");
}
