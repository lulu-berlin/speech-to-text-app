//! Roadmap item 66, test-list D11: the client resolves the same per-user directories as the service.
//! The cases live in `docs/service-dirs.json`, which the service's `ServiceDirsSpec` reads too, so
//! `stt-app` and the tray always look where the service writes `config.json` and `runtime.json`.

use std::collections::HashMap;
use std::path::PathBuf;

use stt_client::{Os, ServiceDirs};

#[test]
fn the_client_resolves_every_case_of_the_shared_directory_table() {
    let table = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../docs/service-dirs.json");
    let table: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&table).unwrap()).unwrap();

    for case in table["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let env: HashMap<String, String> = case["env"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(key, value)| (key.clone(), value.as_str().unwrap().to_owned()))
            .collect();
        let os = Os::from(case["os"].as_str().unwrap());

        let resolved = ServiceDirs::resolve(|name| env.get(name).cloned(), os)
            .unwrap_or_else(|error| panic!("{name}: {error}"));

        assert_eq!(
            resolved.dirs.config_dir,
            PathBuf::from(case["configDir"].as_str().unwrap()),
            "{name}"
        );
        assert_eq!(
            resolved.dirs.runtime_dir,
            PathBuf::from(case["runtimeDir"].as_str().unwrap()),
            "{name}"
        );
        assert_eq!(
            resolved.warning.is_some(),
            case["warns"].as_bool().unwrap(),
            "{name}"
        );
    }
}
