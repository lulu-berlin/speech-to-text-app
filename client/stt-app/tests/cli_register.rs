//! Roadmap item 66: the user installs the frontends they want, and each is registered with the
//! service as a descriptor in `frontends/`. `stt-app register <file>` copies one in, so a freshly
//! installed app can open a frontend at all, and `stt-app list` then shows it.

#[path = "../../stt-client/tests/support/mod.rs"]
mod support;

use std::process::Command;

#[test]
fn registering_a_descriptor_installs_it_for_list_and_open() {
    let source = tempfile::tempdir().unwrap();
    support::write(
        source.path(),
        "browser.json",
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    );
    let config = tempfile::tempdir().unwrap();

    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .args([
            "register",
            source.path().join("browser.json").to_str().unwrap(),
        ])
        .env("STT_CONFIG_DIR", config.path())
        .env("STT_RUNTIME_DIR", config.path())
        .output()
        .unwrap();

    assert!(
        out.status.success(),
        "stderr: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    let installed = config.path().join("frontends").join("browser.json");
    assert!(installed.is_file(), "expected {installed:?} to exist");
    assert_eq!(
        std::fs::read_to_string(&installed).unwrap(),
        std::fs::read_to_string(source.path().join("browser.json")).unwrap()
    );

    let listed = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .arg("list")
        .env("STT_CONFIG_DIR", config.path())
        .env("STT_RUNTIME_DIR", config.path())
        .output()
        .unwrap();
    assert!(listed.status.success());
    assert!(
        String::from_utf8_lossy(&listed.stdout).starts_with("browser\t"),
        "stdout: {}",
        String::from_utf8_lossy(&listed.stdout)
    );
}
