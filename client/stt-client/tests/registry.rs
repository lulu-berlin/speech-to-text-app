//! Roadmap item 66, test list item 8: the frontend registry is one JSON descriptor per installed
//! frontend in the per-user config directory (`…/stt-app/frontends`), written by installers. A
//! file the client cannot use — malformed JSON, or a `kind` it does not know, say from a newer
//! installer — is skipped with a warning naming it, and the valid descriptors stay listed: one bad
//! file must not empty the tray's menu.

use std::fs;

use stt_client::{Descriptor, Launch, read_registry};

#[test]
fn a_malformed_or_unknown_kind_descriptor_is_skipped_with_a_warning_and_the_valid_ones_stay_listed()
{
    let dir = tempfile::tempdir().unwrap();
    fs::write(
        dir.path().join("browser.json"),
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    )
    .unwrap();
    fs::write(dir.path().join("broken.json"), r#"{"version":1,"id":"#).unwrap();
    fs::write(
        dir.path().join("hologram.json"),
        r#"{"version":1,"id":"hologram","kind":"hologram","name":"Hologram"}"#,
    )
    .unwrap();

    let registry = read_registry(dir.path()).unwrap();

    assert_eq!(
        registry.frontends,
        vec![Descriptor {
            version: 1,
            id: "browser".into(),
            name: "Browser".into(),
            launch: Launch::Browser { url: "/".into() },
        }]
    );
    let skipped: Vec<&str> = registry.skipped.iter().map(|s| s.file.as_str()).collect();
    assert_eq!(skipped, ["broken.json", "hologram.json"]);
    assert!(registry.skipped.iter().all(|s| !s.reason.is_empty()));
}
