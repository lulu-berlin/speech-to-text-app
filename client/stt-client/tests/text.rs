//! ADR 0067: the Rust clients' text is the shared catalog, not a second copy of it - every key and
//! template compiled into the crate is the one in `frontend/src/locales/en.json`, a placeholder
//! takes the value it is given, a plural its count, and the app's language (item 88) picks the
//! catalog and the formats.

use std::collections::BTreeMap;
use std::path::Path;

use stt_client::{
    Locale, catalog, date_medium_in, duration_in, effective_ui_locale, format_locale_for,
    integer_in, number_in, template, text, text_in,
};

fn locale(ui: &str, format: &str) -> Locale {
    Locale {
        ui: ui.to_string(),
        format: format.to_string(),
    }
}

#[test]
fn the_compiled_text_is_the_shared_catalog_and_placeholders_are_filled() {
    let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../frontend/src/locales/en.json");
    let json: BTreeMap<String, String> = serde_json::from_str(
        &std::fs::read_to_string(&file).expect("the shared UI text catalog is readable"),
    )
    .expect("the shared UI text catalog is JSON of strings");
    let compiled: BTreeMap<String, String> = catalog("en")
        .iter()
        .map(|(key, text)| ((*key).to_string(), (*text).to_string()))
        .collect();
    assert_eq!(compiled, json, "the crate compiles exactly the catalog");

    assert_eq!(text("queue.title", &[]), json["queue.title"]);
    assert_eq!(
        text("tui.upload.failed", &[("error", "no such file")]),
        json["tui.upload.failed"].replace("{error}", "no such file")
    );
    assert_eq!(
        text("no.such.key", &[]),
        "no.such.key",
        "a missing key shows itself"
    );
}

#[test]
fn the_default_terminals_are_named_by_the_catalog() {
    // The Settings row names what opens the TUI on Windows and macOS when no command is stored:
    // product names, which the catalog carries rather than the TUI's code.
    assert_eq!(template("tui.settings.terminalWindows"), Some("PowerShell"));
    assert_eq!(template("tui.settings.terminalMacos"), Some("Terminal"));
}

#[test]
fn a_plural_takes_its_count_and_the_locale_its_category() {
    let english = locale("en", "en-US");
    assert_eq!(
        text_in(&english, "tui.queue.tasks", &[("count", "1")]),
        "1 task"
    );
    assert_eq!(
        text_in(&english, "tui.queue.tasks", &[("count", "3")]),
        "3 tasks"
    );
    // Russian has one, few and many: 1, 2 and 5 take different branches.
    let russian = locale("ru", "ru-RU");
    let tasks = |count: &str| text_in(&russian, "tui.queue.tasks", &[("count", count)]);
    assert_eq!(tasks("1"), "1 задача");
    assert_eq!(tasks("2"), "2 задачи");
    assert_eq!(tasks("5"), "5 задач");
    // Arabic's digits are its own, and its zero category is a branch of its own.
    let arabic = locale("ar", "ar-EG");
    assert_eq!(
        text_in(&arabic, "tui.queue.tasks", &[("count", "0")]),
        "لا مهام"
    );
    assert_eq!(
        text_in(&arabic, "tui.queue.tasks", &[("count", "3")]),
        "٣ مهام"
    );
}

#[test]
fn the_app_language_picks_the_catalog_and_the_format_locale() {
    let tags = |list: &[&str]| {
        list.iter()
            .map(|tag| (*tag).to_string())
            .collect::<Vec<_>>()
    };
    // The stored language wins when it ships a catalog; otherwise the system's, as the browser
    // resolves it: an exact tag, the split languages' variants, then a base language.
    assert_eq!(effective_ui_locale(Some("de"), &tags(&["fr-FR"])), "de");
    assert_eq!(effective_ui_locale(Some("sw"), &tags(&["fr-FR"])), "fr");
    assert_eq!(effective_ui_locale(None, &tags(&["pt-BR"])), "pt-BR");
    assert_eq!(effective_ui_locale(None, &tags(&["zh-TW"])), "zh-Hant");
    assert_eq!(effective_ui_locale(None, &tags(&["es-MX"])), "es-419");
    assert_eq!(effective_ui_locale(None, &tags(&["C.UTF-8"])), "en");
    // The format locale is the system's tag when it names the same UI locale, else the locale's
    // canonical region.
    assert_eq!(format_locale_for("de", &tags(&["de-AT"])), "de-AT");
    assert_eq!(format_locale_for("de", &tags(&["de"])), "de-DE");
    assert_eq!(format_locale_for("pt-BR", &tags(&["pt-PT"])), "pt-BR");

    // The words and the formats follow it: German's decimal comma and medium date, French's
    // month abbreviation, English's own, Arabic's digits.
    let german = locale("de", "de-DE");
    let french = locale("fr", "fr-FR");
    let english = locale("en", "en-US");
    let arabic = locale("ar", "ar-EG");
    assert_eq!(number_in(&german, 57.7, 1), "57,7");
    assert_eq!(number_in(&english, 57.7, 1), "57.7");
    assert_eq!(integer_in(&arabic, 42), "٤٢");
    // The API's UTC date, in each locale's medium date: CLDR's `dd.MM.y` for German, `d MMM y`
    // for French and `MMM d, y` for English.
    assert_eq!(date_medium_in(&german, 1_791_537_645_000), "09.10.2026");
    assert_eq!(date_medium_in(&french, 1_791_537_645_000), "9 oct. 2026");
    assert_eq!(date_medium_in(&english, 1_791_537_645_000), "Oct 9, 2026");
    assert_eq!(duration_in(&english, 1_185_000), "19:45");
    assert_eq!(duration_in(&arabic, 1_185_000), "١٩:٤٥");
}
