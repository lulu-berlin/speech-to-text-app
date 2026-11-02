mod support;

use stt_client::{Descriptor, Launch, Registry, tray_entries};

fn browser() -> Descriptor {
    Descriptor {
        version: 1,
        id: "browser".into(),
        name: "Browser".into(),
        launch: Launch::Browser { url: "/".into() },
    }
}

fn tui() -> Descriptor {
    Descriptor {
        version: 1,
        id: "tui".into(),
        name: "Terminal TUI".into(),
        launch: Launch::Terminal {
            command: "stt-tui".into(),
            args: Vec::new(),
        },
    }
}

/// Roadmap item 66, test list item 3 (the tray's list): the tray's menu holds one entry per installed frontend,
/// in the registry's order, each with its kind, and the default frontend is marked. A default that is not
/// installed marks nothing, so an uninstalled frontend is never presented as the default.
#[test]
fn the_tray_lists_every_installed_frontend_and_marks_the_default() {
    let registry = Registry {
        frontends: vec![browser(), tui()],
        skipped: Vec::new(),
    };

    let entries = tray_entries(&registry, Some("tui"));

    assert_eq!(entries.len(), 2);
    assert_eq!(entries[0].id, "browser");
    assert_eq!(entries[0].name, "Browser");
    assert_eq!(entries[0].kind, "browser");
    assert!(!entries[0].is_default);
    assert_eq!(entries[1].id, "tui");
    assert_eq!(entries[1].kind, "terminal");
    assert!(entries[1].is_default);

    assert!(
        tray_entries(&registry, None)
            .iter()
            .all(|entry| !entry.is_default)
    );
    assert!(
        tray_entries(&registry, Some("gone"))
            .iter()
            .all(|entry| !entry.is_default)
    );
}

// ADR 0064, item 66's items 2-3: the tray lists the same registry the service serves and `stt-app
// register` writes - `<config>/frontends` - and not a descriptor dropped in the config dir itself.
#[test]
fn the_tray_reads_the_frontends_directory_the_service_and_stt_app_use() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());
    std::fs::create_dir_all(dir.path().join("frontends")).unwrap();
    support::write(
        &dir.path().join("frontends"),
        "browser.json",
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    );
    support::write(
        dir.path(),
        "stray.json",
        r#"{"version":1,"id":"stray","kind":"browser","name":"Stray","url":"/"}"#,
    );

    let ids: Vec<String> = stt_client::registry_for(&dirs)
        .frontends
        .iter()
        .map(|frontend| frontend.id.clone())
        .collect();
    assert_eq!(ids, vec!["browser".to_string()]);
}

// ADR 0064, item 66: the tray's menu follows the registry, so a frontend installed while the tray
// runs must be visible to it. The fingerprint is what it compares between looks.
#[test]
fn the_registry_fingerprint_changes_when_a_frontend_is_installed_or_removed() {
    let dir = tempfile::tempdir().unwrap();
    let dirs = support::dirs_in(dir.path());
    std::fs::create_dir_all(dir.path().join("frontends")).unwrap();
    assert!(stt_client::registry_fingerprint(&dirs).is_empty());

    support::write(
        &dir.path().join("frontends"),
        "browser.json",
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    );
    let installed = stt_client::registry_fingerprint(&dirs);
    assert_eq!(installed.len(), 1);
    assert_eq!(installed[0].0, "browser.json");

    std::fs::remove_file(dir.path().join("frontends").join("browser.json")).unwrap();
    assert!(stt_client::registry_fingerprint(&dirs).is_empty());
}
