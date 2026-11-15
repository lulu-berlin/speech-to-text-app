//! Compiles the shared UI text catalogs (`frontend/src/locales/<locale>.json`, ADR 0067) and the
//! shipped UI locales (`docs/api/ui-locales.json`, item 88) into Rust constants, so the tray, the
//! CLI and the TUI show the same words as the browser, in the same languages, without a second
//! copy.

use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let catalogs = root.join("frontend/src/locales");
    let ui_locales = root.join("docs/api/ui-locales.json");
    println!("cargo:rerun-if-changed={}", catalogs.display());
    println!("cargo:rerun-if-changed={}", ui_locales.display());
    // Every locale file in the catalog directory becomes a constant, so adding a locale needs no
    // change here.
    let mut locales: Vec<String> = fs::read_dir(&catalogs)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", catalogs.display()))
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name();
            name.to_str()?.strip_suffix(".json").map(str::to_owned)
        })
        .collect();
    locales.sort();
    assert!(
        locales.iter().any(|locale| locale == "en"),
        "{} must hold en.json, the fallback locale",
        catalogs.display()
    );
    let mut generated = String::new();
    for locale in &locales {
        let file = catalogs.join(format!("{locale}.json"));
        let text = fs::read_to_string(&file)
            .unwrap_or_else(|error| panic!("cannot read {}: {error}", file.display()));
        let messages: BTreeMap<String, String> =
            serde_json::from_str(&text).unwrap_or_else(|error| {
                panic!(
                    "{} is not a JSON object of strings: {error}",
                    file.display()
                )
            });
        generated.push_str(&format!(
            "/// The `{locale}` locale's messages, key to template.\n\
             pub const {}: &[(&str, &str)] = &[{}];\n",
            locale.to_ascii_uppercase().replace('-', "_"),
            messages
                .iter()
                .map(|(key, text)| format!("({key:?}, {text:?})"))
                .collect::<Vec<_>>()
                .join(", ")
        ));
    }
    // A locale the client asks for that has no file falls back to English.
    generated.push_str(
        "/// The messages compiled in for `locale`; an unknown locale falls back to English.\n\
         pub fn catalog(locale: &str) -> &'static [(&'static str, &'static str)] {\n    match locale {\n",
    );
    for locale in &locales {
        generated.push_str(&format!(
            "        {locale:?} => {},\n",
            locale.to_ascii_uppercase().replace('-', "_")
        ));
    }
    generated.push_str("        _ => EN,\n    }\n}\n");

    // The shipped UI locales: each code with the full tag its dates and numbers are formatted
    // with, and the default. The browser reads the same file (item 88).
    let text = fs::read_to_string(&ui_locales)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", ui_locales.display()));
    let json: serde_json::Value = serde_json::from_str(&text)
        .unwrap_or_else(|error| panic!("{} is not JSON: {error}", ui_locales.display()));
    let default = json["default"]
        .as_str()
        .unwrap_or_else(|| panic!("{} has no default locale", ui_locales.display()));
    let entries: Vec<(String, String)> = json["locales"]
        .as_array()
        .unwrap_or_else(|| panic!("{} has no locales", ui_locales.display()))
        .iter()
        .map(|locale| {
            let code = locale["code"].as_str().expect("a locale has a code");
            let format = locale["formatLocale"]
                .as_str()
                .expect("a locale has a format locale");
            (code.to_owned(), format.to_owned())
        })
        .collect();
    generated.push_str(&format!(
        "/// The default UI locale, when no system language is shipped.\n\
         pub const DEFAULT_UI_LOCALE: &str = {default:?};\n\
         /// Every shipped UI locale: its code and the full tag its dates and numbers use.\n\
         pub const UI_LOCALES: &[(&str, &str)] = &[{}];\n",
        entries
            .iter()
            .map(|(code, format)| format!("({code:?}, {format:?})"))
            .collect::<Vec<_>>()
            .join(", ")
    ));

    let out = Path::new(&env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("ui_text.rs");
    fs::write(&out, generated)
        .unwrap_or_else(|error| panic!("cannot write {}: {error}", out.display()));
}
