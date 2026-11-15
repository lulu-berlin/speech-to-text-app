#![allow(dead_code)]

//! The settings the settings tests start from, and the answers and write bodies derived from them.

use serde_json::Value;

/// Stored settings with every field set, so a write that drops one shows.
pub const SETTINGS: &str = r#"{"device":"auto","threads":4,"defaultLanguage":"de","speakersMode":"fixed","speakersCount":3,"maxSpeakers":12,"deviceFallback":null,"defaultFrontend":"browser","terminalCommand":"kitty -e","appLanguage":null,"themeMode":"auto","availableDevices":["cpu","auto","gpu:0","gpu:1"],"gpuNames":{"gpu:0":"NVIDIA GeForce RTX 3060","gpu:1":"AMD Radeon RX 6600"},"deviceProbeError":null}"#;

/// Two frontends installed, as `GET /api/frontends` lists them (roadmap item 66).
pub const FRONTENDS: &str = r#"{"frontends":[{"id":"browser","name":"Browser","kind":"browser"},{"id":"window","name":"Window","kind":"gui"}]}"#;

/// The fixture's GPUs as the settings page names them: the service's index and the probe's name.
pub const GPU0: &str = "GPU 0 — NVIDIA GeForce RTX 3060";
pub const GPU1: &str = "GPU 1 — AMD Radeon RX 6600";

/// The value column of the settings row labelled `label`: what follows the label on its line.
pub fn value(screen: &str, label: &str) -> String {
    screen
        .lines()
        .find_map(|line| {
            let line = line.trim_matches(['│', ' ']);
            line.strip_prefix(label).map(|rest| rest.trim().to_string())
        })
        .unwrap_or_default()
}

/// `base` (the service's settings) with `change`'s fields replaced: the answer to a stored write.
pub fn stored(base: &str, change: Value) -> String {
    changed(base, change).to_string()
}

/// The body of the write that makes `change` to `base`: every stored setting, as the contract
/// takes them - without the read-only device list, GPU names, fallback and probe error.
pub fn body(base: &str, change: Value) -> Value {
    let mut settings = changed(base, change);
    for read_only in [
        "deviceFallback",
        "availableDevices",
        "gpuNames",
        "deviceProbeError",
    ] {
        settings.as_object_mut().unwrap().remove(read_only);
    }
    settings
}

fn changed(base: &str, change: Value) -> Value {
    let mut settings: Value = serde_json::from_str(base).unwrap();
    for (key, value) in change.as_object().unwrap() {
        settings[key] = value.clone();
    }
    settings
}
