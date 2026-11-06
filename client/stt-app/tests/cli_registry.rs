use std::process::Command;

#[test]
fn list_warns_about_bad_descriptors_and_still_lists_the_valid_frontend() {
    let dir = tempfile::tempdir().unwrap();
    let frontends = dir.path().join("frontends");
    std::fs::create_dir(&frontends).unwrap();
    std::fs::write(
        frontends.join("browser.json"),
        r#"{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}"#,
    )
    .unwrap();
    std::fs::write(frontends.join("broken.json"), r#"{"version":1,"id":"#).unwrap();
    std::fs::write(
        frontends.join("hologram.json"),
        r#"{"version":1,"id":"hologram","kind":"hologram","name":"Hologram"}"#,
    )
    .unwrap();

    let out = Command::new(env!("CARGO_BIN_EXE_stt-app"))
        .arg("list")
        .env("STT_CONFIG_DIR", dir.path())
        .env_remove("STT_RUNTIME_DIR")
        .output()
        .unwrap();

    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        out.status.success(),
        "exit {:?}, stderr: {stderr}",
        out.status
    );
    assert_eq!(stdout.lines().count(), 1, "stdout: {stdout}");
    assert!(stdout.contains("browser"), "stdout: {stdout}");
    assert!(stderr.contains("broken.json"), "stderr: {stderr}");
    assert!(stderr.contains("hologram.json"), "stderr: {stderr}");
}
