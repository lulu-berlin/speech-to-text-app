//! ADR 0066: the Rust clients' text is the shared catalog, not a second copy of it - every key and
//! template compiled into the crate is the one in `ui-text/en.json`, and a placeholder takes the
//! value it is given.

use std::collections::BTreeMap;
use std::path::Path;

use stt_client::{catalog, text};

#[test]
fn the_compiled_text_is_the_shared_catalog_and_placeholders_are_filled() {
    let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ui-text/en.json");
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
        text("upload.prompt", &[("path", "/tmp/memo.mp3")]),
        json["upload.prompt"].replace("{path}", "/tmp/memo.mp3")
    );
    assert_eq!(
        text("upload.failed", &[("error", "no such file")]),
        json["upload.failed"].replace("{error}", "no such file")
    );
    assert_eq!(
        text("no.such.key", &[]),
        "no.such.key",
        "a missing key shows itself"
    );
}
