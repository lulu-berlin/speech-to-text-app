//! The service's settings (roadmap item 67): read and saved whole, the calls the browser's Settings
//! page makes, with the per-install secret (`docs/api/settings.json`).

use std::collections::BTreeMap;
use std::io;

use serde::{Deserialize, Serialize};

use crate::Service;
use crate::response_json;

/// The settings as `GET /api/settings` answers them. Serialized, they are the body of a
/// `PUT /api/settings`: every stored setting, the default frontend and the terminal command
/// included - the contract reads a missing one as none - and not the read-only device list, GPU
/// names, probe error or CPU-fallback notice.
#[derive(Debug, Clone, PartialEq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub device: String,
    pub threads: Option<u32>,
    pub default_language: String,
    pub speakers_mode: String,
    pub speakers_count: Option<u32>,
    pub max_speakers: u32,
    pub default_frontend: Option<String>,
    pub terminal_command: Option<String>,
    #[serde(default, skip_serializing)]
    pub available_devices: Vec<String>,
    #[serde(default, skip_serializing)]
    pub gpu_names: BTreeMap<String, String>,
}

/// The stored settings and the devices the user can choose.
pub async fn settings(service: &Service) -> io::Result<Settings> {
    let response = reqwest::Client::new()
        .get(format!("{}/api/settings", service.endpoint))
        .bearer_auth(&service.secret)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}

/// Stores `settings` as a whole; the service answers with the settings as they now are.
pub async fn save_settings(service: &Service, settings: &Settings) -> io::Result<Settings> {
    let response = reqwest::Client::new()
        .put(format!("{}/api/settings", service.endpoint))
        .bearer_auth(&service.secret)
        .json(settings)
        .send()
        .await
        .map_err(io::Error::other)?;
    response_json(response).await
}
