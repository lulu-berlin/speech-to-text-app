//! Compiles the shared UI text catalog (`ui-text/<locale>.json`, ADR 0066) into Rust constants, so
//! the tray, the CLI and the TUI show the same words as the browser without a second copy.

use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::path::Path;

fn main() {
    let ui_text = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ui-text");
    println!("cargo:rerun-if-changed={}", ui_text.display());
    // Every locale file in the catalog directory becomes a constant, so adding a locale
    // (roadmap item 88) needs no change here.
    let mut locales: Vec<String> = fs::read_dir(&ui_text)
        .unwrap_or_else(|error| panic!("cannot read {}: {error}", ui_text.display()))
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
        ui_text.display()
    );
    let mut generated = String::new();
    for locale in &locales {
        let file = ui_text.join(format!("{locale}.json"));
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
            locale.to_ascii_uppercase(),
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
            locale.to_ascii_uppercase()
        ));
    }
    generated.push_str("        _ => EN,\n    }\n}\n");
    let out = Path::new(&env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("ui_text.rs");
    fs::write(&out, generated)
        .unwrap_or_else(|error| panic!("cannot write {}: {error}", out.display()));
}
