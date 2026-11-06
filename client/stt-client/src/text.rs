//! The shared UI text every frontend shows (ADR 0066): compiled from `ui-text/<locale>.json` by the
//! build script, so the tray, the CLI and the TUI use the browser's words.

include!(concat!(env!("OUT_DIR"), "/ui_text.rs"));

/// The locale every frontend ships today; item 88 adds the others and the locale selection.
pub const DEFAULT_LOCALE: &str = "en";

/// The text for `key` in the default locale, with each `{name}` placeholder replaced by the value
/// in `args`. A key in no catalog is returned as itself, so what is missing shows up rather than
/// disappearing.
pub fn text(key: &str, args: &[(&str, &str)]) -> String {
    text_in(DEFAULT_LOCALE, key, args)
}

/// [`text`] in `locale`, falling back to the default locale and then to `key`.
pub fn text_in(locale: &str, key: &str, args: &[(&str, &str)]) -> String {
    let template = catalog(locale)
        .iter()
        .find(|(candidate, _)| *candidate == key)
        .or_else(|| catalog(DEFAULT_LOCALE).iter().find(|(k, _)| *k == key))
        .map(|(_, template)| *template)
        .unwrap_or(key);
    let mut text = template.to_string();
    for (name, value) in args {
        text = text.replace(&format!("{{{name}}}"), value);
    }
    text
}
